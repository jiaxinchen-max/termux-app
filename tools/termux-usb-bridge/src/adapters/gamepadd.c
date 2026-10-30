/* SPDX-License-Identifier: MIT */
#include "termux_usb_bridge.h"
#include "termux_usb_gamepad.h"
#include "ipc.h"
#include <libusb.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "gamepad_wire.h"
static volatile sig_atomic_t stopping;
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static struct tusb_gamepad_state state;
static struct tusb_hid_descriptor descriptor;
static libusb_device_handle *usb;
static uint8_t endpoint;
static void stop(int signal_number) {(void)signal_number;stopping=1;}
static void *read_reports(void *unused)
{
    (void)unused;uint8_t data[4096];
    while(!stopping) {
        int count=0,r=libusb_interrupt_transfer(usb,endpoint,data,sizeof(data),&count,250);
        if(r==LIBUSB_ERROR_TIMEOUT)continue;
        if(r){pthread_mutex_lock(&mutex);state.connected=0;state.sequence++;pthread_mutex_unlock(&mutex);break;}
        pthread_mutex_lock(&mutex);tusb_hid_report(&descriptor,data,(size_t)count,&state);pthread_mutex_unlock(&mutex);
    }
    return NULL;
}
int main(int argc,char **argv)
{
    const char *device=NULL,*address=NULL,*descriptor_file=NULL,*report_hex=NULL;
    for(int i=1;i<argc;i++) {
        if(!strcmp(argv[i],"--device") && i+1<argc)device=argv[++i];
        else if(!strcmp(argv[i],"--socket") && i+1<argc)address=argv[++i];
        else if(!strcmp(argv[i],"--descriptor") && i+1<argc)descriptor_file=argv[++i];
        else if(!strcmp(argv[i],"--report") && i+1<argc)report_hex=argv[++i];
        else {fprintf(stderr,"Usage: tusb-gamepadd --socket PATH (--device USB_PATH | --descriptor FILE --report HEX)\n");return 2;}
    }
    if(!address || (!!device==!!descriptor_file) || (descriptor_file && !report_hex))return 2;
    uint8_t bytes[4096];int count=0,fd=-1,interface=-1,result=1,listener=-1;libusb_context *context=NULL;
    pthread_t thread;int thread_started=0;
    if(descriptor_file) {
        FILE *file=fopen(descriptor_file,"rb");if(!file){perror("descriptor");goto out;}
        count=(int)fread(bytes,1,sizeof(bytes),file);int invalid=ferror(file) || !feof(file);fclose(file);
        if(invalid || tusb_hid_parse(bytes,(size_t)count,&descriptor)){perror("HID descriptor");goto out;}
        size_t n=0;
        while(*report_hex) {while(*report_hex==' ' || *report_hex==':')report_hex++;if(!*report_hex)break;
            unsigned v;int consumed=0;if(n==sizeof(bytes) || sscanf(report_hex,"%2x%n",&v,&consumed)!=1 || consumed!=2)return 2;
            bytes[n++]=(uint8_t)v;report_hex+=consumed;
        }
        if(tusb_hid_report(&descriptor,bytes,n,&state)){perror("HID report");goto out;}
    } else {
        fd=tusb_open(NULL,device);if(fd<0){perror("USB open");goto out;}
        libusb_set_option(NULL,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        if(libusb_init(&context) || libusb_wrap_sys_device(context,fd,&usb)){fprintf(stderr,"libusb init/wrap failed\n");goto out;}
        struct libusb_config_descriptor *config=NULL;
        if(libusb_get_active_config_descriptor(libusb_get_device(usb),&config))goto out;
        for(unsigned i=0;i<config->bNumInterfaces;i++)for(int a=0;a<config->interface[i].num_altsetting;a++){
            const struct libusb_interface_descriptor *item=&config->interface[i].altsetting[a];
            if(item->bInterfaceClass!=3 || item->bInterfaceSubClass==1 || item->bAlternateSetting)continue;
            for(unsigned e=0;e<item->bNumEndpoints;e++)if((item->endpoint[e].bmAttributes&3)==LIBUSB_TRANSFER_TYPE_INTERRUPT && (item->endpoint[e].bEndpointAddress&0x80)){
                interface=item->bInterfaceNumber;endpoint=item->endpoint[e].bEndpointAddress;goto found;
            }
        }
found:libusb_free_config_descriptor(config);
        if(interface<0){fprintf(stderr,"No supported HID interrupt interface\n");goto out;}
        libusb_set_auto_detach_kernel_driver(usb,1);
        if(libusb_claim_interface(usb,interface)){fprintf(stderr,"HID interface busy\n");goto out;}
        count=libusb_control_transfer(usb,0x81,LIBUSB_REQUEST_GET_DESCRIPTOR,0x2200,(uint16_t)interface,bytes,sizeof(bytes),3000);
        if(count<0 || tusb_hid_parse(bytes,(size_t)count,&descriptor)){fprintf(stderr,"Unsupported HID gamepad descriptor\n");goto out;}
        /* Do not claim connected until a valid report has arrived. */
    }
    signal(SIGINT,stop);signal(SIGTERM,stop);
    listener=tusb_ipc_listen(address);if(listener<0){perror("gamepad socket");goto out;}
    if(device){if(pthread_create(&thread,NULL,read_reports,NULL))goto out;thread_started=1;}
    fprintf(stderr,"gamepad listening: %s\n",address);
    while(!stopping){
        struct pollfd pollfd={listener,POLLIN,0};int n=poll(&pollfd,1,200);
        if(n<0 && errno==EINTR)continue;if(n<0)goto out;if(!n)continue;
        int client=accept(listener,NULL,NULL);if(client<0)continue;
        if(!tusb_ipc_same_uid(client)){
            unsigned char packet[48];pthread_mutex_lock(&mutex);gp_encode(packet,&state);pthread_mutex_unlock(&mutex);
            tusb_ipc_write(client,packet,sizeof(packet));
        }
        close(client);
    }
    result=0;
out:
    stopping=1;if(thread_started)pthread_join(thread,NULL);
    if(listener>=0){close(listener);unlink(address);}
    if(usb){if(interface>=0)libusb_release_interface(usb,interface);libusb_close(usb);}
    if(context)libusb_exit(context);if(fd>=0)close(fd);return result;
}
