/* SPDX-License-Identifier: MIT */
#include "camera_wire.h"
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include <pthread.h>
#include <stdatomic.h>
#include <poll.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
struct source {
    struct pw_main_loop *loop;struct pw_stream *stream;struct spa_source *input_event;
    pthread_mutex_t mutex;pthread_t reader;atomic_int stopping,eof,error;
    uint8_t *latest;unsigned width,height,fps,stride,output;
    uint64_t received,stamp,sequence,origin_stamp,origin_time;
    int shutting_down,streaming,result;
};
/* 0 clean EOF, 1 complete, -1 truncated/error, -2 local shutdown. */
static int read_exact(struct source *s,void *data,size_t length){
    uint8_t *p=data;size_t used=0;
    while(used<length){
        if(atomic_load(&s->stopping))return -2;
        struct pollfd fd={.fd=STDIN_FILENO,.events=POLLIN};int status=poll(&fd,1,100);
        if(status<0){if(errno==EINTR)continue;return -1;}if(!status)continue;
        ssize_t n=read(STDIN_FILENO,p+used,length-used);
        if(n<0){if(errno==EINTR)continue;return -1;}
        if(!n)return used?-1:0;used+=(size_t)n;
    }return 1;
}
static void *read_frames(void *data){
    struct source *s=data;size_t bytes=(size_t)s->width*s->height*3;
    uint8_t *frame=malloc(bytes),header[TUSB_FRAME_HEADER];uint64_t last_stamp=0,last_seq=0;
    if(!frame){atomic_store(&s->error,1);goto done;}
    while(!atomic_load(&s->stopping)){
        int status=read_exact(s,header,sizeof(header));
        if(status<=0){if(status==-1)atomic_store(&s->error,1);break;}
        uint64_t stamp=tusb_get64(header+16),seq=tusb_get64(header+24);
        if(memcmp(header,"TCF1",4) || tusb_get32(header+4)!=s->width || tusb_get32(header+8)!=s->height ||
           tusb_get32(header+12)!=bytes || !stamp || stamp>INT64_MAX || !seq || stamp<=last_stamp || seq<=last_seq){
            fprintf(stderr,"Invalid Camera2 frame header\n");atomic_store(&s->error,1);break;
        }
        status=read_exact(s,frame,bytes);
        if(status!=1){if(status!=-2)atomic_store(&s->error,1);break;}
        pthread_mutex_lock(&s->mutex);
        for(unsigned y=0;y<s->height;y++)memcpy(s->latest+(size_t)y*s->stride,frame+(size_t)y*s->width*3,s->width*3);
        s->received++;s->stamp=stamp;s->sequence=seq;
        pthread_mutex_unlock(&s->mutex);last_stamp=stamp;last_seq=seq;
        pw_loop_signal_event(pw_main_loop_get_loop(s->loop),s->input_event);
    }
    free(frame);
done:
    atomic_store(&s->eof,1);pw_loop_signal_event(pw_main_loop_get_loop(s->loop),s->input_event);return NULL;
}
static void input_event(void *data,uint64_t count){
    (void)count;struct source *s=data;
    if(atomic_load(&s->eof)){s->result=atomic_load(&s->error);s->shutting_down=1;pw_main_loop_quit(s->loop);}
    else if(s->streaming)pw_stream_trigger_process(s->stream);
}
static void process(void *data){
    struct source *s=data;struct pw_buffer *b=pw_stream_dequeue_buffer(s->stream);if(!b)return;
    struct spa_data *d=&b->buffer->datas[0];size_t bytes=(size_t)s->stride*s->height;
    if(!d->data || !d->chunk || d->maxsize<bytes){if(d->chunk)d->chunk->size=0;pw_stream_queue_buffer(s->stream,b);return;}
    pthread_mutex_lock(&s->mutex);memcpy(d->data,s->latest,bytes);
    uint64_t stamp=s->stamp,seq=s->sequence,received=s->received;pthread_mutex_unlock(&s->mutex);
    struct spa_meta_header *h=spa_buffer_find_meta_data(b->buffer,SPA_META_Header,sizeof(*h));
    if(received && !s->origin_stamp){s->origin_stamp=stamp;s->origin_time=pw_stream_get_nsec(s->stream);}
    if(h){h->seq=(uint32_t)seq;h->pts=received?(int64_t)(s->origin_time+stamp-s->origin_stamp):0;h->flags=0;h->dts_offset=0;}
    d->chunk->offset=0;d->chunk->stride=(int32_t)s->stride;d->chunk->size=received?(uint32_t)bytes:0;
    s->output++;pw_stream_queue_buffer(s->stream,b);
}
static void quit(void *data,int number){(void)number;struct source *s=data;s->shutting_down=1;pw_main_loop_quit(s->loop);}
static void state_changed(void *data,enum pw_stream_state old,enum pw_stream_state state,const char *error){
    struct source *s=data;s->streaming=state==PW_STREAM_STATE_STREAMING;
    if(state==PW_STREAM_STATE_ERROR || (!s->shutting_down && state==PW_STREAM_STATE_UNCONNECTED && old!=PW_STREAM_STATE_UNCONNECTED)){
        fprintf(stderr,"Camera2 PipeWire error: %s\n",error?error:"disconnected");s->result=1;pw_main_loop_quit(s->loop);
    }else if(s->streaming){pthread_mutex_lock(&s->mutex);int ready=s->received>0;pthread_mutex_unlock(&s->mutex);if(ready)pw_stream_trigger_process(s->stream);}
}
static void format_changed(void *data,uint32_t id,const struct spa_pod *param){
    if(id!=SPA_PARAM_Format || !param)return;struct source *s=data;struct spa_video_info_raw format={0};
    if(spa_format_video_raw_parse(param,&format)<0 || format.format!=SPA_VIDEO_FORMAT_RGB ||
       format.size.width!=s->width || format.size.height!=s->height){s->result=1;pw_main_loop_quit(s->loop);return;}
    uint8_t buffer[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(buffer,sizeof(buffer));const struct spa_pod *params[2];
    params[0]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamBuffers,SPA_PARAM_Buffers,
        SPA_PARAM_BUFFERS_buffers,SPA_POD_CHOICE_RANGE_Int(4,2,16),SPA_PARAM_BUFFERS_blocks,SPA_POD_Int(1),
        SPA_PARAM_BUFFERS_size,SPA_POD_Int((int32_t)(s->stride*s->height)),SPA_PARAM_BUFFERS_stride,SPA_POD_Int((int32_t)s->stride),
        SPA_PARAM_BUFFERS_dataType,SPA_POD_CHOICE_FLAGS_Int((1<<SPA_DATA_MemPtr)|(1<<SPA_DATA_MemFd)));
    params[1]=spa_pod_builder_add_object(&b,SPA_TYPE_OBJECT_ParamMeta,SPA_PARAM_Meta,
        SPA_PARAM_META_type,SPA_POD_Id(SPA_META_Header),SPA_PARAM_META_size,SPA_POD_Int(sizeof(struct spa_meta_header)));
    pw_stream_update_params(s->stream,params,2);
}
static const struct pw_stream_events events={PW_VERSION_STREAM_EVENTS,.process=process,.state_changed=state_changed,.param_changed=format_changed};
static int number(const char *s,unsigned *out,unsigned max){char *end=NULL;errno=0;unsigned long v=strtoul(s,&end,10);if(errno || !*s || *end || !v || v>max)return -1;*out=(unsigned)v;return 0;}
int main(int argc,char **argv){
    struct source s={.mutex=PTHREAD_MUTEX_INITIALIZER,.width=640,.height=480,.fps=30};
    const char *name="tusb.camera2",*description="Android Camera2";
    for(int i=1;i<argc;i++){
        if(!strcmp(argv[i],"--name") && i+1<argc)name=argv[++i];
        else if(!strcmp(argv[i],"--description") && i+1<argc)description=argv[++i];
        else if(!strcmp(argv[i],"--width") && i+1<argc){if(number(argv[++i],&s.width,4096))return 2;}
        else if(!strcmp(argv[i],"--height") && i+1<argc){if(number(argv[++i],&s.height,2160))return 2;}
        else if(!strcmp(argv[i],"--fps") && i+1<argc){if(number(argv[++i],&s.fps,240))return 2;}
        else return 2;
    }
    if(s.width%2 || s.height%2)return 2;s.stride=(s.width*3+3)&~3u;
    s.latest=calloc(s.height,s.stride);if(!s.latest)return 1;
    pw_init(NULL,NULL);s.loop=pw_main_loop_new(NULL);if(!s.loop){free(s.latest);return 1;}
    s.input_event=pw_loop_add_event(pw_main_loop_get_loop(s.loop),input_event,&s);
    pw_loop_add_signal(pw_main_loop_get_loop(s.loop),SIGTERM,quit,&s);pw_loop_add_signal(pw_main_loop_get_loop(s.loop),SIGINT,quit,&s);
    s.stream=pw_stream_new_simple(pw_main_loop_get_loop(s.loop),"Android Camera2 bridge",
        pw_properties_new(PW_KEY_NODE_NAME,name,PW_KEY_NODE_DESCRIPTION,description,PW_KEY_MEDIA_CLASS,"Video/Source",
            PW_KEY_MEDIA_TYPE,"Video",PW_KEY_MEDIA_CATEGORY,"Capture",PW_KEY_MEDIA_ROLE,"Camera",PW_KEY_NODE_VIRTUAL,"true","node.supports-request","1",NULL),&events,&s);
    if(!s.stream){s.result=1;goto out;}
    uint8_t buffer[512];struct spa_pod_builder b=SPA_POD_BUILDER_INIT(buffer,sizeof(buffer));
    const struct spa_pod *format=spa_format_video_raw_build(&b,SPA_PARAM_EnumFormat,
        &SPA_VIDEO_INFO_RAW_INIT(.format=SPA_VIDEO_FORMAT_RGB,.size=SPA_RECTANGLE(s.width,s.height),.framerate=SPA_FRACTION(s.fps,1)));
    if(pw_stream_connect(s.stream,PW_DIRECTION_OUTPUT,PW_ID_ANY,PW_STREAM_FLAG_DRIVER|PW_STREAM_FLAG_MAP_BUFFERS,&format,1)<0){s.result=1;goto out;}
    if(pthread_create(&s.reader,NULL,read_frames,&s)){s.result=1;goto out;}
    pw_main_loop_run(s.loop);s.shutting_down=1;atomic_store(&s.stopping,1);pthread_join(s.reader,NULL);
    if(!s.received)s.result=1;
out:
    fprintf(stderr,"Camera2 source stopped: received=%llu output=%u result=%d\n",(unsigned long long)s.received,s.output,s.result);
    if(s.stream)pw_stream_destroy(s.stream);pw_main_loop_destroy(s.loop);free(s.latest);pthread_mutex_destroy(&s.mutex);return s.result;
}
