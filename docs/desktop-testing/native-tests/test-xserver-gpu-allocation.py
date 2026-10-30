#!/usr/bin/env python3
"""Exercise actual glamor pixmap allocation: unsupported/FBO failures cannot become CPU pixmaps."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('source',type=Path)
parser.add_argument('--cc',default='cc')
args=parser.parse_args()
source=(args.source/'glamor/glamor.c').read_text()
function=source[source.index('PixmapPtr\nglamor_create_pixmap('):source.index('Bool\nglamor_destroy_pixmap(')]
harness=r'''
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>
#define NullPixmap NULL
#define GLAMOR_CREATE_PIXMAP_CPU 1
#define CREATE_PIXMAP_USAGE_GLYPH_PICTURE 2
#define GLAMOR_CREATE_FORMAT_CBCR 3
#define GLAMOR_CREATE_PIXMAP_NO_TEXTURE 4
#define GLAMOR_CREATE_NO_LARGE 5
#define GLAMOR_TEXTURE_ONLY 6
#define ErrorF(...) fprintf(stderr,__VA_ARGS__)
#define DEBUGF(...) ((void)0)
typedef struct { int is_cbcr,type; } glamor_pixmap_private;
typedef struct Pixmap { struct { int bitsPerPixel; } drawable; glamor_pixmap_private priv; } *PixmapPtr;
typedef struct Screen { int (*ModifyPixmapHeader)(PixmapPtr,int,int,int,int,int,void *); } *ScreenPtr;
typedef struct { int gpu_unsupported,rendering_supported; } Format;
typedef struct { Format formats[33]; int glyph_max_dim,max_fbo_size; } glamor_screen_private;
typedef struct { int present; } glamor_pixmap_fbo;
static glamor_screen_private state;
static glamor_pixmap_fbo fbo;
static int cpuAllocations,destroyed,attached,fboFail;
static glamor_screen_private *glamor_get_screen_private(ScreenPtr screen) { (void)screen;return &state; }
static glamor_pixmap_private *glamor_get_pixmap_private(PixmapPtr pixmap) { return &pixmap->priv; }
static PixmapPtr fbCreatePixmap(ScreenPtr screen,int w,int h,int depth,unsigned usage) {
    (void)screen;(void)usage;
    if(w>0 && h>0)++cpuAllocations;
    PixmapPtr pixmap=calloc(1,sizeof(*pixmap));
    assert(pixmap);pixmap->drawable.bitsPerPixel=depth<=8?8:depth<=16?16:32;
    return pixmap;
}
static void fbDestroyPixmap(PixmapPtr pixmap) { ++destroyed;free(pixmap); }
static void glamor_init_pixmap_private_small(PixmapPtr pixmap,glamor_pixmap_private *priv) { (void)pixmap;(void)priv; }
static int glamor_check_fbo_size(glamor_screen_private *screen,int w,int h) { (void)screen;(void)w;(void)h;return 1; }
static glamor_pixmap_fbo *glamor_create_fbo(glamor_screen_private *screen,PixmapPtr pixmap,int w,int h,unsigned usage) {
    (void)screen;(void)pixmap;(void)w;(void)h;(void)usage;return fboFail?NULL:&fbo;
}
static glamor_pixmap_fbo *glamor_create_fbo_array(glamor_screen_private *screen,PixmapPtr pixmap,unsigned usage,int w,int h,glamor_pixmap_private *priv) {
    (void)priv;return glamor_create_fbo(screen,pixmap,w,h,usage);
}
static void glamor_pixmap_attach_fbo(PixmapPtr pixmap,glamor_pixmap_fbo *value) { (void)pixmap;(void)value;++attached; }
static int modify(PixmapPtr pixmap,int w,int h,int depth,int bpp,int pitch,void *bits) {
    (void)pixmap;(void)w;(void)h;(void)depth;(void)bpp;(void)pitch;(void)bits;return 1;
}
'''
harness+=function
harness+=r'''
int main(void) {
    struct Screen screen={modify};
    assert(!setenv("XLORIE_GLAMOR","1",1));
    state.formats[30].gpu_unsupported=1;
    assert(!glamor_create_pixmap(&screen,16,16,30,0));
    assert(!glamor_create_pixmap(&screen,16,16,30,GLAMOR_CREATE_PIXMAP_CPU));
    assert(cpuAllocations==0 && destroyed==0);
    state.formats[24].rendering_supported=1;
    fboFail=1;
    assert(!glamor_create_pixmap(&screen,16,16,24,0));
    assert(cpuAllocations==0 && destroyed==1);
    state.formats[16].rendering_supported=1;
    fboFail=0;
    PixmapPtr good=glamor_create_pixmap(&screen,16,16,16,0);
    assert(good && attached==1 && cpuAllocations==0);
    free(good);
    puts("PASS: unsupported depth and GPU FBO failures reject without CPU allocation; supported GPU path remains");
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-gpu-allocation-') as temp:
    test,binary=Path(temp)/'test.c',Path(temp)/'test'
    test.write_text(harness)
    subprocess.run([args.cc,'-std=c11','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',str(test),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
