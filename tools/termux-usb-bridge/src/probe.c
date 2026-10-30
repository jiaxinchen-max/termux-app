/* SPDX-License-Identifier: MIT */
#include "termux_usb_bridge.h"
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#ifdef TUSB_WITH_LIBUSB
#include <libusb.h>
#endif

int main(int argc, char **argv)
{
    if (argc == 1) {
        struct tusb_device_list list;
        if (tusb_list(NULL, &list) < 0) { perror("tusb_list"); return 1; }
        for (size_t i = 0; i < list.count; ++i) puts(list.paths[i]);
        tusb_free_list(&list);
        return 0;
    }
    int fd = tusb_open(NULL, argv[1]);
    if (fd < 0) { perror("tusb_open"); return 1; }
    int result = 0;
#ifdef TUSB_WITH_LIBUSB
    libusb_context *ctx = NULL;
    libusb_device_handle *handle = NULL;
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int error = libusb_init(&ctx);
    if (!error) error = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    struct libusb_device_descriptor descriptor;
    if (!error) error = libusb_get_device_descriptor(libusb_get_device(handle), &descriptor);
    if (error) { fprintf(stderr, "libusb: %s\n", libusb_error_name(error)); result = 1; }
    else printf("%04x:%04x class=%02x\n", descriptor.idVendor, descriptor.idProduct, descriptor.bDeviceClass);
    if (handle) libusb_close(handle);
    if (ctx) libusb_exit(ctx);
#else
    unsigned char buffer[18];
    ssize_t n = read(fd, buffer, sizeof(buffer));
    if (n < 0) { perror("read"); result = 1; }
    else {
        printf("descriptor bytes:");
        for (ssize_t i = 0; i < n; ++i) printf(" %02x", buffer[i]);
        putchar('\n');
    }
#endif
    close(fd);
    return result;
}
