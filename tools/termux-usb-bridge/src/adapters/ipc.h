/* SPDX-License-Identifier: MIT */
#ifndef TUSB_ADAPTER_IPC_H
#define TUSB_ADAPTER_IPC_H
#include <stddef.h>
int tusb_ipc_connect(const char *path);
int tusb_ipc_listen(const char *path);
int tusb_ipc_same_uid(int fd);
int tusb_ipc_write(int fd,const void *data,size_t size);
int tusb_ipc_read(int fd,void *data,size_t size);
#endif
