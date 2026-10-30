#!/usr/bin/env python3
"""Exercise actual transfer helpers against a normalized GLES mock, exhaustively."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('source',type=Path)
parser.add_argument('--cc',default='cc')
args=parser.parse_args()
source=(args.source/'glamor/glamor_transfer.c').read_text()
upload=source[source.index('static void\nglamor_upload_pixels('):source.index('/*\n * Write a region of bits')]
read=source[source.index('static void\nglamor_read_pixels('):source.index('/*\n * Read stuff')]
harness=r'''
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
typedef int GLint;
#define FatalError(...) abort()
#define GL_RGBA 0x1908
#define GL_RED 0x1903
#define GL_RGB 0x1907
#define GL_BGRA 0x80e1
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNSIGNED_SHORT_5_6_5 0x8363
#define GL_TEXTURE_2D 0x0de1
#define GL_UNPACK_ROW_LENGTH 0x0cf2
#define GL_PACK_ROW_LENGTH 0x0d02
struct glamor_format { unsigned format,type; int rgba_readback,rgba_upload,depth; };
static int packLength,unpackLength,textureWidth;
static uint8_t texture[65536*4];
static void glGetIntegerv(unsigned name,int *value) { *value=name==GL_PACK_ROW_LENGTH?packLength:unpackLength; }
static void glPixelStorei(unsigned name,int value) { if(name==GL_PACK_ROW_LENGTH)packLength=value;else unpackLength=value; }
static void glTexSubImage2D(unsigned target,int level,int x,int y,int width,int height,unsigned format,unsigned type,const uint8_t *bits) {
    (void)target;(void)level;
    int bpp=format==GL_RED?1:type==GL_UNSIGNED_SHORT_5_6_5?2:4;
    int stride=(unpackLength?unpackLength:width)*bpp;
    stride=(stride+3)&~3;
    for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
        const uint8_t *src=bits+row*stride+col*bpp;
        uint8_t *dst=texture+((y+row)*textureWidth+x+col)*4;
        if(format==GL_RED) { dst[0]=src[0];dst[1]=dst[2]=0;dst[3]=255; }
        else if(type==GL_UNSIGNED_SHORT_5_6_5) {
            unsigned value=src[0]|((unsigned)src[1]<<8);
            dst[0]=(((value>>11)&31)*255+15)/31;
            dst[1]=(((value>>5)&63)*255+31)/63;
            dst[2]=((value&31)*255+15)/31;dst[3]=255;
        } else memcpy(dst,src,4);
    }
}
static void glReadPixels(int x,int y,int width,int height,unsigned format,unsigned type,uint8_t *bits) {
    assert(format==GL_RGBA && type==GL_UNSIGNED_BYTE);
    int stride=(packLength?packLength:width)*4;
    for(int row=0;row<height;row++)for(int col=0;col<width;col++)
        memcpy(bits+row*stride+col*4,texture+((y+row)*textureWidth+x+col)*4,4);
}
'''
harness+=upload+read
harness+=r'''
static void test(int depth) {
    int bpp=depth==8?1:2;
    int width=depth==8?16:256;
    int height=depth==8?16:depth==15?128:256;
    int stride=width*bpp+8;
    size_t size=(size_t)height*stride+16;
    uint8_t *input=malloc(size),*output=malloc(size);
    assert(input && output);
    memset(input,0xcd,size);memset(output,0xcd,size);
    for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
        unsigned value=y*width+x;
        uint8_t *dst=input+4+y*stride+x*bpp;
        dst[0]=value&255;if(bpp==2)dst[1]=value>>8;
    }
    struct glamor_format f={depth==8?GL_RED:depth==16?GL_RGB:GL_RGBA,
        depth==16?GL_UNSIGNED_SHORT_5_6_5:GL_UNSIGNED_BYTE,1,depth==15,depth};
    textureWidth=width;
    unpackLength=stride/bpp;
    glamor_upload_pixels(&f,0,0,width,height,input+4,stride);
    assert(unpackLength==stride/bpp);
    packLength=stride/bpp;
    glamor_read_pixels(&f,0,0,width,height,output+4,stride);
    assert(packLength==stride/bpp);
    /* Includes every valid pixel value; checks both little-endian bytes and all padding. */
    assert(!memcmp(input,output,size));
    memset(output,0xcd,size);
    glamor_read_pixels(&f,3,2,5,4,output+4,stride);
    for(size_t i=0;i<size;i++) {
        if(i>=4 && (i-4)/stride<4 && (i-4)%stride<(size_t)(5*bpp)) {
            int y=(i-4)/stride,x=((i-4)%stride)/bpp,byte=(i-4)%bpp;
            unsigned expected=(y+2)*width+x+3;
            assert(output[i]==((expected>>(byte*8))&255));
        } else assert(output[i]==0xcd);
    }
    free(input);free(output);
}
int main(void) {
    test(8);test(15);test(16);
    puts("PASS: real upload/download losslessly round-trip all A8/X1555/RGB565 pixels; stride/padding/rectangles/little-endian; GL pack state restored");
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-depth-test-') as temp:
    test,binary=Path(temp)/'test.c',Path(temp)/'test'
    test.write_text(harness)
    subprocess.run([args.cc,'-std=c11','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',str(test),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
