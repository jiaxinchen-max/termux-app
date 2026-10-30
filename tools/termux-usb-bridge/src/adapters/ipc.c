/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#define _DARWIN_C_SOURCE
#include "ipc.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
static int uid_of(int fd,uid_t *uid) {
#ifdef __linux__
    struct ucred credentials;socklen_t size=sizeof(credentials);
    if(getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&credentials,&size)) return -1;
    *uid=credentials.uid;return 0;
#else
    gid_t gid;return getpeereid(fd,uid,&gid);
#endif
}
int tusb_ipc_same_uid(int fd) {
    int pair[2];uid_t local,remote;
    if(socketpair(AF_UNIX,SOCK_STREAM,0,pair)) return -1;
    int r=uid_of(pair[0],&local),saved=errno;close(pair[0]);close(pair[1]);
    if(r) {errno=saved;return -1;}
    if(uid_of(fd,&remote)) return -1;
    if(local!=remote) {errno=EACCES;return -1;}return 0;
}
static int make_socket(struct sockaddr_un *address,const char *path) {
    if(!path || strlen(path)>=sizeof(address->sun_path)) {errno=ENAMETOOLONG;return -1;}
    memset(address,0,sizeof(*address));address->sun_family=AF_UNIX;strcpy(address->sun_path,path);
    int fd=socket(AF_UNIX,SOCK_STREAM,0);if(fd<0)return -1;
    fcntl(fd,F_SETFD,FD_CLOEXEC);
#ifdef SO_NOSIGPIPE
    int one=1;setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&one,sizeof(one));
#endif
    struct timeval timeout={0,200000};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
    setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout));return fd;
}
int tusb_ipc_connect(const char *path) {
    struct sockaddr_un address;int fd=make_socket(&address,path);if(fd<0)return -1;
    if(connect(fd,(struct sockaddr *)&address,sizeof(address)) || tusb_ipc_same_uid(fd)) {int e=errno;close(fd);errno=e;return -1;}return fd;
}
int tusb_ipc_listen(const char *path) {
    char parent[sizeof(((struct sockaddr_un *)0)->sun_path)];
    if(!path || strlen(path)>=sizeof(parent)) {errno=ENAMETOOLONG;return -1;}
    strcpy(parent,path);char *slash=strrchr(parent,'/');if(!slash || slash==parent) {errno=EINVAL;return -1;}*slash=0;
    if(mkdir(parent,0700) && errno!=EEXIST)return -1;
    struct stat info;if(stat(parent,&info))return -1;
    if(info.st_uid!=getuid() || (info.st_mode&077)) {errno=EACCES;return -1;}
    struct sockaddr_un address;int fd=make_socket(&address,path);if(fd<0)return -1;
    if(bind(fd,(struct sockaddr *)&address,sizeof(address))) {int e=errno;close(fd);errno=e;return -1;}
    if(chmod(path,0600) || listen(fd,32)) {int e=errno;close(fd);unlink(path);errno=e;return -1;}return fd;
}
int tusb_ipc_write(int fd,const void *data,size_t size) {
    const char *p=data;while(size) {
#ifdef MSG_NOSIGNAL
        ssize_t n=send(fd,p,size,MSG_NOSIGNAL);
#else
        ssize_t n=send(fd,p,size,0);
#endif
        if(n<0 && errno==EINTR)continue;if(n<=0)return -1;p+=n;size-=(size_t)n;
    }return 0;
}
int tusb_ipc_read(int fd,void *data,size_t size) {
    char *p=data;while(size) {ssize_t n=recv(fd,p,size,0);if(n<0 && errno==EINTR)continue;
        if(n<=0) {if(!n)errno=EPROTO;return -1;}p+=n;size-=(size_t)n;
    }return 0;
}
