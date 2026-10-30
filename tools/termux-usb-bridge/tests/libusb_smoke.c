/* SPDX-License-Identifier: MIT */
#include <libusb.h>
#include <stdio.h>

int main(void)
{
    libusb_context *contexts[2] = {NULL, NULL};
    int result = 0;
    for (int i = 0; i < 2; ++i) {
        int error = libusb_init(&contexts[i]);
        if (error) { fprintf(stderr, "init: %s\n", libusb_error_name(error)); result = 1; break; }
        libusb_device **devices = NULL;
        ssize_t count = libusb_get_device_list(contexts[i], &devices);
        if (count < 0) { fprintf(stderr, "list: %s\n", libusb_error_name((int)count)); result = 1; break; }
        printf("context %d: %zd devices\n", i, count);
        for (ssize_t d = 0; d < count; ++d) {
            struct libusb_device_descriptor descriptor;
            error = libusb_get_device_descriptor(devices[d], &descriptor);
            if (error) result = 1;
            else printf("%04x:%04x\n", descriptor.idVendor, descriptor.idProduct);
        }
        libusb_free_device_list(devices, 1);
    }
    for (int i = 1; i >= 0; --i)
        if (contexts[i]) libusb_exit(contexts[i]);
    return result;
}
