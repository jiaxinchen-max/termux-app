/* SPDX-License-Identifier: MIT */
#include "camera_frame.h"
#include <turbojpeg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQUIRE(condition) do {if(!(condition)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#condition);return 1;}} while(0)
static int guard(const unsigned char *data,size_t start,size_t end)
{for(size_t i=start;i<end;i++) if(data[i]!=0xa5)return 0;return 1;}
int main(void)
{
    struct tusb_camera_decoder decoder={0};
    unsigned char raw[48],rgb[70];memset(raw,0xff,sizeof(raw));memset(rgb,0xa5,sizeof(rgb));
    for(int row=0;row<3;row++)for(int x=0;x<6;x+=2) {
        unsigned char *p=raw+row*16+x*2;
        p[0]=16;p[1]=128;p[2]=235;p[3]=128;
    }
    REQUIRE(!tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,sizeof(raw),6,3,16,rgb,54));
    for(int i=0;i<18;i++)for(int c=0;c<3;c++)REQUIRE(rgb[i*3+c]==(i&1?255:0));
    REQUIRE(guard(rgb,54,sizeof(rgb)));
    memset(rgb,0xa5,sizeof(rgb));
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,43,6,3,16,rgb,54)<0);
    REQUIRE(guard(rgb,0,sizeof(rgb)));
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,sizeof(raw),5,3,16,rgb,54)<0);
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,sizeof(raw),6,3,11,rgb,54)<0);
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,sizeof(raw),6,3,SIZE_MAX,rgb,54)<0);
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,raw,sizeof(raw),6,3,16,rgb,53)<0);
    REQUIRE(guard(rgb,0,sizeof(rgb)));
    unsigned char colors[]={81,90,81,240,145,54,145,34,41,240,41,110};
    REQUIRE(!tusb_camera_decode(&decoder,TUSB_CAMERA_YUYV,colors,sizeof(colors),6,1,0,rgb,54));
    REQUIRE(rgb[0]>250 && rgb[1]<3 && rgb[2]<3);
    REQUIRE(rgb[6]<3 && rgb[7]>250 && rgb[8]<3);
    REQUIRE(rgb[12]<3 && rgb[13]<3 && rgb[14]>250);
    puts("PASS YUYV: BT.601 colors, row padding, non-eight-pixel dimensions, truncated/odd/overflow input and output bounds");

    unsigned char source[16*16*3],output[16*16*3+16];
    for(size_t i=0;i<sizeof(source);i+=3){source[i]=220;source[i+1]=60;source[i+2]=25;}
    tjhandle encoder=tjInitCompress();REQUIRE(encoder);
    unsigned char *jpeg=NULL;unsigned long length=0;
    REQUIRE(!tjCompress2(encoder,source,16,0,16,TJPF_RGB,&jpeg,&length,TJSAMP_444,100,0));
    tjDestroy(encoder);memset(output,0xa5,sizeof(output));
    REQUIRE(!tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,jpeg,length,16,16,0,output,sizeof(source)));
    for(size_t i=0;i<sizeof(source);i++)REQUIRE(abs((int)source[i]-output[i])<=4);
    REQUIRE(guard(output,sizeof(source),sizeof(output)));
    memset(output,0xa5,sizeof(output));
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,jpeg,length,8,8,0,output,8*8*3)<0);
    REQUIRE(guard(output,0,sizeof(output)));
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,jpeg,length/3,16,16,0,output,sizeof(source))<0);
    REQUIRE(tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,"not jpeg",8,16,16,0,output,sizeof(source))<0);
    REQUIRE(!tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,jpeg,length,16,16,0,output,sizeof(source)));
    /* Many UVC MJPEG devices omit standard Huffman tables. */
    unsigned char *abbreviated=malloc(length);REQUIRE(abbreviated);size_t used=2,pos=2;
    abbreviated[0]=jpeg[0];abbreviated[1]=jpeg[1];
    while(pos+4<length && jpeg[pos]==0xff && jpeg[pos+1]!=0xda) {
        size_t segment=2+((size_t)jpeg[pos+2]<<8)+jpeg[pos+3];REQUIRE(segment<=length-pos);
        if(jpeg[pos+1]!=0xc4){memcpy(abbreviated+used,jpeg+pos,segment);used+=segment;}
        pos+=segment;
    }
    memcpy(abbreviated+used,jpeg+pos,length-pos);used+=length-pos;
    REQUIRE(used<length);
    tusb_camera_decoder_close(&decoder);
    REQUIRE(!tusb_camera_decode(&decoder,TUSB_CAMERA_MJPEG,abbreviated,used,16,16,0,output,sizeof(source)));
    for(size_t i=0;i<sizeof(source);i++)REQUIRE(abs((int)source[i]-output[i])<=4);
    REQUIRE(guard(output,sizeof(source),sizeof(output)));
    free(abbreviated);tjFree(jpeg);tusb_camera_decoder_close(&decoder);tusb_camera_decoder_close(&decoder);
    puts("PASS MJPEG: RGB accuracy, missing Huffman tables, JPEG/UVC size mismatch, corrupt/truncated input, decoder recovery and output bounds");
    puts("TUSB_CAMERA_CODEC_PASS");return 0;
}
