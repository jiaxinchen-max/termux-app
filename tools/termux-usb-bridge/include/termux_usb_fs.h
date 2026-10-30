/* SPDX-License-Identifier: MIT */
#ifndef TERMUX_USB_FS_H
#define TERMUX_USB_FS_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
struct tusb_fs_entry { uint64_t size; uint32_t attributes, fat_time; char name[1024]; };
struct tusb_fs_volume_info {
    uint64_t size, free;
    uint32_t serial, writable;
    char type[16], label[1024];
};
typedef int (*tusb_fs_visitor)(const struct tusb_fs_entry *, void *);
/* One mounted volume per process. All calls are serialized. Negative results set errno. */
int tusb_fs_mount_usb(const char *device, int writable, unsigned lun);
int tusb_fs_mount_image(const char *image, int writable);
int tusb_fs_unmount(void);
int tusb_fs_sync(void);
int tusb_fs_volume_info(struct tusb_fs_volume_info *info);
int tusb_fs_stat(const char *path, struct tusb_fs_entry *entry);
int tusb_fs_list(const char *path, tusb_fs_visitor visitor, void *userdata);
ssize_t tusb_fs_read(const char *path, void *data, size_t size, uint64_t offset);
ssize_t tusb_fs_write(const char *path, const void *data, size_t size, uint64_t offset, int truncate);
int tusb_fs_mkdir(const char *path);
int tusb_fs_unlink(const char *path);
int tusb_fs_rename(const char *source, const char *destination);
/* Creates a NEW image exclusively. Never formats an existing file or USB device. */
int tusb_fs_create_image(const char *path, uint64_t size, int exfat);
#endif
