/* SPDX-License-Identifier: MIT */
#include <pipewire/pipewire.h>
#include <spa/param/video/format-utils.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
struct sink {
    struct pw_main_loop *loop;struct pw_stream *stream;
    unsigned frames,width,height,fps,target;int changed,result;
    const char *output,*name;uint64_t checksum,previous;
};
static void process(void *userdata)
{
    struct sink *s=userdata;struct pw_buffer *buffer=pw_stream_dequeue_buffer(s->stream);if(!buffer)return;
    struct spa_data *data=&buffer->buffer->datas[0];
    size_t row_size=(size_t)s->width*3;
    if(data->data && data->chunk && data->chunk->stride>0 && (size_t)data->chunk->stride>=row_size &&
       data->chunk->size<=data->maxsize && data->chunk->offset<=data->maxsize-data->chunk->size &&
       data->chunk->size>=(s->height-1)*(size_t)data->chunk->stride+row_size) {
        unsigned char *pixels=(unsigned char *)data->data+data->chunk->offset;
        s->checksum=UINT64_C(14695981039346656037);
        for(unsigned row=0;row<s->height;row++)for(size_t x=0;x<row_size;x++) {
            s->checksum^=pixels[(size_t)row*data->chunk->stride+x];s->checksum*=UINT64_C(1099511628211);
        }
        if(s->frames && s->checksum!=s->previous)s->changed=1;
        s->previous=s->checksum;s->frames++;
        if(s->frames>=s->target && s->changed) {
            if(s->output) {
                FILE *out=fopen(s->output,"wb");
                if(!out){perror("capture output");pw_main_loop_quit(s->loop);goto queue;}
                fprintf(out,"P6\n%u %u\n255\n",s->width,s->height);
                int ok=1;
                for(unsigned row=0;row<s->height;row++)if(fwrite(pixels+(size_t)row*data->chunk->stride,1,row_size,out)!=row_size)ok=0;
                if(fclose(out))ok=0;
                if(!ok){pw_main_loop_quit(s->loop);goto queue;}
            }
            s->result=0;pw_main_loop_quit(s->loop);
        }
    }
queue:pw_stream_queue_buffer(s->stream,buffer);
}
static void timeout(void *userdata,uint64_t expirations){(void)expirations;struct sink *s=userdata;pw_main_loop_quit(s->loop);}
static const struct pw_stream_events events={PW_VERSION_STREAM_EVENTS,.process=process};
int main(int argc,char **argv)
{
    struct sink s={.result=1,.width=64,.height=48,.fps=10,.target=3,.name="tusb.test.capture"};
    for(int i=1;i<argc;i++) {
        if(!strcmp(argv[i],"--width") && i+1<argc)s.width=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--height") && i+1<argc)s.height=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--fps") && i+1<argc)s.fps=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--frames") && i+1<argc)s.target=(unsigned)strtoul(argv[++i],NULL,10);
        else if(!strcmp(argv[i],"--output") && i+1<argc)s.output=argv[++i];
        else if(!strcmp(argv[i],"--name") && i+1<argc)s.name=argv[++i];else return 2;
    }
    if(!s.width || !s.height || s.width>4096 || s.height>2160 || !s.fps || s.fps>240 || !s.target || s.target>300)return 2;
    pw_init(NULL,NULL);s.loop=pw_main_loop_new(NULL);
    s.stream=pw_stream_new_simple(pw_main_loop_get_loop(s.loop),"tusb-test-capture",
        pw_properties_new(PW_KEY_NODE_NAME,s.name,PW_KEY_MEDIA_CLASS,"Video/Sink",NULL),&events,&s);
    uint8_t memory[512];struct spa_pod_builder builder=SPA_POD_BUILDER_INIT(memory,sizeof(memory));
    const struct spa_pod *format=spa_format_video_raw_build(&builder,SPA_PARAM_EnumFormat,
        &SPA_VIDEO_INFO_RAW_INIT(.format=SPA_VIDEO_FORMAT_RGB,.size=SPA_RECTANGLE(s.width,s.height),.framerate=SPA_FRACTION(s.fps,1)));
    if(pw_stream_connect(s.stream,PW_DIRECTION_INPUT,PW_ID_ANY,PW_STREAM_FLAG_MAP_BUFFERS,&format,1)<0)return 1;
    struct spa_source *timer=pw_loop_add_timer(pw_main_loop_get_loop(s.loop),timeout,&s);
    struct timespec end={12,0};pw_loop_update_timer(pw_main_loop_get_loop(s.loop),timer,&end,NULL,false);
    pw_main_loop_run(s.loop);printf("width=%u height=%u frames=%u changed=%d checksum=%016llx\n",s.width,s.height,s.frames,s.changed,(unsigned long long)s.checksum);
    pw_stream_destroy(s.stream);pw_main_loop_destroy(s.loop);pw_deinit();return s.result;
}
