/* SPDX-License-Identifier: MIT
 * PID-1-only integration test. The direct-open shim is ONLY for an isolated VM. */
#define _GNU_SOURCE
#include "termux_usb_fs.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <unistd.h>
static char device_path[128];

int tusb_open(const char *socket_path,const char *device)
{
    (void)socket_path;
    if(getpid()!=1 || strcmp(device,device_path)){errno=EPERM;return -1;}
    return open(device,O_RDWR|O_CLOEXEC);
}
static void finish(int result)
{
    printf("TUSB_QEMU_STORAGE_%s\n",result?"FAIL":"PASS");fflush(stdout);sync();reboot(RB_POWER_OFF);
    for(;;)pause();
}
int main(void)
{
    if(getpid()!=1){fprintf(stderr,"This test must only run as PID 1 inside the test VM\n");return 2;}
    char command[4096]={0};int fd=open("/proc/cmdline",O_RDONLY);
    if(fd>=0){ssize_t n=read(fd,command,sizeof(command)-1);if(n<0)command[0]=0;close(fd);}
    if(!strstr(command,"tusb_usb_storage_test=1")){fprintf(stderr,"Missing isolated-test boot flag\n");finish(1);}
    for(int attempt=0;attempt<100 && !*device_path;attempt++) {
        DIR *buses=opendir("/dev/bus/usb");struct dirent *bus;
        if(buses) {
            while((bus=readdir(buses))) {
                if(bus->d_name[0]=='.')continue;
                char directory[128];snprintf(directory,sizeof(directory),"/dev/bus/usb/%.20s",bus->d_name);
                DIR *nodes=opendir(directory);struct dirent *node;if(!nodes)continue;
                while((node=readdir(nodes))) {
                    if(node->d_name[0]=='.')continue;
                    char path[128];snprintf(path,sizeof(path),"%s/%.20s",directory,node->d_name);
                    int usb=open(path,O_RDWR);if(usb<0)continue;
                    unsigned char descriptor[18];ssize_t n=read(usb,descriptor,sizeof(descriptor));close(usb);
                    if(n==18 && descriptor[4]!=9){snprintf(device_path,sizeof(device_path),"%s",path);break;}
                }
                closedir(nodes);if(*device_path)break;
            }
            closedir(buses);
        }
        if(!*device_path)usleep(50000);
    }
    printf("QEMU USB device: %s\n",device_path);
    if(!*device_path || tusb_fs_mount_usb(device_path,1,0)){perror("USB BOT mount");finish(1);}
    if(tusb_fs_mkdir("/qemu-usb")){perror("mkdir");finish(1);}
    size_t size=131123;unsigned char *written=malloc(size),*readback=malloc(size);
    if(!written || !readback)finish(1);
    for(size_t i=0;i<size;i++)written[i]=(unsigned char)(i*17+3);
    if(tusb_fs_write("/qemu-usb/original.bin",written,size,0,1)!=(ssize_t)size){perror("USB write");finish(1);}
    if(tusb_fs_read("/qemu-usb/original.bin",readback,size,0)!=(ssize_t)size || memcmp(written,readback,size)){perror("USB readback");finish(1);}
    if(tusb_fs_rename("/qemu-usb/original.bin","/qemu-usb/moved.bin")){perror("rename");finish(1);}
    struct tusb_fs_entry entry;
    if(tusb_fs_stat("/qemu-usb/moved.bin",&entry) || entry.size!=size){perror("stat");finish(1);}
    if(tusb_fs_unmount()){perror("unmount");finish(1);}
    free(written);free(readback);finish(0);
}
