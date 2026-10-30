/* SPDX-License-Identifier: MIT */
/* Run only in an Android/Bionic process with system Camera2/Media NDK libraries. */
#include "camera_wire.h"
#include <camera/NdkCameraManager.h>
#include <media/NdkImageReader.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;
static struct {
    pthread_mutex_t lock;
    pthread_cond_t changed;
    uint8_t *latest;
    unsigned width,height,fps;
    uint64_t sequence,stamp;
    int error;
} c={.lock=PTHREAD_MUTEX_INITIALIZER,.changed=PTHREAD_COND_INITIALIZER,.width=640,.height=480,.fps=30};
static uint64_t monotonic_ns(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
static void stop(int number){(void)number;stopping=1;}
static void fail(int error){pthread_mutex_lock(&c.lock);c.error=error?error:-1;pthread_cond_broadcast(&c.changed);pthread_mutex_unlock(&c.lock);}
static void disconnected(void *context,ACameraDevice *device){(void)context;(void)device;fprintf(stderr,"Camera2 disconnected\n");fail(-1);}
static void device_error(void *context,ACameraDevice *device,int error){(void)context;(void)device;fprintf(stderr,"Camera2 device error %d\n",error);fail(error);}
static void session_state(void *context,ACameraCaptureSession *session){(void)context;(void)session;}
static uint8_t clamp(int v){return v<0?0:v>255?255:(uint8_t)v;}
static void image_available(void *context,AImageReader *reader){
    (void)context;AImage *image=NULL;
    if(AImageReader_acquireLatestImage(reader,&image)!=AMEDIA_OK)return;
    int32_t w=0,h=0,format=0,count=0;int64_t stamp=0;
    uint8_t *planes[3]={0};int length[3]={0},row[3]={0},pixel[3]={0};
    int bad=AImage_getWidth(image,&w) || AImage_getHeight(image,&h) || AImage_getFormat(image,&format) ||
        AImage_getNumberOfPlanes(image,&count) || AImage_getTimestamp(image,&stamp);
    if(w!=(int)c.width || h!=(int)c.height || format!=AIMAGE_FORMAT_YUV_420_888 || count!=3 || stamp<=0)bad=1;
    for(int p=0;p<3 && !bad;p++){
        if(AImage_getPlaneData(image,p,&planes[p],&length[p]) || AImage_getPlaneRowStride(image,p,&row[p]) ||
           AImage_getPlanePixelStride(image,p,&pixel[p]))bad=1;
        int pw=p?(w+1)/2:w,ph=p?(h+1)/2:h;
        if(!planes[p] || row[p]<=0 || pixel[p]<=0 || length[p]<=0 ||
           (int64_t)(ph-1)*row[p]+(int64_t)(pw-1)*pixel[p]>=length[p])bad=1;
    }
    if(bad){AImage_delete(image);fprintf(stderr,"Invalid Camera2 YUV image\n");fail(-1);return;}
    pthread_mutex_lock(&c.lock);
    if(!stopping && !c.error){
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int l=planes[0][y*row[0]+x*pixel[0]]-16;
            int u=planes[1][(y/2)*row[1]+(x/2)*pixel[1]]-128;
            int v=planes[2][(y/2)*row[2]+(x/2)*pixel[2]]-128;
            uint8_t *out=c.latest+((size_t)y*w+x)*3;
            out[0]=clamp((298*l+409*v+128)>>8);out[1]=clamp((298*l-100*u-208*v+128)>>8);out[2]=clamp((298*l+516*u+128)>>8);
        }
        if(c.sequence && (uint64_t)stamp<=c.stamp)c.error=-1;
        else {c.stamp=(uint64_t)stamp;c.sequence++;}
        pthread_cond_broadcast(&c.changed);
    }
    pthread_mutex_unlock(&c.lock);AImage_delete(image);
}
static int number(const char *s,unsigned *out,unsigned max){char *end=NULL;errno=0;unsigned long v=strtoul(s,&end,10);if(errno || !*s || *end || !v || v>max)return -1;*out=(unsigned)v;return 0;}
static void json_string(const char *s){putchar('"');for(const unsigned char *p=(const unsigned char *)s;*p;p++){
    if(*p=='"' || *p=='\\'){putchar('\\');putchar(*p);}else if(*p<32)printf("\\u%04x",*p);else putchar(*p);
}putchar('"');}
static int list_cameras(ACameraManager *manager){
    ACameraIdList *ids=NULL;int status=ACameraManager_getCameraIdList(manager,&ids);
    if(status){fprintf(stderr,"Camera enumeration failed: %d\n",status);return 1;}
    putchar('[');
    for(int i=0;i<ids->numCameras;i++){
        ACameraMetadata *metadata=NULL;ACameraMetadata_const_entry entry;
        status=ACameraManager_getCameraCharacteristics(manager,ids->cameraIds[i],&metadata);
        if(status){ACameraManager_deleteCameraIdList(ids);return 1;}
        if(i)putchar(',');printf("{\"id\":");json_string(ids->cameraIds[i]);
        int facing=-1,orientation=-1;
        if(!ACameraMetadata_getConstEntry(metadata,ACAMERA_LENS_FACING,&entry) && entry.count)facing=entry.data.u8[0];
        if(!ACameraMetadata_getConstEntry(metadata,ACAMERA_SENSOR_ORIENTATION,&entry) && entry.count)orientation=entry.data.i32[0];
        printf(",\"facing\":\"%s\",\"orientation\":%d,\"yuv_sizes\":[",facing==0?"front":facing==1?"back":"external",orientation);
        int comma=0;
        if(!ACameraMetadata_getConstEntry(metadata,ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,&entry))
            for(uint32_t j=0;j+3<entry.count;j+=4)if(entry.data.i32[j]==AIMAGE_FORMAT_YUV_420_888 && entry.data.i32[j+3]==0){
                printf("%s[%d,%d]",comma?",":"",entry.data.i32[j+1],entry.data.i32[j+2]);comma=1;
            }
        printf("],\"fps_ranges\":[");comma=0;
        if(!ACameraMetadata_getConstEntry(metadata,ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,&entry))
            for(uint32_t j=0;j+1<entry.count;j+=2){printf("%s[%d,%d]",comma?",":"",entry.data.i32[j],entry.data.i32[j+1]);comma=1;}
        printf("]}");ACameraMetadata_free(metadata);
    }
    puts("]");ACameraManager_deleteCameraIdList(ids);return 0;
}
static int write_all(const uint8_t *p,size_t length,uint64_t deadline){
    while(length){
        ssize_t n=write(STDOUT_FILENO,p,length);
        if(n>0){p+=n;length-=(size_t)n;continue;}
        if(n<0 && errno==EINTR)continue;
        if(n<0 && (errno==EAGAIN || errno==EWOULDBLOCK)){
            if(monotonic_ns()>=deadline){errno=ETIMEDOUT;return -1;}
            struct pollfd fd={.fd=STDOUT_FILENO,.events=POLLOUT};poll(&fd,1,100);continue;
        }
        return -1;
    }return 0;
}
#define CHECK(call) do{int status=(call);if(status){fprintf(stderr,"%s failed: %d\n",#call,status);result=1;goto out;}}while(0)
int main(int argc,char **argv){
    const char *id=NULL;unsigned seconds=0;int listing=0;
    for(int i=1;i<argc;i++){
        if(!strcmp(argv[i],"--list"))listing=1;
        else if(!strcmp(argv[i],"--camera") && i+1<argc)id=argv[++i];
        else if(!strcmp(argv[i],"--width") && i+1<argc){if(number(argv[++i],&c.width,4096))return 2;}
        else if(!strcmp(argv[i],"--height") && i+1<argc){if(number(argv[++i],&c.height,2160))return 2;}
        else if(!strcmp(argv[i],"--fps") && i+1<argc){if(number(argv[++i],&c.fps,240))return 2;}
        else if(!strcmp(argv[i],"--seconds") && i+1<argc){if(number(argv[++i],&seconds,86400))return 2;}
        else return 2;
    }
    if((!listing && !id) || (listing && id) || c.width%2 || c.height%2)return 2;
    struct sigaction action={.sa_handler=stop};sigemptyset(&action.sa_mask);
    sigaction(SIGTERM,&action,NULL);sigaction(SIGINT,&action,NULL);signal(SIGPIPE,SIG_IGN);
    int result=0;ACameraManager *manager=NULL;ACameraDevice *device=NULL;AImageReader *reader=NULL;
    ACaptureSessionOutputContainer *container=NULL;ACaptureSessionOutput *output=NULL;
    ACameraOutputTarget *target=NULL;ACaptureRequest *request=NULL;ACameraCaptureSession *session=NULL;
    ACameraMetadata *metadata=NULL;uint8_t *frame=NULL;uint64_t sent=0;
    if(!listing){
        /* Exported Android platform API, not a public/stable app NDK ABI. */
        void *binder=dlopen("libbinder_ndk.so",RTLD_NOW|RTLD_LOCAL);
        void (*start_pool)(void)=binder?(void (*)(void))dlsym(binder,"ABinderProcess_startThreadPool"):NULL;
        if(!start_pool){fprintf(stderr,"This Android build lacks the required Binder thread-pool platform interface\n");return 1;}
        start_pool();
    }
    manager=ACameraManager_create();if(!manager)return 1;
    if(listing){result=list_cameras(manager);goto out;}
    size_t size=(size_t)c.width*c.height*3;c.latest=calloc(1,size);frame=malloc(size);
    if(!c.latest || !frame){result=1;goto out;}
    CHECK(ACameraManager_getCameraCharacteristics(manager,id,&metadata));
    ACameraMetadata_const_entry entry;int supported=0;int32_t range[2]={0};
    CHECK(ACameraMetadata_getConstEntry(metadata,ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,&entry));
    for(uint32_t i=0;i+3<entry.count;i+=4)if(entry.data.i32[i]==AIMAGE_FORMAT_YUV_420_888 &&
        entry.data.i32[i+1]==(int)c.width && entry.data.i32[i+2]==(int)c.height && entry.data.i32[i+3]==0)supported=1;
    if(!supported){fprintf(stderr,"Camera does not support YUV %ux%u; use tusb-camera2 list\n",c.width,c.height);result=1;goto out;}
    CHECK(ACameraMetadata_getConstEntry(metadata,ACAMERA_CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,&entry));
    for(uint32_t i=0;i+1<entry.count;i+=2)if(entry.data.i32[i]<=(int)c.fps && entry.data.i32[i+1]==(int)c.fps && entry.data.i32[i]>range[0]){
        range[0]=entry.data.i32[i];range[1]=entry.data.i32[i+1];
    }
    if(!range[1]){fprintf(stderr,"Camera lacks an AE FPS range ending at %u; use tusb-camera2 list\n",c.fps);result=1;goto out;}
    ACameraDevice_StateCallbacks dc={.onDisconnected=disconnected,.onError=device_error};
    int opened=ACameraManager_openCamera(manager,id,&dc,&device);
    if(opened){fprintf(stderr,"Camera2 open failed: %d. CAMERA permission, shared UID, AppOps and foreground eligibility are required.\n",opened);result=1;goto out;}
    CHECK(AImageReader_new((int)c.width,(int)c.height,AIMAGE_FORMAT_YUV_420_888,4,&reader));
    AImageReader_ImageListener listener={.onImageAvailable=image_available};CHECK(AImageReader_setImageListener(reader,&listener));
    ANativeWindow *window=NULL;CHECK(AImageReader_getWindow(reader,&window));
    CHECK(ACaptureSessionOutputContainer_create(&container));CHECK(ACaptureSessionOutput_create(window,&output));
    CHECK(ACaptureSessionOutputContainer_add(container,output));CHECK(ACameraOutputTarget_create(window,&target));
    CHECK(ACameraDevice_createCaptureRequest(device,TEMPLATE_PREVIEW,&request));CHECK(ACaptureRequest_addTarget(request,target));
    CHECK(ACaptureRequest_setEntry_i32(request,ACAMERA_CONTROL_AE_TARGET_FPS_RANGE,2,range));
    ACameraCaptureSession_stateCallbacks sc={.onClosed=session_state,.onReady=session_state,.onActive=session_state};
    CHECK(ACameraDevice_createCaptureSession(device,container,&sc,&session));
    int sequence=0;CHECK(ACameraCaptureSession_setRepeatingRequest(session,NULL,1,&request,&sequence));
    int flags=fcntl(STDOUT_FILENO,F_GETFL);if(flags<0 || fcntl(STDOUT_FILENO,F_SETFL,flags|O_NONBLOCK)<0){result=1;goto out;}
    uint64_t start=monotonic_ns(),last=start;int error=0;
    fprintf(stderr,"Camera2 %s started: %ux%u AE=%d..%d, uid=%u\n",id,c.width,c.height,range[0],range[1],(unsigned)getuid());
    while(!stopping){
        uint64_t now=monotonic_ns();if(seconds && now-start>=(uint64_t)seconds*1000000000)break;
        pthread_mutex_lock(&c.lock);
        if(c.error){error=c.error;pthread_mutex_unlock(&c.lock);break;}
        if(c.sequence==sent){
            struct timespec tick;clock_gettime(CLOCK_REALTIME,&tick);tick.tv_nsec+=250000000;
            if(tick.tv_nsec>=1000000000){tick.tv_sec++;tick.tv_nsec-=1000000000;}
            pthread_cond_timedwait(&c.changed,&c.lock,&tick);
        }
        uint64_t stamp=c.stamp,seq=c.sequence;
        if(seq!=sent)memcpy(frame,c.latest,size);
        error=c.error;pthread_mutex_unlock(&c.lock);if(error)break;
        if(seq==sent){if(now-last>UINT64_C(15000000000)){error=-1;fprintf(stderr,"No Camera2 frames for 15 seconds\n");break;}continue;}
        uint8_t header[TUSB_FRAME_HEADER];tusb_frame_header(header,c.width,c.height,stamp,seq);
        uint64_t deadline=monotonic_ns()+UINT64_C(5000000000);
        if(write_all(header,sizeof(header),deadline) || write_all(frame,size,deadline)){perror("Camera2 transport");error=-1;break;}
        sent=seq;last=monotonic_ns();
    }
    if(error || !sent)result=1;
    pthread_mutex_lock(&c.lock);uint64_t captured=c.sequence;pthread_mutex_unlock(&c.lock);
    fprintf(stderr,"Camera2 stopped: captured=%llu last_sent_sequence=%llu result=%d\n",(unsigned long long)captured,(unsigned long long)sent,result);
out:
    stopping=1;
    if(session){ACameraCaptureSession_stopRepeating(session);ACameraCaptureSession_close(session);}
    if(device)ACameraDevice_close(device);
    if(request)ACaptureRequest_free(request);if(target)ACameraOutputTarget_free(target);
    if(container)ACaptureSessionOutputContainer_free(container);if(output)ACaptureSessionOutput_free(output);
    if(reader)AImageReader_delete(reader);if(metadata)ACameraMetadata_free(metadata);if(manager)ACameraManager_delete(manager);
    free(frame);free(c.latest);return result;
}
