/* SPDX-License-Identifier: MIT */
/* Standalone Camera2 probe; optional RGB stdout feeds an external PipeWire publisher. */
#include <camera/NdkCameraManager.h>
#include <media/NdkImageReader.h>
#include <dlfcn.h>
#include <signal.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static FILE *diagnostics;
static volatile sig_atomic_t stop_requested;
static void stop(int signal_number) {(void)signal_number;stop_requested=1;}

static struct {
    pthread_mutex_t lock;
    pthread_cond_t condition;
    unsigned frames, target, changed;
    int error, closed, stream;
    int64_t first_time, last_time;
    uint64_t first_hash, last_hash;
    const char *prefix;
} state = {.lock=PTHREAD_MUTEX_INITIALIZER, .condition=PTHREAD_COND_INITIALIZER, .target=30};

static void failed(int error) {
    pthread_mutex_lock(&state.lock);
    state.error=error ? error : -1;
    pthread_cond_broadcast(&state.condition);
    pthread_mutex_unlock(&state.lock);
}
static void disconnected(void *context, ACameraDevice *device) {
    (void)context;(void)device;fprintf(stderr,"DEVICE_DISCONNECTED\n");failed(-2);
}
static void device_error(void *context, ACameraDevice *device, int error) {
    (void)context;(void)device;fprintf(stderr,"DEVICE_ERROR %d\n",error);failed(error);
}
static void closed(void *context, ACameraCaptureSession *session) {
    (void)context;(void)session;
    pthread_mutex_lock(&state.lock);state.closed=1;
    pthread_cond_broadcast(&state.condition);pthread_mutex_unlock(&state.lock);
}
static void ready(void *context, ACameraCaptureSession *session) {(void)context;(void)session;}
static uint8_t clamp(int v) {return v<0?0:v>255?255:(uint8_t)v;}
static int write_rgb(FILE *f, int width, int height, uint8_t **planes, int *row, int *pixel) {
    uint8_t *line=malloc((size_t)width*3);if(!line)return -1;
    int result=0;
    for(int y=0;y<height;y++) {
        for(int x=0;x<width;x++) {
            int l=planes[0][y*row[0]+x*pixel[0]]-16;
            int u=planes[1][(y/2)*row[1]+(x/2)*pixel[1]]-128;
            int v=planes[2][(y/2)*row[2]+(x/2)*pixel[2]]-128;
            line[x*3]=clamp((298*l+409*v+128)>>8);
            line[x*3+1]=clamp((298*l-100*u-208*v+128)>>8);
            line[x*3+2]=clamp((298*l+516*u+128)>>8);
        }
        if(fwrite(line,3,(size_t)width,f)!=(size_t)width){result=-1;break;}
    }
    free(line);return result;
}
static int save(const char *suffix, int width, int height, uint8_t **planes, int *row, int *pixel) {
    char path[1024];
    if(snprintf(path,sizeof(path),"%s-%s.ppm",state.prefix,suffix)>=(int)sizeof(path))return -1;
    FILE *f=fopen(path,"wb");if(!f)return -1;
    fprintf(f,"P6\n%d %d\n255\n",width,height);
    int result=write_rgb(f,width,height,planes,row,pixel);
    if(fclose(f))result=-1;return result;
}
static void image_available(void *context, AImageReader *reader) {
    (void)context;AImage *image=NULL;
    if(AImageReader_acquireNextImage(reader,&image)!=AMEDIA_OK)return;
    int32_t width=0,height=0,format=0,count=0;int64_t stamp=0;
    uint8_t *planes[3]={0};int length[3]={0},row[3]={0},pixel[3]={0};
    int bad=AImage_getWidth(image,&width) || AImage_getHeight(image,&height) ||
        AImage_getFormat(image,&format) || AImage_getNumberOfPlanes(image,&count) ||
        AImage_getTimestamp(image,&stamp);
    if(format!=AIMAGE_FORMAT_YUV_420_888 || count!=3 || width!=640 || height!=480)bad=1;
    for(int p=0;p<3 && !bad;p++) {
        if(AImage_getPlaneData(image,p,&planes[p],&length[p]) ||
           AImage_getPlaneRowStride(image,p,&row[p]) || AImage_getPlanePixelStride(image,p,&pixel[p]))bad=1;
        int w=p?(width+1)/2:width,h=p?(height+1)/2:height;
        if(!planes[p] || row[p]<=0 || pixel[p]<=0 || length[p]<=0 ||
           (int64_t)(h-1)*row[p]+(int64_t)(w-1)*pixel[p]>=length[p])bad=1;
    }
    if(bad){fprintf(stderr,"INVALID_IMAGE\n");AImage_delete(image);failed(-3);return;}
    uint64_t hash=UINT64_C(14695981039346656037);
    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
        hash^=planes[0][y*row[0]+x*pixel[0]];hash*=UINT64_C(1099511628211);
    }
    pthread_mutex_lock(&state.lock);
    if(state.frames<state.target && !state.error && !stop_requested) {
        if(state.frames && stamp<=state.last_time)state.error=-4;
        if(!state.frames) {
            state.first_time=stamp;state.first_hash=hash;
            fprintf(diagnostics,"IMAGE %dx%d format=%d planes=%d Y(row=%d,pixel=%d) U(row=%d,pixel=%d) V(row=%d,pixel=%d)\n",
                   width,height,format,count,row[0],pixel[0],row[1],pixel[1],row[2],pixel[2]);
            if(!state.stream && save("first",width,height,planes,row,pixel))state.error=-5;
        }
        if(state.frames && hash!=state.last_hash)state.changed++;
        state.last_time=stamp;state.last_hash=hash;state.frames++;
        if(state.stream) {
            if(write_rgb(stdout,width,height,planes,row,pixel) || fflush(stdout))state.error=-5;
            if(state.frames%30==0)fprintf(diagnostics,"STREAM frames=%u timestamp=%lld\n",state.frames,(long long)stamp);
        } else if(state.frames==state.target && save("last",width,height,planes,row,pixel))state.error=-5;
        pthread_cond_broadcast(&state.condition);
    }
    pthread_cond_broadcast(&state.condition);pthread_mutex_unlock(&state.lock);AImage_delete(image);
}
#define CHECK(call) do { int status=(call); fprintf(diagnostics,"%s => %d\n",#call,status); if(status){result=1;goto cleanup;} } while(0)
int main(int argc, char **argv) {
    state.stream=argc==4 && !strcmp(argv[1],"--stream");
    diagnostics=state.stream?stderr:stdout;setvbuf(diagnostics,NULL,_IOLBF,0);
    if((argc!=2 && argc!=3) && !state.stream){fprintf(stderr,"Usage: %s --list | CAMERA_ID OUTPUT_PREFIX | --stream CAMERA_ID FRAME_COUNT\n",argv[0]);return 2;}
    if(state.stream) {
        char *end=NULL;unsigned long frames=strtoul(argv[3],&end,10);
        if(!frames || frames>18000 || *end)return 2;
        state.target=(unsigned)frames;
        signal(SIGPIPE,SIG_IGN);signal(SIGTERM,stop);signal(SIGINT,stop);
    }
    fprintf(diagnostics,"NDK_CAMERA_PROBE uid=%u pid=%u\n",(unsigned)getuid(),(unsigned)getpid());
    /* Standalone executables lack the Java app runtime's Binder pool.
     * This exported platform helper is NOT part of the public app NDK API.
     * Resolve it explicitly for this Android diagnostic, not as a portable ABI. */
    if(!getenv("TUSB_SKIP_BINDER_POOL")) {
        void *binder=dlopen("libbinder_ndk.so",RTLD_NOW|RTLD_LOCAL);
        void (*start_pool)(void)=binder?(void (*)(void))dlsym(binder,"ABinderProcess_startThreadPool"):NULL;
        if(!start_pool){fprintf(stderr,"No platform Binder pool helper available\n");return 1;}
        start_pool();fprintf(diagnostics,"BINDER_POOL=ABinderProcess_startThreadPool (platform API)\n");
    }
    FILE *se=fopen("/proc/self/attr/current","r");char context[256];
    if(se){if(fgets(context,sizeof(context),se))fprintf(diagnostics,"SELINUX=%s\n",context);fclose(se);}
    int result=0;ACameraManager *manager=ACameraManager_create();
    ACameraIdList *ids=NULL;ACameraDevice *device=NULL;AImageReader *reader=NULL;
    ACaptureSessionOutputContainer *container=NULL;ACaptureSessionOutput *output=NULL;
    ACameraOutputTarget *target=NULL;ACaptureRequest *request=NULL;ACameraCaptureSession *session=NULL;
    if(!manager)return 1;
    CHECK(ACameraManager_getCameraIdList(manager,&ids));
    for(int i=0;i<ids->numCameras;i++) {
        ACameraMetadata *metadata=NULL;ACameraMetadata_const_entry facing;
        CHECK(ACameraManager_getCameraCharacteristics(manager,ids->cameraIds[i],&metadata));
        int got=ACameraMetadata_getConstEntry(metadata,ACAMERA_LENS_FACING,&facing);
        fprintf(diagnostics,"CAMERA id=%s facing=%d\n",ids->cameraIds[i],got?-1:facing.data.u8[0]);
        ACameraMetadata_free(metadata);
    }
    if(!strcmp(argv[1],"--list"))goto cleanup;
    if(!state.stream && argc!=3){result=2;goto cleanup;}state.prefix=state.stream?NULL:argv[2];
    if(!state.stream) {
    char map_path[1024];snprintf(map_path,sizeof(map_path),"%s-maps.log",state.prefix);
    FILE *maps=fopen("/proc/self/maps","r"),*copy=fopen(map_path,"w");
    if(maps && copy){char line[2048];while(fgets(line,sizeof(line),maps))fputs(line,copy);}
    if(maps)fclose(maps);if(copy)fclose(copy);
    }
    ACameraDevice_StateCallbacks device_cb={.onDisconnected=disconnected,.onError=device_error};
    CHECK(ACameraManager_openCamera(manager,argv[state.stream?2:1],&device_cb,&device));
    CHECK(AImageReader_new(640,480,AIMAGE_FORMAT_YUV_420_888,4,&reader));
    AImageReader_ImageListener image_cb={.onImageAvailable=image_available};
    CHECK(AImageReader_setImageListener(reader,&image_cb));
    ANativeWindow *window=NULL;CHECK(AImageReader_getWindow(reader,&window));
    CHECK(ACaptureSessionOutputContainer_create(&container));
    CHECK(ACaptureSessionOutput_create(window,&output));
    CHECK(ACaptureSessionOutputContainer_add(container,output));
    CHECK(ACameraOutputTarget_create(window,&target));
    CHECK(ACameraDevice_createCaptureRequest(device,TEMPLATE_PREVIEW,&request));
    CHECK(ACaptureRequest_addTarget(request,target));
    ACameraCaptureSession_stateCallbacks session_cb={.onClosed=closed,.onReady=ready,.onActive=ready};
    CHECK(ACameraDevice_createCaptureSession(device,container,&session_cb,&session));
    int sequence=0;CHECK(ACameraCaptureSession_setRepeatingRequest(session,NULL,1,&request,&sequence));
    struct timespec end;clock_gettime(CLOCK_REALTIME,&end);end.tv_sec+=state.stream?600:15;
    pthread_mutex_lock(&state.lock);
    while(state.frames<state.target && !state.error && !stop_requested) {
        struct timespec tick;clock_gettime(CLOCK_REALTIME,&tick);
        if(tick.tv_sec>=end.tv_sec)break;
        tick.tv_sec++;pthread_cond_timedwait(&state.condition,&state.lock,&tick);
    }
    fprintf(diagnostics,"RESULT frames=%u changed=%u timestamps=%lld..%lld span_ms=%.3f hashes=%016llx..%016llx error=%d\n",
           state.frames,state.changed,(long long)state.first_time,(long long)state.last_time,
           (double)(state.last_time-state.first_time)/1e6,(unsigned long long)state.first_hash,(unsigned long long)state.last_hash,state.error);
    if((!state.stream && state.frames<state.target) || !state.frames || state.error)result=1;
    pthread_mutex_unlock(&state.lock);
cleanup:
    if(session){ACameraCaptureSession_stopRepeating(session);ACameraCaptureSession_close(session);}
    if(device)ACameraDevice_close(device);
    if(request)ACaptureRequest_free(request);
    if(target)ACameraOutputTarget_free(target);
    if(container)ACaptureSessionOutputContainer_free(container);
    if(output)ACaptureSessionOutput_free(output);
    if(reader)AImageReader_delete(reader);
    if(ids)ACameraManager_deleteCameraIdList(ids);
    ACameraManager_delete(manager);
    fprintf(diagnostics,"NDK_CAMERA_PROBE_%s\n",result?"FAIL":"PASS");return result;
}
