/* SPDX-License-Identifier: MIT */
/* GUI-validation RGB pipe -> PipeWire source. Fixed RGB 640x480 at nominal 30fps.
 * Isolates Android Camera2 system libraries from the Termux desktop libraries. */
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include <pthread.h>
#include <stdatomic.h>
#include <poll.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define WIDTH 640
#define HEIGHT 480
#define STRIDE (WIDTH*3)
#define FRAME_BYTES ((size_t)STRIDE*HEIGHT)
struct source {
    struct pw_main_loop *loop;
    struct pw_stream *stream;
    struct spa_source *timer,*input_event;
    pthread_mutex_t mutex;
    pthread_t reader;
    atomic_int stopping,eof,error;
    uint8_t *latest;
    uint64_t received;
    unsigned sequence;
    int shutting_down,result;
};
static void *read_frames(void *data) {
    struct source *s=data;uint8_t *frame=malloc(FRAME_BYTES);size_t used=0;
    if(!frame){atomic_store(&s->error,1);goto done;}
    while(!atomic_load(&s->stopping)) {
        struct pollfd fd={.fd=STDIN_FILENO,.events=POLLIN};
        int status=poll(&fd,1,100);
        if(status<0){if(errno==EINTR)continue;atomic_store(&s->error,1);break;}
        if(!status)continue;
        ssize_t count=read(STDIN_FILENO,frame+used,FRAME_BYTES-used);
        if(count<0){if(errno==EINTR)continue;atomic_store(&s->error,1);break;}
        if(!count){if(used)atomic_store(&s->error,1);break;}
        used+=(size_t)count;
        if(used==FRAME_BYTES) {
            pthread_mutex_lock(&s->mutex);
            memcpy(s->latest,frame,FRAME_BYTES);s->received++;
            if(s->received%30==0)fprintf(stderr,"RGB_INPUT frames=%llu\n",(unsigned long long)s->received);
            pthread_mutex_unlock(&s->mutex);used=0;
        }
    }
    free(frame);
done:
    atomic_store(&s->eof,1);pw_loop_signal_event(pw_main_loop_get_loop(s->loop),s->input_event);return NULL;
}
static void input_done(void *data,uint64_t count) {
    (void)count;struct source *s=data;
    if(atomic_load(&s->eof)) {
        s->result=atomic_load(&s->error);s->shutting_down=1;pw_main_loop_quit(s->loop);
    }
}
static void process(void *data) {
    struct source *s=data;struct pw_buffer *b=pw_stream_dequeue_buffer(s->stream);if(!b)return;
    struct spa_data *d=&b->buffer->datas[0];
    if(!d->data || !d->chunk || d->maxsize<FRAME_BYTES) {
        if(d->chunk)d->chunk->size=0;
        pw_stream_queue_buffer(s->stream,b);return;
    }
    pthread_mutex_lock(&s->mutex);
    memcpy(d->data,s->latest,FRAME_BYTES);
    uint64_t frames=s->received;
    pthread_mutex_unlock(&s->mutex);
    struct spa_meta_header *h=spa_buffer_find_meta_data(b->buffer,SPA_META_Header,sizeof(*h));
    if(h){h->seq=s->sequence++;h->pts=(int64_t)pw_stream_get_nsec(s->stream);h->flags=0;h->dts_offset=0;}
    d->chunk->offset=0;d->chunk->stride=STRIDE;d->chunk->size=frames?FRAME_BYTES:0;
    pw_stream_queue_buffer(s->stream,b);
}
static void tick(void *data,uint64_t count){(void)count;struct source *s=data;pw_stream_trigger_process(s->stream);}
static void quit(void *data,int number){(void)number;struct source *s=data;s->shutting_down=1;pw_main_loop_quit(s->loop);}
static void state_changed(void *data,enum pw_stream_state old,enum pw_stream_state state,const char *error) {
    struct source *s=data;
    if(state==PW_STREAM_STATE_ERROR || (!s->shutting_down && state==PW_STREAM_STATE_UNCONNECTED && old!=PW_STREAM_STATE_UNCONNECTED)) {
        fprintf(stderr,"PW_ERROR %s\n",error?error:"disconnected");s->result=1;pw_main_loop_quit(s->loop);
    } else if(state==PW_STREAM_STATE_STREAMING) {
        struct timespec start={0,1},period={0,33333333};
        pw_loop_update_timer(pw_main_loop_get_loop(s->loop),s->timer,&start,&period,false);
    } else pw_loop_update_timer(pw_main_loop_get_loop(s->loop),s->timer,NULL,NULL,false);
}
static void format_changed(void *data,uint32_t id,const struct spa_pod *param) {
    if(id!=SPA_PARAM_Format || !param)return;
    struct source *s=data;struct spa_video_info_raw format={0};
    if(spa_format_video_raw_parse(param,&format)<0 || format.format!=SPA_VIDEO_FORMAT_RGB ||
       format.size.width!=WIDTH || format.size.height!=HEIGHT){s->result=1;pw_main_loop_quit(s->loop);return;}
    uint8_t buffer[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(buffer,sizeof(buffer));
    const struct spa_pod *params[2];
    params[0]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamBuffers,SPA_PARAM_Buffers,
        SPA_PARAM_BUFFERS_buffers,SPA_POD_CHOICE_RANGE_Int(4,2,16),SPA_PARAM_BUFFERS_blocks,SPA_POD_Int(1),
        SPA_PARAM_BUFFERS_size,SPA_POD_Int(FRAME_BYTES),SPA_PARAM_BUFFERS_stride,SPA_POD_Int(STRIDE),
        SPA_PARAM_BUFFERS_dataType,SPA_POD_CHOICE_FLAGS_Int((1<<SPA_DATA_MemPtr)|(1<<SPA_DATA_MemFd)));
    params[1]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamMeta,SPA_PARAM_Meta,
        SPA_PARAM_META_type,SPA_POD_Id(SPA_META_Header),SPA_PARAM_META_size,SPA_POD_Int(sizeof(struct spa_meta_header)));
    pw_stream_update_params(s->stream,params,2);
}
static const struct pw_stream_events events={PW_VERSION_STREAM_EVENTS,.process=process,.state_changed=state_changed,.param_changed=format_changed};
int main(int argc,char **argv) {
    if(argc!=3){fprintf(stderr,"Usage: %s NODE_NAME DESCRIPTION < RGB640x480\n",argv[0]);return 2;}
    struct source s={.mutex=PTHREAD_MUTEX_INITIALIZER};s.latest=calloc(1,FRAME_BYTES);if(!s.latest)return 1;
    pw_init(NULL,NULL);s.loop=pw_main_loop_new(NULL);if(!s.loop){free(s.latest);return 1;}
    s.timer=pw_loop_add_timer(pw_main_loop_get_loop(s.loop),tick,&s);
    s.input_event=pw_loop_add_event(pw_main_loop_get_loop(s.loop),input_done,&s);
    pw_loop_add_signal(pw_main_loop_get_loop(s.loop),SIGTERM,quit,&s);
    pw_loop_add_signal(pw_main_loop_get_loop(s.loop),SIGINT,quit,&s);
    s.stream=pw_stream_new_simple(pw_main_loop_get_loop(s.loop),"Android Camera2 RGB bridge",
        pw_properties_new(PW_KEY_NODE_NAME,argv[1],PW_KEY_NODE_DESCRIPTION,argv[2],PW_KEY_MEDIA_CLASS,"Video/Source",
                          PW_KEY_MEDIA_TYPE,"Video",PW_KEY_MEDIA_CATEGORY,"Capture",PW_KEY_MEDIA_ROLE,"Camera",
                          PW_KEY_NODE_VIRTUAL,"true","node.supports-request","1",NULL),&events,&s);
    if(!s.stream){s.result=1;goto out;}
    uint8_t buffer[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(buffer,sizeof(buffer));
    const struct spa_pod *format=spa_format_video_raw_build(&b,SPA_PARAM_EnumFormat,
        &SPA_VIDEO_INFO_RAW_INIT(.format=SPA_VIDEO_FORMAT_RGB,.size=SPA_RECTANGLE(WIDTH,HEIGHT),.framerate=SPA_FRACTION(30,1)));
    if(pw_stream_connect(s.stream,PW_DIRECTION_OUTPUT,PW_ID_ANY,PW_STREAM_FLAG_DRIVER|PW_STREAM_FLAG_MAP_BUFFERS,&format,1)<0){s.result=1;goto out;}
    if(pthread_create(&s.reader,NULL,read_frames,&s)){s.result=1;goto out;}
    pw_main_loop_run(s.loop);s.shutting_down=1;atomic_store(&s.stopping,1);pthread_join(s.reader,NULL);
out:
    fprintf(stderr,"RGB_SOURCE_END frames=%llu output=%u result=%d\n",(unsigned long long)s.received,s.sequence,s.result);
    if(s.stream)pw_stream_destroy(s.stream);pw_main_loop_destroy(s.loop);free(s.latest);pthread_mutex_destroy(&s.mutex);return s.result;
}
