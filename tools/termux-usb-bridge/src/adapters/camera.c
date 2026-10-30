/* SPDX-License-Identifier: MIT */
#include "termux_usb_bridge.h"
#include "camera_frame.h"
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct camera {
    struct pw_main_loop *loop;
    struct pw_stream *stream;
    struct spa_source *timer,*deadline;
    pthread_mutex_t mutex;
    uint8_t *latest;
    uvc_frame_t *converted;
    struct tusb_camera_decoder decoder;
    unsigned width,height,fps,stride,sequence;
    int pattern,result,shutting_down;
};

static void frame_arrived(uvc_frame_t *frame,void *userdata)
{
    struct camera *camera=userdata;
    if(frame->width!=camera->width || frame->height!=camera->height)return;
    enum tusb_camera_format format;
    if(frame->frame_format==UVC_FRAME_FORMAT_YUYV)format=TUSB_CAMERA_YUYV;
    else if(frame->frame_format==UVC_FRAME_FORMAT_MJPEG)format=TUSB_CAMERA_MJPEG;
    else return;
    if(tusb_camera_decode(&camera->decoder,format,frame->data,frame->data_bytes,camera->width,camera->height,
                          frame->step,camera->converted->data,camera->converted->data_bytes)<0)return;
    pthread_mutex_lock(&camera->mutex);
    for(unsigned row=0;row<camera->height;row++)
        memcpy(camera->latest+(size_t)row*camera->stride,(uint8_t *)camera->converted->data+(size_t)row*camera->width*3,camera->width*3);
    pthread_mutex_unlock(&camera->mutex);
}
static void process(void *userdata)
{
    struct camera *camera=userdata;
    struct pw_buffer *buffer=pw_stream_dequeue_buffer(camera->stream);
    if(!buffer)return;
    struct spa_data *data=&buffer->buffer->datas[0];
    size_t size=(size_t)camera->stride*camera->height;
    if(!data->data || data->maxsize<size) {
        if(data->chunk)data->chunk->size=0;
        pw_stream_queue_buffer(camera->stream,buffer);return;
    }
    if(camera->pattern) {
        for(unsigned y=0;y<camera->height;y++)for(unsigned x=0;x<camera->width;x++) {
            uint8_t *p=(uint8_t *)data->data+(size_t)y*camera->stride+x*3;
            p[0]=(uint8_t)(x+camera->sequence);p[1]=(uint8_t)y;p[2]=(uint8_t)camera->sequence;
        }
    } else {
        pthread_mutex_lock(&camera->mutex);memcpy(data->data,camera->latest,size);pthread_mutex_unlock(&camera->mutex);
    }
    struct spa_meta_header *header=spa_buffer_find_meta_data(buffer->buffer,SPA_META_Header,sizeof(*header));
    if(header) {header->seq=camera->sequence;header->pts=(int64_t)pw_stream_get_nsec(camera->stream);header->flags=0;header->dts_offset=0;}
    camera->sequence++;
    data->chunk->offset=0;data->chunk->size=(uint32_t)size;data->chunk->stride=(int32_t)camera->stride;
    pw_stream_queue_buffer(camera->stream,buffer);
}
static void tick(void *userdata,uint64_t expirations) {(void)expirations;struct camera *c=userdata;pw_stream_trigger_process(c->stream);}
static void quit(void *userdata,int number) {(void)number;struct camera *c=userdata;c->shutting_down=1;pw_main_loop_quit(c->loop);}
static void deadline(void *userdata,uint64_t expirations) {(void)expirations;quit(userdata,0);}
static void state_changed(void *userdata,enum pw_stream_state old,enum pw_stream_state state,const char *error)
{
    (void)old;struct camera *c=userdata;
    if(state==PW_STREAM_STATE_ERROR) {fprintf(stderr,"PipeWire: %s\n",error?error:"stream error");c->result=1;pw_main_loop_quit(c->loop);}
    else if(state==PW_STREAM_STATE_STREAMING) {
        uint64_t period=UINT64_C(1000000000)/c->fps;
        struct timespec start={0,1},interval={(time_t)(period/1000000000),(long)(period%1000000000)};
        pw_loop_update_timer(pw_main_loop_get_loop(c->loop),c->timer,&start,&interval,false);
    } else if(!c->shutting_down && state==PW_STREAM_STATE_UNCONNECTED && old!=PW_STREAM_STATE_UNCONNECTED) {
        c->result=1;pw_main_loop_quit(c->loop);
    } else {
        pw_loop_update_timer(pw_main_loop_get_loop(c->loop),c->timer,NULL,NULL,false);
        if(state==PW_STREAM_STATE_PAUSED)fprintf(stderr,"camera node id=%u\n",pw_stream_get_node_id(c->stream));
    }
}
static void format_changed(void *userdata,uint32_t id,const struct spa_pod *parameter)
{
    struct camera *c=userdata;if(id!=SPA_PARAM_Format || !parameter)return;
    struct spa_video_info_raw format={0};
    if(spa_format_video_raw_parse(parameter,&format)<0 || format.format!=SPA_VIDEO_FORMAT_RGB ||
        format.size.width!=c->width || format.size.height!=c->height) {
        c->result=1;pw_main_loop_quit(c->loop);return;
    }
    uint8_t memory[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(memory,sizeof(memory));
    const struct spa_pod *parameters[2];
    parameters[0]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamBuffers,SPA_PARAM_Buffers,
        SPA_PARAM_BUFFERS_buffers,SPA_POD_CHOICE_RANGE_Int(4,2,16),SPA_PARAM_BUFFERS_blocks,SPA_POD_Int(1),
        SPA_PARAM_BUFFERS_size,SPA_POD_Int((int32_t)(c->stride*c->height)),SPA_PARAM_BUFFERS_stride,SPA_POD_Int((int32_t)c->stride),
        SPA_PARAM_BUFFERS_dataType,SPA_POD_CHOICE_FLAGS_Int((1<<SPA_DATA_MemPtr)|(1<<SPA_DATA_MemFd)));
    parameters[1]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamMeta,SPA_PARAM_Meta,
        SPA_PARAM_META_type,SPA_POD_Id(SPA_META_Header),SPA_PARAM_META_size,SPA_POD_Int(sizeof(struct spa_meta_header)));
    pw_stream_update_params(c->stream,parameters,2);
}
static const struct pw_stream_events events={PW_VERSION_STREAM_EVENTS,.state_changed=state_changed,.param_changed=format_changed,.process=process};

int main(int argc,char **argv)
{
    struct camera c={.mutex=PTHREAD_MUTEX_INITIALIZER,.width=640,.height=480,.fps=30};
    const char *device=NULL,*name="tusb.camera";unsigned seconds=0;
    for(int i=1;i<argc;i++) {
        if(!strcmp(argv[i],"--device") && i+1<argc)device=argv[++i];
        else if(!strcmp(argv[i],"--name") && i+1<argc)name=argv[++i];
        else if(!strcmp(argv[i],"--width") && i+1<argc)c.width=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--height") && i+1<argc)c.height=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--fps") && i+1<argc)c.fps=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--seconds") && i+1<argc)seconds=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--test-pattern"))c.pattern=1;
        else {fprintf(stderr,"Usage: tusb-camera (--device USB_PATH | --test-pattern) [--width 640 --height 480 --fps 30 --name tusb.camera --seconds N]\n");return 2;}
    }
    if((!device && !c.pattern) || (device && c.pattern) || !c.width || !c.height || c.width>4096 || c.height>2160 || !c.fps || c.fps>240)return 2;
    c.stride=(c.width*3+3)&~3u;c.latest=calloc(c.height,c.stride);if(!c.latest)return 1;
    int fd=-1;uvc_context_t *context=NULL;uvc_device_handle_t *camera=NULL;
    c.result=1;
    if(device) {
        fd=tusb_open(NULL,device);if(fd<0){perror("USB open");goto out;}
        libusb_set_option(NULL,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        uvc_error_t error=uvc_init(&context,NULL);
        if(!error)error=uvc_wrap(fd,context,&camera);
        uvc_stream_ctrl_t control;
        if(!error) {
            error=uvc_get_stream_ctrl_format_size(camera,&control,UVC_FRAME_FORMAT_YUYV,(int)c.width,(int)c.height,(int)c.fps);
            if(error)error=uvc_get_stream_ctrl_format_size(camera,&control,UVC_FRAME_FORMAT_MJPEG,(int)c.width,(int)c.height,(int)c.fps);
        }
        c.converted=uvc_allocate_frame((size_t)c.width*c.height*3);
        if(!c.converted){fprintf(stderr,"RGB buffer allocation failed\n");goto out;}
        if(!error)error=uvc_start_streaming(camera,&control,frame_arrived,&c,0);
        if(error){uvc_perror(error,"UVC stream");goto out;}
    }
    pw_init(NULL,NULL);
    c.loop=pw_main_loop_new(NULL);if(!c.loop)goto out;
    c.timer=pw_loop_add_timer(pw_main_loop_get_loop(c.loop),tick,&c);
    c.deadline=pw_loop_add_timer(pw_main_loop_get_loop(c.loop),deadline,&c);
    pw_loop_add_signal(pw_main_loop_get_loop(c.loop),SIGINT,quit,&c);
    pw_loop_add_signal(pw_main_loop_get_loop(c.loop),SIGTERM,quit,&c);
    c.stream=pw_stream_new_simple(pw_main_loop_get_loop(c.loop),"Termux USB camera",
        pw_properties_new(PW_KEY_MEDIA_TYPE,"Video",PW_KEY_MEDIA_CATEGORY,"Capture",PW_KEY_MEDIA_ROLE,"Camera",
            PW_KEY_MEDIA_CLASS,"Video/Source",PW_KEY_NODE_NAME,name,PW_KEY_NODE_DESCRIPTION,"Termux USB camera",
            PW_KEY_NODE_VIRTUAL,"true","node.supports-request","1",NULL),&events,&c);
    if(!c.stream)goto out;
    uint8_t memory[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(memory,sizeof(memory));
    const struct spa_pod *format=spa_format_video_raw_build(&b,SPA_PARAM_EnumFormat,
        &SPA_VIDEO_INFO_RAW_INIT(.format=SPA_VIDEO_FORMAT_RGB,.size=SPA_RECTANGLE(c.width,c.height),.framerate=SPA_FRACTION(c.fps,1)));
    if(pw_stream_connect(c.stream,PW_DIRECTION_OUTPUT,PW_ID_ANY,PW_STREAM_FLAG_DRIVER|PW_STREAM_FLAG_MAP_BUFFERS,&format,1)<0)goto out;
    if(seconds){struct timespec duration={(time_t)seconds,0};pw_loop_update_timer(pw_main_loop_get_loop(c.loop),c.deadline,&duration,NULL,false);}
    c.result=0;pw_main_loop_run(c.loop);
out:
    c.shutting_down=1;
    if(camera)uvc_stop_streaming(camera);
    if(c.stream)pw_stream_destroy(c.stream);
    if(c.loop)pw_main_loop_destroy(c.loop);
    if(camera)uvc_close(camera);
    if(context)uvc_exit(context);
    if(c.converted)uvc_free_frame(c.converted);
    tusb_camera_decoder_close(&c.decoder);
    if(fd>=0)close(fd);free(c.latest);pthread_mutex_destroy(&c.mutex);
    return c.result;
}
