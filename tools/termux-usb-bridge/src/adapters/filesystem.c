/* SPDX-License-Identifier: MIT */
#define _FILE_OFFSET_BITS 64
#include "termux_usb_fs.h"
#include "termux_usb_bridge.h"
#include "ff.h"
#include "diskio.h"
#include <libusb.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static FATFS volume;
static struct {
    int active, image, writable, usb_fd, interface;
    uint32_t sector_size, tag;
    uint64_t sectors;
    uint8_t in, out, lun;
    libusb_context *context;
    libusb_device_handle *usb;
} disk = {.image = -1, .usb_fd = -1, .interface = -1};

static void le32(uint8_t *p, uint32_t v) { for (int i=0;i<4;i++) p[i]=(uint8_t)(v>>(8*i)); }
static uint32_t read_le32(const uint8_t *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
static void be32(uint8_t *p, uint32_t v) { for (int i=0;i<4;i++) p[i]=(uint8_t)(v>>(24-8*i)); }
static uint32_t read_be32(const uint8_t *p) { return (uint32_t)p[3]|(uint32_t)p[2]<<8|(uint32_t)p[1]<<16|(uint32_t)p[0]<<24; }
static void be64(uint8_t *p, uint64_t v) { be32(p,(uint32_t)(v>>32));be32(p+4,(uint32_t)v); }

static void bot_reset(void)
{
    libusb_control_transfer(disk.usb, 0x21, 0xff, 0, (uint16_t)disk.interface, NULL, 0, 1000);
    libusb_clear_halt(disk.usb,disk.in);
    libusb_clear_halt(disk.usb,disk.out);
}

static int scsi(const uint8_t *cdb, unsigned cdb_size, void *data, uint32_t size, int input)
{
    uint8_t cbw[31]={0}, csw[13];
    uint32_t tag=++disk.tag;
    le32(cbw,0x43425355);le32(cbw+4,tag);le32(cbw+8,size);
    cbw[12]=input?0x80:0;cbw[13]=disk.lun;cbw[14]=(uint8_t)cdb_size;
    memcpy(cbw+15,cdb,cdb_size);
    int actual=0, result=libusb_bulk_transfer(disk.usb,disk.out,cbw,31,&actual,5000);
    if (result || actual!=31) goto protocol_error;
    uint32_t transferred=0;
    while (transferred<size) {
        int amount=(int)((size-transferred)>65536?65536:(size-transferred));
        actual=0;
        result=libusb_bulk_transfer(disk.usb,input?disk.in:disk.out,(uint8_t *)data+transferred,amount,&actual,10000);
        if (actual<0 || actual>amount) goto protocol_error;
        transferred+=(uint32_t)actual;
        if (result==LIBUSB_ERROR_PIPE) { libusb_clear_halt(disk.usb,input?disk.in:disk.out);break; }
        if (result || actual<amount) { if (result) goto protocol_error;break; }
    }
    actual=0;
    result=libusb_bulk_transfer(disk.usb,disk.in,csw,13,&actual,5000);
    if (result==LIBUSB_ERROR_PIPE) {
        libusb_clear_halt(disk.usb,disk.in);
        result=libusb_bulk_transfer(disk.usb,disk.in,csw,13,&actual,5000);
    }
    if (result || actual!=13 || read_le32(csw)!=0x53425355 || read_le32(csw+4)!=tag || read_le32(csw+8)>size || csw[12]>2)
        goto protocol_error;
    if (csw[12]==2) goto protocol_error;
    if (csw[12] || read_le32(csw+8) || transferred!=size) { errno=EIO;return -1; }
    return 0;
protocol_error:
    bot_reset();errno=EIO;return -1;
}

static int sectors_io(void *buffer, uint64_t sector, unsigned count, int writing)
{
    if (!disk.active || sector>disk.sectors || count>disk.sectors-sector) { errno=EINVAL;return -1; }
    if (writing && !disk.writable) { errno=EROFS;return -1; }
    size_t total=(size_t)count*disk.sector_size;
    if (disk.image>=0) {
        size_t done=0;
        while (done<total) {
            ssize_t n=writing?pwrite(disk.image,(char *)buffer+done,total-done,(off_t)(sector*disk.sector_size+done)):
                pread(disk.image,(char *)buffer+done,total-done,(off_t)(sector*disk.sector_size+done));
            if (n<0 && errno==EINTR) continue;
            if (n<=0) { if (!n) errno=EIO;return -1; } done+=(size_t)n;
        }
        return 0;
    }
    while (count) {
        unsigned n=count>128?128:count;
        uint8_t command[16]={0};
        command[0]=writing?0x8a:0x88;be64(command+2,sector);be32(command+10,n);
        /* READ/WRITE(10) is more widely supported on older USB sticks. */
        unsigned command_size=16;
        if (sector+n-1<=UINT32_MAX) {
            memset(command,0,16);command[0]=writing?0x2a:0x28;be32(command+2,(uint32_t)sector);
            command[7]=(uint8_t)(n>>8);command[8]=(uint8_t)n;command_size=10;
        }
        if (scsi(command,command_size,buffer,n*disk.sector_size,!writing)<0) return -1;
        buffer=(uint8_t *)buffer+n*disk.sector_size;sector+=n;count-=n;
    }
    return 0;
}

DSTATUS disk_initialize(BYTE drive) { return drive || !disk.active?STA_NOINIT:disk.writable?0:STA_PROTECT; }
DSTATUS disk_status(BYTE drive) { return disk_initialize(drive); }
DRESULT disk_read(BYTE drive,BYTE *buffer,LBA_t sector,UINT count) { return drive || sectors_io(buffer,sector,count,0)<0?RES_ERROR:RES_OK; }
DRESULT disk_write(BYTE drive,const BYTE *buffer,LBA_t sector,UINT count) {
    if (!disk.writable) return RES_WRPRT;
    return drive || sectors_io((void *)buffer,sector,count,1)<0?RES_ERROR:RES_OK;
}
DRESULT disk_ioctl(BYTE drive,BYTE command,void *buffer)
{
    if (drive || !disk.active) return RES_NOTRDY;
    switch(command) {
    case GET_SECTOR_COUNT: *(LBA_t *)buffer=disk.sectors;return RES_OK;
    case GET_SECTOR_SIZE: *(WORD *)buffer=(WORD)disk.sector_size;return RES_OK;
    case GET_BLOCK_SIZE: *(DWORD *)buffer=1;return RES_OK;
    case CTRL_SYNC:
        if (!disk.writable) return RES_OK;
        if (disk.image>=0) return fsync(disk.image)?RES_ERROR:RES_OK;
        { uint8_t command[10]={0x35};return scsi(command,10,NULL,0,0)<0?RES_ERROR:RES_OK; }
    default:return RES_PARERR;
    }
}
DWORD get_fattime(void)
{
    time_t now=time(NULL);struct tm t;
    if (!localtime_r(&now,&t) || t.tm_year<80) return (DWORD)(2026-1980)<<25|1<<21|1<<16;
    return (DWORD)(t.tm_year-80)<<25|(DWORD)(t.tm_mon+1)<<21|(DWORD)t.tm_mday<<16|
        (DWORD)t.tm_hour<<11|(DWORD)t.tm_min<<5|(DWORD)t.tm_sec/2;
}

static int failure(FRESULT result)
{
    static const int errors[]={0,EIO,EPROTO,ENODEV,ENOENT,ENOENT,EINVAL,EACCES,EEXIST,EBADF,
        EROFS,ENODEV,ENODEV,ENODEV,EINVAL,ETIMEDOUT,EBUSY,ENOMEM,EMFILE,EINVAL};
    if (result==FR_OK) return 0;
    errno=(unsigned)result<sizeof(errors)/sizeof(errors[0])?errors[result]:EIO;return -1;
}
static int check_path(const char *path)
{
    if (!disk.active) { errno=ENODEV;return -1; }
    if (!path || *path!='/' || strlen(path)>4095 || strchr(path,':') || strchr(path,'\\')) { errno=EINVAL;return -1; }
    const char *p=path;
    while (*p) { while (*p=='/') p++;const char *end=strchr(p,'/');size_t n=end?(size_t)(end-p):strlen(p);
        if ((n==2 && !memcmp(p,"..",2)) || (n==1 && *p=='.')) { errno=EINVAL;return -1; }
        if (!end) break;p=end;
    }
    return 0;
}
static void release_disk(void)
{
    f_mount(NULL,"0:",0);
    if (disk.usb) { if(disk.interface>=0) libusb_release_interface(disk.usb,disk.interface);libusb_close(disk.usb); }
    if (disk.context) libusb_exit(disk.context);
    if (disk.usb_fd>=0) close(disk.usb_fd);
    if (disk.image>=0) close(disk.image);
    memset(&disk,0,sizeof(disk));disk.image=disk.usb_fd=disk.interface=-1;
}
int tusb_fs_sync(void)
{
    pthread_mutex_lock(&lock);
    int result=!disk.active?(errno=ENODEV,-1):disk_ioctl(0,CTRL_SYNC,NULL)==RES_OK?0:(errno=EIO,-1);
    pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_unmount(void)
{
    pthread_mutex_lock(&lock);
    int result=disk.active && disk_ioctl(0,CTRL_SYNC,NULL)!=RES_OK?(errno=EIO,-1):0;
    int saved=errno;release_disk();errno=saved;
    pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_volume_info(struct tusb_fs_volume_info *info)
{
    pthread_mutex_lock(&lock);int result=-1;DWORD clusters,serial;FATFS *fs;
    if(!info) {errno=EINVAL;goto out;}
    if(!disk.active) {errno=ENODEV;goto out;}
    if(failure(f_getfree("0:",&clusters,&fs))<0) goto out;
    char label[1024]={0};
    if(failure(f_getlabel("0:",label,&serial))<0) goto out;
    memset(info,0,sizeof(*info));
    info->size=(uint64_t)(fs->n_fatent-2)*fs->csize*disk.sector_size;
    info->free=(uint64_t)clusters*fs->csize*disk.sector_size;
    info->serial=serial;info->writable=disk.writable;
    const char *types[]={"unknown","FAT12","FAT16","FAT32","exFAT"};
    snprintf(info->type,sizeof(info->type),"%s",types[fs->fs_type<=4?fs->fs_type:0]);
    snprintf(info->label,sizeof(info->label),"%s",label);result=0;
out:pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_mount_image(const char *path,int writable)
{
    pthread_mutex_lock(&lock);int result=-1;
    if(disk.active) {errno=EBUSY;goto out;}
    disk.image=open(path,(writable?O_RDWR:O_RDONLY)|O_CLOEXEC);
    if(disk.image<0) goto out;
    off_t size=lseek(disk.image,0,SEEK_END);
    if(size<512 || size%512) {errno=EINVAL;goto bad;}
    disk.sectors=(uint64_t)size/512;disk.sector_size=512;disk.writable=writable;disk.active=1;
    if(failure(f_mount(&volume,"0:",1))<0) goto bad;
    result=0;goto out;
bad:{int error=errno;release_disk();errno=error;}
out:pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_mount_usb(const char *path,int writable,unsigned lun)
{
    pthread_mutex_lock(&lock);int result=-1;
    if(disk.active) {errno=EBUSY;goto out;}
    if(lun>15) {errno=EINVAL;goto out;}
    disk.usb_fd=tusb_open(NULL,path);if(disk.usb_fd<0) goto out;
    libusb_set_option(NULL,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if(libusb_init(&disk.context) || libusb_wrap_sys_device(disk.context,disk.usb_fd,&disk.usb)) {errno=EIO;goto bad;}
    struct libusb_config_descriptor *config=NULL;
    if(libusb_get_active_config_descriptor(libusb_get_device(disk.usb),&config)) {errno=EIO;goto bad;}
    for(unsigned i=0;i<config->bNumInterfaces;i++) for(int a=0;a<config->interface[i].num_altsetting;a++) {
        const struct libusb_interface_descriptor *in=&config->interface[i].altsetting[a];
        if(in->bInterfaceClass!=8 || in->bInterfaceSubClass!=6 || in->bInterfaceProtocol!=0x50 || in->bAlternateSetting) continue;
        uint8_t input=0,output=0;
        for(unsigned e=0;e<in->bNumEndpoints;e++) {
            const struct libusb_endpoint_descriptor *ep=&in->endpoint[e];
            if((ep->bmAttributes&3)==LIBUSB_TRANSFER_TYPE_BULK) {
                if(ep->bEndpointAddress&0x80) input=ep->bEndpointAddress;else output=ep->bEndpointAddress;
            }
        }
        if(input && output) {disk.interface=in->bInterfaceNumber;disk.in=input;disk.out=output;goto found;}
    }
found:libusb_free_config_descriptor(config);
    if(disk.interface<0) {errno=ENOTSUP;goto bad;}
    libusb_set_auto_detach_kernel_driver(disk.usb,1);
    if(libusb_claim_interface(disk.usb,disk.interface)) {errno=EBUSY;goto bad;}
    uint8_t max_lun=0;
    int n=libusb_control_transfer(disk.usb,0xa1,0xfe,0,(uint16_t)disk.interface,&max_lun,1,1000);
    if(n==LIBUSB_ERROR_PIPE) max_lun=0;
    else if(n!=1) {errno=EIO;goto bad;}
    if(lun>max_lun) {errno=ENODEV;goto bad;}disk.lun=(uint8_t)lun;
    uint8_t inquiry[36],command[16]={0x12,0,0,0,36};
    if(scsi(command,6,inquiry,36,1)<0) goto bad;
    uint8_t capacity[32];memset(command,0,16);command[0]=0x25;
    if(scsi(command,10,capacity,8,1)<0) goto bad;
    disk.sectors=(uint64_t)read_be32(capacity)+1;disk.sector_size=read_be32(capacity+4);
    if(disk.sectors==UINT64_C(0x100000000)) {
        memset(command,0,16);command[0]=0x9e;command[1]=0x10;command[13]=32;
        if(scsi(command,16,capacity,32,1)<0) goto bad;
        uint64_t last=(uint64_t)read_be32(capacity)<<32|read_be32(capacity+4);
        if(last==UINT64_MAX) {errno=EOVERFLOW;goto bad;}
        disk.sectors=last+1;disk.sector_size=read_be32(capacity+8);
    }
    if(disk.sector_size<512 || disk.sector_size>4096 || (disk.sector_size&(disk.sector_size-1))) {errno=ENOTSUP;goto bad;}
    disk.active=1;disk.writable=writable;
    if(failure(f_mount(&volume,"0:",1))<0) goto bad;
    result=0;goto out;
bad:{int error=errno;release_disk();errno=error;}
out:pthread_mutex_unlock(&lock);return result;
}
static void fill_entry(struct tusb_fs_entry *entry,const FILINFO *info)
{
    memset(entry,0,sizeof(*entry));entry->size=info->fsize;entry->attributes=info->fattrib;
    entry->fat_time=(uint32_t)info->fdate<<16|info->ftime;
    snprintf(entry->name,sizeof(entry->name),"%s",info->fname);
}
int tusb_fs_stat(const char *path,struct tusb_fs_entry *entry)
{
    pthread_mutex_lock(&lock);int result=-1;FILINFO info;
    if(!entry || check_path(path)<0) goto out;
    if(!strcmp(path,"/")) {memset(entry,0,sizeof(*entry));entry->attributes=AM_DIR;result=0;}
    else if(!(result=failure(f_stat(path,&info)))) fill_entry(entry,&info);
out:pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_list(const char *path,tusb_fs_visitor visitor,void *userdata)
{
    pthread_mutex_lock(&lock);int result=-1;DIR dir;
    if(!visitor || check_path(path)<0) goto out;
    if(failure(f_opendir(&dir,path))<0) goto out;
    FILINFO info;
    for(;;) {
        result=failure(f_readdir(&dir,&info));if(result<0 || !*info.fname) break;
        struct tusb_fs_entry entry;fill_entry(&entry,&info);
        if(visitor(&entry,userdata)) {errno=ECANCELED;result=-1;break;}
    }
    f_closedir(&dir);
out:pthread_mutex_unlock(&lock);return result;
}
ssize_t tusb_fs_read(const char *path,void *data,size_t size,uint64_t offset)
{
    pthread_mutex_lock(&lock);ssize_t result=-1;FIL file;UINT actual=0;
    if(check_path(path)<0) goto out;
    if(size>UINT_MAX) {errno=EOVERFLOW;goto out;}
    if(failure(f_open(&file,path,FA_READ))<0) goto out;
    if(failure(f_lseek(&file,offset))<0 || failure(f_read(&file,data,(UINT)size,&actual))<0) {int e=errno;f_close(&file);errno=e;goto out;}
    result=actual;if(failure(f_close(&file))<0) result=-1;
out:pthread_mutex_unlock(&lock);return result;
}
ssize_t tusb_fs_write(const char *path,const void *data,size_t size,uint64_t offset,int truncate)
{
    pthread_mutex_lock(&lock);ssize_t result=-1;FIL file;UINT actual=0;
    if(check_path(path)<0) goto out;
    if(!disk.writable) {errno=EROFS;goto out;}
    if(size>UINT_MAX) {errno=EOVERFLOW;goto out;}
    if(failure(f_open(&file,path,FA_WRITE|FA_OPEN_ALWAYS))<0) goto out;
    if(failure(f_lseek(&file,offset))<0 || (truncate && failure(f_truncate(&file))<0) ||
       failure(f_write(&file,data,(UINT)size,&actual))<0 || failure(f_sync(&file))<0) {
        int e=errno;f_close(&file);errno=e;goto out;
    }
    if(actual!=size) {errno=ENOSPC;f_close(&file);goto out;}
    result=actual;if(failure(f_close(&file))<0) result=-1;
out:pthread_mutex_unlock(&lock);return result;
}
int tusb_fs_mkdir(const char *path) {pthread_mutex_lock(&lock);int r=check_path(path)<0?-1:!disk.writable?(errno=EROFS,-1):failure(f_mkdir(path));pthread_mutex_unlock(&lock);return r;}
int tusb_fs_unlink(const char *path) {pthread_mutex_lock(&lock);int r=check_path(path)<0?-1:!disk.writable?(errno=EROFS,-1):failure(f_unlink(path));pthread_mutex_unlock(&lock);return r;}
int tusb_fs_rename(const char *from,const char *to) {pthread_mutex_lock(&lock);int r=check_path(from)<0 || check_path(to)<0?-1:!disk.writable?(errno=EROFS,-1):failure(f_rename(from,to));pthread_mutex_unlock(&lock);return r;}
int tusb_fs_create_image(const char *path,uint64_t size,int exfat)
{
    pthread_mutex_lock(&lock);int result=-1;
    if(disk.active) {errno=EBUSY;goto out;}
    if(size<UINT64_C(32)*1024*1024 || size>INT64_MAX || size%512) {errno=EINVAL;goto out;}
    disk.image=open(path,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600);if(disk.image<0) goto out;
    if(ftruncate(disk.image,(off_t)size)<0) goto bad;
    disk.sector_size=512;disk.sectors=size/512;disk.active=disk.writable=1;
    MKFS_PARM options={.fmt=(BYTE)(exfat?FM_EXFAT:FM_FAT32),.n_fat=1};
    uint8_t work[4096];result=failure(f_mkfs("0:",&options,work,sizeof(work)));
bad:{int error=errno;release_disk();errno=error;}
out:pthread_mutex_unlock(&lock);return result;
}
