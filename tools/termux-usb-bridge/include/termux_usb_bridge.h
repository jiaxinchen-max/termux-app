/* SPDX-License-Identifier: MIT */
#ifndef TERMUX_USB_BRIDGE_H
#define TERMUX_USB_BRIDGE_H
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

struct tusb_device_list {
    size_t count;
    char **paths;
};

/* NULL socket_path uses TERMUX_USB_BRIDGE_SOCKET, then $TMPDIR/termux-usb-bridge/broker.sock.
 * Errors return -1 and set errno. Each returned FD belongs to the caller.
 * Opening never prompts for Android permission; authorize explicitly first. */
int tusb_list(const char *socket_path, struct tusb_device_list *list);
void tusb_free_list(struct tusb_device_list *list);
int tusb_open(const char *socket_path, const char *device_path);

#ifdef __cplusplus
}
#endif
#endif
