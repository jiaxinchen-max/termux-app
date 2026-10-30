/* SPDX-License-Identifier: MIT */
#include "termux_usb_bridge.h"
#include <libusb.h>
#include <stdio.h>
#include <unistd.h>
int main(int argc,char **argv)
{
    if(argc!=2) {fprintf(stderr,"Usage: tusb-inspect USB_PATH\n");return 2;}
    int fd=tusb_open(NULL,argv[1]);if(fd<0){perror("tusb_open");return 1;}
    libusb_context *context=NULL;libusb_device_handle *handle=NULL;
    libusb_set_option(NULL,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int error=libusb_init(&context);
    if(!error) error=libusb_wrap_sys_device(context,fd,&handle);
    struct libusb_device_descriptor descriptor;
    if(!error) error=libusb_get_device_descriptor(libusb_get_device(handle),&descriptor);
    struct libusb_config_descriptor *config=NULL;
    if(!error) error=libusb_get_active_config_descriptor(libusb_get_device(handle),&config);
    if(!error) {
        int storage=0,camera=0,hid=0;
        for(unsigned i=0;i<config->bNumInterfaces;i++) for(int a=0;a<config->interface[i].num_altsetting;a++) {
            const struct libusb_interface_descriptor *in=&config->interface[i].altsetting[a];
            storage|=in->bInterfaceClass==8 && in->bInterfaceSubClass==6 && in->bInterfaceProtocol==0x50;
            camera|=in->bInterfaceClass==14 && in->bInterfaceSubClass==1;
            hid|=in->bInterfaceClass==3;
        }
        printf("{\"vendor\":%u,\"product\":%u,\"storage\":%s,\"camera\":%s,\"hid\":%s}\n",
            descriptor.idVendor,descriptor.idProduct,storage?"true":"false",camera?"true":"false",hid?"true":"false");
    } else fprintf(stderr,"USB inspect: %s\n",libusb_error_name(error));
    if(config) libusb_free_config_descriptor(config);
    if(handle) libusb_close(handle);
    if(context) libusb_exit(context);
    close(fd);return error?1:0;
}
