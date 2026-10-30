/* SPDX-License-Identifier: MIT */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#ifndef _DARWIN_C_SOURCE
#define _DARWIN_C_SOURCE
#endif
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#include "termux_usb_bridge.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define TUSB_LIMIT 65536u

static int peer_uid(int fd, uid_t *uid)
{
#if defined(__linux__)
    int credentials[3];
    socklen_t size = sizeof(credentials);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, credentials, &size) < 0) return -1;
    if (size != sizeof(credentials)) { errno = EPROTO; return -1; }
    *uid = (uid_t)credentials[1];
    return 0;
#elif defined(__APPLE__)
    gid_t gid;
    return getpeereid(fd, uid, &gid);
#else
#error "Implement peer credential verification for this platform"
#endif
}

static int verify_peer(int fd)
{
    /* getuid() is spoofed by PRoot -0. Compare two kernel-issued credentials. */
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) < 0) return -1;
    uid_t actual, remote;
    int result = peer_uid(pair[0], &actual);
    int saved = errno;
    close(pair[0]);
    close(pair[1]);
    if (result < 0) { errno = saved; return -1; }
    if (peer_uid(fd, &remote) < 0) return -1;
    if (actual != remote) { errno = EACCES; return -1; }
    return 0;
}

static int write_all(int fd, const void *buffer, size_t length)
{
    const unsigned char *p = buffer;
    while (length) {
#ifdef MSG_NOSIGNAL
        ssize_t n = send(fd, p, length, MSG_NOSIGNAL);
#else
        ssize_t n = send(fd, p, length, 0);
#endif
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = EPIPE; return -1; }
        p += n;
        length -= (size_t)n;
    }
    return 0;
}

static int read_all(int fd, void *buffer, size_t length)
{
    unsigned char *p = buffer;
    while (length) {
        ssize_t n = recv(fd, p, length, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = EPROTO; return -1; }
        p += n;
        length -= (size_t)n;
    }
    return 0;
}

static int transact(const char *path, unsigned char op, const char *request,
                    char **body, size_t *body_length, int *device_fd)
{
    struct sockaddr_un address = {0};
    char default_path[sizeof(address.sun_path)];
    unsigned char header[12] = {'T','U','S','B',1,0,0,0,0,0,0,0};
    size_t length = request ? strlen(request) : 0;
    int received = -1, fd = -1, result = -1, saved;
    *body = NULL;
    *body_length = 0;
    *device_fd = -1;
    if (length > TUSB_LIMIT) { errno = EMSGSIZE; return -1; }
    if (!path) path = getenv("TERMUX_USB_BRIDGE_SOCKET");
    if (!path || !*path) {
        const char *tmp = getenv("TMPDIR");
        int n = snprintf(default_path, sizeof(default_path), "%s/termux-usb-bridge/broker.sock",
                         tmp && *tmp ? tmp : "/tmp");
        if (n < 0 || (size_t)n >= sizeof(default_path)) { errno = ENAMETOOLONG; return -1; }
        path = default_path;
    }
    if (strlen(path) >= sizeof(address.sun_path)) { errno = ENAMETOOLONG; return -1; }
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) goto out;
#ifdef SO_NOSIGPIPE
    { int one = 1; if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one)) < 0) goto out; }
#endif
    struct timeval timeout = {15, 0};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) < 0 ||
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) < 0) goto out;
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, strlen(path) + 1);
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) goto out;
    if (verify_peer(fd) < 0) goto out;
    header[5] = op;
    header[8] = (unsigned char)(length >> 24);
    header[9] = (unsigned char)(length >> 16);
    header[10] = (unsigned char)(length >> 8);
    header[11] = (unsigned char)length;
    if (write_all(fd, header, sizeof(header)) < 0 ||
        (length && write_all(fd, request, length) < 0)) goto out;

    /* Read the first byte with recvmsg: SCM_RIGHTS belongs to that byte.
     * Stream reads may be short; consume the rest with read_all. */
    union { struct cmsghdr align; unsigned char bytes[256]; } control;
    struct iovec iov = {header, 1};
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.bytes;
    msg.msg_controllen = sizeof(control.bytes);
    ssize_t n;
    do { n = recvmsg(fd, &msg, 0); } while (n < 0 && errno == EINTR);
    if (n <= 0) { if (!n) errno = EPROTO; goto out; }
    int count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(0)) {
            size_t nfds = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            for (size_t i = 0; i < nfds; ++i) {
                int next;
                memcpy(&next, (unsigned char *)CMSG_DATA(c) + i * sizeof(int), sizeof(next));
                if (count++ == 0) received = next;
                else close(next);
            }
        }
    }
    if ((msg.msg_flags & MSG_CTRUNC) || count > 1) { errno = EPROTO; goto out; }
    if (read_all(fd, header + 1, sizeof(header) - 1) < 0) goto out;
    if (memcmp(header, "TUSB", 4) || header[4] != 1 || header[5] != op) { errno = EPROTO; goto out; }
    unsigned status = (unsigned)header[6] << 8 | header[7];
    length = (size_t)header[8] << 24 | (size_t)header[9] << 16 |
             (size_t)header[10] << 8 | header[11];
    if (length > TUSB_LIMIT) { errno = EMSGSIZE; goto out; }
    *body = malloc(length + 1);
    if (!*body) goto out;
    if (read_all(fd, *body, length) < 0) goto out;
    (*body)[length] = '\0';
    if (status) { errno = status; goto out; }
    if ((op == 2 && (received < 0 || length)) || (op == 1 && received >= 0)) {
        errno = EPROTO;
        goto out;
    }
    if (received >= 0 && fcntl(received, F_SETFD, FD_CLOEXEC) < 0) goto out;
    *body_length = length;
    *device_fd = received;
    received = -1;
    result = 0;
out:
    saved = errno;
    if (received >= 0) close(received);
    if (fd >= 0) close(fd);
    if (result < 0) { free(*body); *body = NULL; }
    errno = saved;
    return result;
}

void tusb_free_list(struct tusb_device_list *list)
{
    if (!list) return;
    for (size_t i = 0; i < list->count; ++i) free(list->paths[i]);
    free(list->paths);
    list->paths = NULL;
    list->count = 0;
}

int tusb_list(const char *socket_path, struct tusb_device_list *list)
{
    char *body;
    size_t length;
    int fd;
    if (!list) { errno = EINVAL; return -1; }
    list->count = 0;
    list->paths = NULL;
    if (transact(socket_path, 1, NULL, &body, &length, &fd) < 0) return -1;
    if (memchr(body, '\0', length)) { free(body); errno = EPROTO; return -1; }
    char *start = body;
    while ((size_t)(start - body) < length) {
        char *end = strchr(start, '\n');
        if (!end || end == start) goto bad;
        *end = '\0';
        unsigned bus, dev;
        int used = 0;
        if (sscanf(start, "/dev/bus/usb/%3u/%3u%n", &bus, &dev, &used) != 2 ||
            start[used] || !bus || bus > 255 || !dev || dev > 127) goto bad;
        char **next = realloc(list->paths, (list->count + 1) * sizeof(*next));
        if (!next) goto fail;
        list->paths = next;
        list->paths[list->count] = strdup(start);
        if (!list->paths[list->count]) goto fail;
        ++list->count;
        start = end + 1;
    }
    free(body);
    return 0;
bad:
    errno = EPROTO;
fail:
    { int saved = errno; free(body); tusb_free_list(list); errno = saved; }
    return -1;
}

int tusb_open(const char *socket_path, const char *device_path)
{
    if (!device_path) { errno = EINVAL; return -1; }
    char *body;
    size_t length;
    int fd;
    if (transact(socket_path, 2, device_path, &body, &length, &fd) < 0) return -1;
    free(body);
    return fd;
}
