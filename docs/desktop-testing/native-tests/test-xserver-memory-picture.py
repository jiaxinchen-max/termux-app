#!/usr/bin/env python3
"""Exercise the actual temporary-picture upload function and strict GL failures."""
import argparse
from pathlib import Path
import subprocess
import tempfile

p=argparse.ArgumentParser()
p.add_argument('source',type=Path)
p.add_argument('--cc',default='cc')
args=p.parse_args()
source=(args.source/'glamor/glamor_picture.c').read_text()
function=source[source.index('Bool\nglamor_upload_picture_to_texture('):]
header=r'''
#define _POSIX_C_SOURCE 200809L
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <assert.h>
#include <setjmp.h>
#include <stdbool.h>
typedef int Bool;
typedef unsigned GLenum;
typedef unsigned PictFormatShort;
#define TRUE 1
#define FALSE 0
#define GL_NO_ERROR 0
#define GL_RED 0x1903
#define GL_GREEN 0x1904
#define GL_BLUE 0x1905
#define GL_ALPHA 0x1906
#define GL_R8 0x8229
#define GL_UNSIGNED_BYTE 0x1401
#define GL_UNPACK_ALIGNMENT 0x0cf5
#define GL_TEXTURE_2D 0x0de1
#define GL_TEXTURE_SWIZZLE_R 0x8e42
#define GL_TEXTURE_SWIZZLE_G 0x8e43
#define GL_TEXTURE_SWIZZLE_B 0x8e44
#define GL_TEXTURE_SWIZZLE_A 0x8e45
#define GLAMOR_CREATE_FBO_NO_FBO 1
typedef struct {int unused;} ScreenRec,*ScreenPtr;
typedef struct {ScreenPtr pScreen;int width,height,depth;} DrawableRec,*DrawablePtr;
typedef struct {DrawableRec drawable;union {void *ptr;} devPrivate;int devKind;} PixmapRec,*PixmapPtr;
typedef struct {DrawablePtr pDrawable;PictFormatShort format;} PictureRec,*PicturePtr;
typedef struct {unsigned tex;} Fbo;
typedef struct {Fbo *fbo;} glamor_pixmap_private;
typedef struct {int is_gles,has_texture_swizzle,suppress_gl_out_of_memory_logging;} glamor_screen_private;
typedef struct {int unused;} pixman_image_t;
struct glamor_format {GLenum internalformat;};
static glamor_screen_private screen_private;
static glamor_pixmap_private pixmap_private;
static struct glamor_format pixel_format;
static Fbo fbo;
static GLenum pending_error,upload_error,seen_internal,seen_external;
static int allocation_fails,upload_calls;
static jmp_buf escape;
static char fatal_message[2048];
static void FatalError(const char *fmt,...) {va_list ap;va_start(ap,fmt);vsnprintf(fatal_message,sizeof(fatal_message),fmt,ap);va_end(ap);longjmp(escape,1);}
static PixmapPtr glamor_get_drawable_pixmap(DrawablePtr p) {return (PixmapPtr)p;}
static glamor_screen_private *glamor_get_screen_private(ScreenPtr p) {(void)p;return &screen_private;}
static glamor_pixmap_private *glamor_get_pixmap_private(PixmapPtr p) {(void)p;return &pixmap_private;}
static const struct glamor_format *glamor_format_for_pixmap(PixmapPtr p) {(void)p;return &pixel_format;}
static int glamor_pixmap_is_memory(PixmapPtr p) {(void)p;return 1;}
static void glamor_make_current(glamor_screen_private *p) {(void)p;}
static GLenum glGetError(void) {GLenum result=pending_error;pending_error=0;return result;}
static int glamor_check_fbo_size(glamor_screen_private *p,int w,int h) {(void)p;(void)w;(void)h;return 1;}
static int glamor_get_tex_format_type_from_pictformat(ScreenPtr p,PictFormatShort format,PictFormatShort *converted,GLenum *external,GLenum *type,GLenum *swizzle) {(void)p;*converted=format;*external=GL_RED;*type=GL_UNSIGNED_BYTE;swizzle[0]=GL_RED;swizzle[1]=GL_GREEN;swizzle[2]=GL_BLUE;swizzle[3]=GL_ALPHA;return 1;}
static void glamor_fallback(const char *s,...) {(void)s;}
static pixman_image_t *glamor_get_converted_image(PictFormatShort a,PictFormatShort b,void *c,int d,int e,int f) {(void)a;(void)b;(void)c;(void)d;(void)e;(void)f;return NULL;}
static void *pixman_image_get_data(pixman_image_t *p) {(void)p;return NULL;}
static int pixman_image_get_stride(pixman_image_t *p) {(void)p;return 0;}
static void pixman_image_unref(pixman_image_t *p) {(void)p;}
static int glamor_pixmap_ensure_fbo(PixmapPtr p,int flags) {(void)p;(void)flags;if(allocation_fails)return 0;pixmap_private.fbo=&fbo;return 1;}
static void glPixelStorei(GLenum a,int b) {(void)a;(void)b;}
static void glBindTexture(GLenum a,unsigned b) {(void)a;(void)b;}
static void glTexParameteri(GLenum a,GLenum b,int c) {(void)a;(void)b;(void)c;}
static void glTexImage2D(GLenum a,int b,int internal,int w,int h,int border,GLenum external,GLenum type,const void *bits) {(void)a;(void)b;(void)w;(void)h;(void)border;(void)type;(void)bits;seen_internal=internal;seen_external=external;upload_calls++;pending_error=upload_error;}
'''
tests=r'''
static void reset(void) {memset(&screen_private,0,sizeof(screen_private));screen_private.is_gles=1;screen_private.has_texture_swizzle=1;pixmap_private.fbo=NULL;pixel_format.internalformat=GL_R8;pending_error=upload_error=seen_internal=seen_external=0;allocation_fails=upload_calls=0;fatal_message[0]=0;}
int main(void) {
    ScreenRec screen={0};uint8_t bits[16]={0};PixmapRec pixmap={{&screen,4,4,8},{bits},4};PictureRec picture={&pixmap.drawable,8};
    setenv("XLORIE_GLAMOR","1",1);
    reset();assert(!setjmp(escape));assert(glamor_upload_picture_to_texture(&picture));assert(seen_internal==GL_R8&&seen_external==GL_RED&&upload_calls==1);
    reset();pending_error=0x500;if(!setjmp(escape)){glamor_upload_picture_to_texture(&picture);assert(!"pending GL error was ignored");}assert(strstr(fatal_message,"0x500")&&upload_calls==0);
    reset();allocation_fails=1;if(!setjmp(escape)){glamor_upload_picture_to_texture(&picture);assert(!"GPU allocation failure returned to CPU path");}assert(strstr(fatal_message,"allocation failed")&&upload_calls==0);
    reset();upload_error=0x502;if(!setjmp(escape)){glamor_upload_picture_to_texture(&picture);assert(!"INVALID_OPERATION was ignored");}assert(strstr(fatal_message,"0x502")&&upload_calls==1);
    reset();upload_error=0x505;if(!setjmp(escape)){glamor_upload_picture_to_texture(&picture);assert(!"OUT_OF_MEMORY returned to CPU path");}assert(strstr(fatal_message,"0x505")&&upload_calls==1);
    puts("PASS: actual memory-picture function uses R8, preserves pending/upload GL error codes, aborts required GPU allocation and all GL failures");
    return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-memory-picture-') as d:
    path=Path(d);c=path/'test.c';exe=path/'test';c.write_text(header+function+tests)
    subprocess.run([args.cc,'-std=c11','-Wall','-Wextra','-O1','-g','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
