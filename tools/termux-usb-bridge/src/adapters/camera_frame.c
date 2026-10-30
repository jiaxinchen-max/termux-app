/* SPDX-License-Identifier: MIT */
#include "camera_frame.h"
#include <turbojpeg.h>
#include <errno.h>
#include <limits.h>

static uint8_t clip(int value) {return value<0?0:value>255?255:(uint8_t)value;}
static void yuv_pixel(uint8_t *rgb,int y,int u,int v)
{
    int c=298*(y-16),d=u-128,e=v-128;
    rgb[0]=clip((c+409*e+128)>>8);
    rgb[1]=clip((c-100*d-208*e+128)>>8);
    rgb[2]=clip((c+516*d+128)>>8);
}
int tusb_camera_decode(struct tusb_camera_decoder *decoder,enum tusb_camera_format format,
    const void *data,size_t size,unsigned width,unsigned height,size_t step,void *output,size_t capacity)
{
    if(!decoder || !data || !output || !width || !height || width>4096 || height>2160 ||
       (size_t)width*height*3>capacity) {errno=EINVAL;return -1;}
    const uint8_t *input=data;uint8_t *rgb=output;
    if(format==TUSB_CAMERA_YUYV) {
        if(!step) step=(size_t)width*2;
        if((width&1) || step<(size_t)width*2 || step>SIZE_MAX/height ||
           size<step*(height-1)+(size_t)width*2) {errno=EINVAL;return -1;}
        /* libuvc 0.0.7's raw converter processes groups of eight pixels and
         * ignores input stride/length. Decode pairs with explicit row bounds. */
        for(unsigned row=0;row<height;row++) for(unsigned x=0;x<width;x+=2) {
            const uint8_t *p=input+row*step+x*2;
            uint8_t *out=rgb+((size_t)row*width+x)*3;
            yuv_pixel(out,p[0],p[1],p[3]);yuv_pixel(out+3,p[2],p[1],p[3]);
        }
        return 0;
    }
    if(format!=TUSB_CAMERA_MJPEG || size>64u*1024*1024 || size>ULONG_MAX) {errno=EINVAL;return -1;}
    if(!decoder->jpeg) decoder->jpeg=tjInitDecompress();
    if(!decoder->jpeg) {errno=ENOMEM;return -1;}
    int actual_width,actual_height,subsampling,colorspace;
    if(tjDecompressHeader3(decoder->jpeg,input,(unsigned long)size,&actual_width,&actual_height,&subsampling,&colorspace) ||
       actual_width!=(int)width || actual_height!=(int)height) {
        tusb_camera_decoder_close(decoder);errno=EINVAL;return -1;
    }
    /* Validate the JPEG header before handing it a buffer sized from UVC's
     * metadata. The vendored MJPEG converter does not check this mismatch. */
    if(tjDecompress2(decoder->jpeg,input,(unsigned long)size,rgb,(int)width,(int)width*3,(int)height,
                     TJPF_RGB,TJFLAG_FASTDCT|TJFLAG_STOPONWARNING)) {
        tusb_camera_decoder_close(decoder);errno=EIO;return -1;
    }
    return 0;
}
void tusb_camera_decoder_close(struct tusb_camera_decoder *decoder)
{if(decoder && decoder->jpeg) {tjDestroy(decoder->jpeg);decoder->jpeg=NULL;}}
