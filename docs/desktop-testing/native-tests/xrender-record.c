/* Read-only graphics protocol recorder. Does not synthesize input or draw. */
#include <X11/Xlib.h>
#include <X11/extensions/record.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <poll.h>
#include <time.h>

static FILE *output;
static unsigned long records;
static void capture(XPointer closure,XRecordInterceptData *data) {
    (void)closure;
    uint32_t header[]={0x58524331,(uint32_t)data->id_base,(uint32_t)data->server_time,(uint32_t)data->client_seq,(uint32_t)data->category,(uint32_t)data->client_swapped,(uint32_t)data->data_len*4};
    if(fwrite(header,sizeof(header),1,output)!=1||fwrite(data->data,4,data->data_len,output)!=data->data_len) {perror("record write");exit(4);}
    records++;
    XRecordFreeData(data);
}
static long long millis(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
int main(int argc,char **argv) {
    if(argc!=4||strcmp(argv[1],"--seconds")||atoi(argv[2])<1||atoi(argv[2])>600) {fprintf(stderr,"usage: %s --seconds 1..600 OUTPUT (uses explicit DISPLAY)\n",argv[0]);return 2;}
    const char *name=getenv("DISPLAY");if(!name||!*name){fprintf(stderr,"DISPLAY required\n");return 2;}
    Display *control=XOpenDisplay(name),*data=XOpenDisplay(name);
    if(!control||!data){fprintf(stderr,"cannot open DISPLAY %s\n",name);return 3;}
    int major,minor,render,shm,event,error;
    if(!XRecordQueryVersion(control,&major,&minor)||!XQueryExtension(control,"RENDER",&render,&event,&error)){fprintf(stderr,"RECORD/RENDER unavailable\n");return 3;}
    int has_shm=XQueryExtension(control,"MIT-SHM",&shm,&event,&error);
    output=fopen(argv[3],"wb");if(!output){perror(argv[3]);return 4;}
    XRecordRange *ranges[3];for(int i=0;i<3;i++)ranges[i]=XRecordAllocRange();
    ranges[0]->core_requests.first=53;ranges[0]->core_requests.last=77;
    ranges[1]->ext_requests.ext_major.first=ranges[1]->ext_requests.ext_major.last=render;
    ranges[1]->ext_requests.ext_minor.first=0;ranges[1]->ext_requests.ext_minor.last=255;
    if(has_shm) {ranges[2]->ext_requests.ext_major.first=ranges[2]->ext_requests.ext_major.last=shm;ranges[2]->ext_requests.ext_minor.first=0;ranges[2]->ext_requests.ext_minor.last=255;}
    XRecordClientSpec clients=XRecordAllClients;
    XRecordContext context=XRecordCreateContext(control,XRecordFromClientTime|XRecordFromClientSequence,&clients,1,ranges,has_shm?3:2);
    XSync(control,False);
    if(!context||!XRecordEnableContextAsync(data,context,capture,NULL)){fprintf(stderr,"cannot enable record\n");return 3;}
    printf("READY DISPLAY=%s RECORD=%d.%d RENDER=%d MIT-SHM=%d seconds=%s output=%s\n",name,major,minor,render,has_shm?shm:0,argv[2],argv[3]);fflush(stdout);
    struct pollfd fd={ConnectionNumber(data),POLLIN,0};
    long long end=millis()+atoi(argv[2])*1000LL;
    while(millis()<end)if(poll(&fd,1,200)>0&&(fd.revents&POLLIN))XRecordProcessReplies(data);
    XRecordDisableContext(control,context);XSync(control,False);
    for(int i=0;i<5;i++)if(poll(&fd,1,100)>0&&(fd.revents&POLLIN))XRecordProcessReplies(data);
    XRecordFreeContext(control,context);XSync(control,False);XCloseDisplay(data);XCloseDisplay(control);
    fclose(output);printf("DONE records=%lu\n",records);
    return 0;
}
