/* SPDX-License-Identifier: MIT
 * Included only in libusb's Linux usbfs translation unit by apply-libusb.py.
 * Reuse upstream transfer/URB code; replace discovery and FD acquisition only. */
#include "tusb_client.c"
#include <poll.h>
#include <pthread.h>

static pthread_t tusb_monitor;
static int tusb_control[2] = {-1, -1};

static int tusb_enabled(void)
{
    const char *value = getenv("TERMUX_USB_BRIDGE_SOCKET");
    return value && *value;
}

static int tusb_libusb_error(void)
{
    switch (errno) {
    case ENODEV: case ENOENT: return LIBUSB_ERROR_NO_DEVICE;
    case EACCES: case EPERM: return LIBUSB_ERROR_ACCESS;
    case ETIMEDOUT: case EAGAIN: return LIBUSB_ERROR_TIMEOUT;
    case ENOMEM: return LIBUSB_ERROR_NO_MEM;
    default: return LIBUSB_ERROR_IO;
    }
}

static int tusb_backend_open(struct libusb_device *dev)
{
    char path[32];
    snprintf(path, sizeof(path), "/dev/bus/usb/%03u/%03u", dev->bus_number, dev->device_address);
    int fd = tusb_open(NULL, path);
    if (fd < 0) return tusb_libusb_error();
    if (lseek(fd, 0, SEEK_SET) < 0) {
        int error = tusb_libusb_error();
        close(fd);
        return error;
    }
    return fd;
}

static void tusb_address(const char *path, uint8_t *bus, uint8_t *device)
{
    /* tusb_list has validated these addresses. */
    unsigned b = 0, d = 0;
    sscanf(path, "/dev/bus/usb/%u/%u", &b, &d);
    *bus = (uint8_t)b;
    *device = (uint8_t)d;
}

static int tusb_backend_scan(struct libusb_context *ctx)
{
    struct tusb_device_list list;
    if (tusb_list(NULL, &list) < 0) return tusb_libusb_error();
    for (size_t i = 0; i < list.count; ++i) {
        uint8_t bus, device;
        tusb_address(list.paths[i], &bus, &device);
        /* Unauthorised devices cannot supply descriptors; retry on later polls. */
        int error = linux_enumerate_device(ctx, bus, device, NULL);
        if (error == LIBUSB_ERROR_NO_MEM) { tusb_free_list(&list); return error; }
    }
    tusb_free_list(&list);
    return LIBUSB_SUCCESS;
}

static void *tusb_backend_events(void *unused)
{
    (void)unused;
    struct tusb_device_list previous = {0};
    struct pollfd stop = {tusb_control[0], POLLIN, 0};
    for (;;) {
        struct tusb_device_list current;
        if (tusb_list(NULL, &current) == 0) {
            usbi_mutex_static_lock(&linux_hotplug_lock);
            for (size_t i = 0; i < previous.count; ++i) {
                size_t j;
                for (j = 0; j < current.count; ++j)
                    if (!strcmp(previous.paths[i], current.paths[j])) break;
                if (j == current.count) {
                    uint8_t bus, device;
                    tusb_address(previous.paths[i], &bus, &device);
                    linux_device_disconnected(bus, device);
                }
            }
            for (size_t i = 0; i < current.count; ++i) {
                uint8_t bus, device;
                tusb_address(current.paths[i], &bus, &device);
                linux_hotplug_enumerate(bus, device, NULL);
            }
            usbi_mutex_static_unlock(&linux_hotplug_lock);
            tusb_free_list(&previous);
            previous = current;
        }
        int result;
        do { result = poll(&stop, 1, 1000); } while (result < 0 && errno == EINTR);
        if (result != 0) break;
    }
    tusb_free_list(&previous);
    return NULL;
}

static int tusb_backend_start(void)
{
    if (pipe(tusb_control) < 0) return LIBUSB_ERROR_IO;
    if (fcntl(tusb_control[0], F_SETFD, FD_CLOEXEC) < 0 ||
        fcntl(tusb_control[1], F_SETFD, FD_CLOEXEC) < 0 ||
        pthread_create(&tusb_monitor, NULL, tusb_backend_events, NULL)) {
        close(tusb_control[0]); close(tusb_control[1]);
        return LIBUSB_ERROR_IO;
    }
    return LIBUSB_SUCCESS;
}

static int tusb_backend_stop(void)
{
    char byte = 0;
    ssize_t written;
    do { written = write(tusb_control[1], &byte, 1); } while (written < 0 && errno == EINTR);
    pthread_join(tusb_monitor, NULL);
    close(tusb_control[0]); close(tusb_control[1]);
    tusb_control[0] = tusb_control[1] = -1;
    return LIBUSB_SUCCESS;
}
