
#define _GNU_SOURCE
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#define GL_BGRA 0x80e1
#define EGL_FUNCS(X) X(eglGetDisplay) X(eglInitialize) X(eglBindAPI) X(eglChooseConfig) X(eglCreatePbufferSurface) X(eglCreateContext) X(eglMakeCurrent) X(eglGetError) X(eglDestroyContext) X(eglDestroySurface) X(eglTerminate)
#define GL_FUNCS(X) X(glGetString) X(glGetError) X(glGenTextures) X(glBindTexture) X(glTexParameteri) X(glTexImage2D) X(glTexSubImage2D) X(glPixelStorei) X(glGetIntegerv) X(glGenFramebuffers) X(glBindFramebuffer) X(glFramebufferTexture2D) X(glCheckFramebufferStatus) X(glReadPixels) X(glDeleteTextures) X(glDeleteFramebuffers) X(glClearColor) X(glClear) X(glFinish) X(glGetShaderPrecisionFormat)
#define DECLARE(f) static __typeof__(&f) p_##f;
EGL_FUNCS(DECLARE)
GL_FUNCS(DECLARE)
#define LOAD_EGL(f) do {p_##f=dlsym(egl_lib,#f);if(!p_##f){fprintf(stderr,"missing %s\n",#f);exit(2);}}while(0);
#define LOAD_GL(f) do {p_##f=dlsym(gl_lib,#f);if(!p_##f){fprintf(stderr,"missing %s\n",#f);exit(2);}}while(0);
static void FatalError(const char *fmt,...) {va_list ap;va_start(ap,fmt);vfprintf(stderr,fmt,ap);va_end(ap);exit(3);}
static void check(const char *where) {GLenum e=p_glGetError();if(e!=GL_NO_ERROR)FatalError("GL error 0x%x at %s\n",e,where);}
struct glamor_format {unsigned format,type;int rgba_readback,rgba_upload,depth;};
#define glTexSubImage2D p_glTexSubImage2D
#define glGetIntegerv p_glGetIntegerv
#define glPixelStorei p_glPixelStorei
#define glReadPixels p_glReadPixels
static void
glamor_upload_pixels(const struct glamor_format *f, int x, int y,
                     int width, int height, const uint8_t *bits, uint32_t stride)
{
    uint8_t *rgba;
    GLint row_length;
    int row, col;

    if (!f->rgba_upload) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height,
                       f->format, f->type, bits);
        return;
    }
    rgba = malloc((size_t) width * 4);
    if (!rgba)
        FatalError("Xlorie: cannot allocate X1555 transfer row\n");
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &row_length);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    for (row = 0; row < height; row++) {
        const uint8_t *src = bits + (size_t) row * stride;
        for (col = 0; col < width; col++) {
            unsigned pixel = src[col * 2] | ((unsigned) src[col * 2 + 1] << 8);
            rgba[col * 4] = (((pixel >> 10) & 31) * 255 + 15) / 31;
            rgba[col * 4 + 1] = (((pixel >> 5) & 31) * 255 + 15) / 31;
            rgba[col * 4 + 2] = ((pixel & 31) * 255 + 15) / 31;
            rgba[col * 4 + 3] = 255;
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y + row, width, 1,
                       GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, row_length);
    free(rgba);
}

static void
glamor_read_pixels(const struct glamor_format *f, int x, int y, int width,
                   int height, uint8_t *bits, uint32_t stride)
{
    if (f->rgba_readback && (f->depth == 8 || f->depth == 15 || f->depth == 16)) {
        uint8_t *rgba = malloc((size_t) width * 4);
        GLint row_length;
        int row, col;

        if (!rgba)
            FatalError("Xlorie: cannot allocate exact RGBA transfer row\n");
        glGetIntegerv(GL_PACK_ROW_LENGTH, &row_length);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        for (row = 0; row < height; row++) {
            uint8_t *dst = bits + (size_t) row * stride;
            glReadPixels(x, y + row, width, 1, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
            for (col = 0; col < width; col++) {
                const uint8_t *pixel = rgba + col * 4;
                if (f->depth == 8) {
                    dst[col] = pixel[0];
                } else {
                    unsigned red = (pixel[0] * 31 + 127) / 255;
                    unsigned blue = (pixel[2] * 31 + 127) / 255;
                    unsigned green = (pixel[1] * (f->depth == 16 ? 63 : 31) + 127) / 255;
                    unsigned value = f->depth == 16 ? (red << 11) | (green << 5) | blue
                                                    : (red << 10) | (green << 5) | blue;
                    dst[col * 2] = value & 255;
                    dst[col * 2 + 1] = value >> 8;
                }
            }
        }
        glPixelStorei(GL_PACK_ROW_LENGTH, row_length);
        free(rgba);
        return;
    }
    glReadPixels(x, y, width, height,
                 f->rgba_readback ? GL_RGBA : f->format, f->type, bits);
    if (f->rgba_readback) {
        for (int row = 0; row < height; row++) {
            uint8_t *pixel = bits + (size_t) row * stride;
            for (int col = 0; col < width; col++, pixel += 4) {
                uint8_t red = pixel[0];
                pixel[0] = pixel[2];
                pixel[2] = red;
            }
        }
    }
}


static int run_format(int depth,int width,int height,int rgba_readback) {
    struct glamor_format f={depth==8?GL_RED:GL_BGRA,GL_UNSIGNED_BYTE,rgba_readback,0,depth};
    const int bpp=depth==8?1:4;
    const int stride=((width*bpp+3)&~3)+12;
    const size_t bytes=(size_t)stride*height;
    uint8_t *src=malloc(bytes),*dst=malloc(bytes+32);
    GLuint tex,fb;
    memset(src,0xa7,bytes);memset(dst,0xd9,bytes+32);
    for(int y=0;y<height;y++)for(int x=0;x<width;x++)for(int c=0;c<bpp;c++)
        src[y*stride+x*bpp+c]=(x*47+y*101+c*73)&255;
    p_glGenTextures(1,&tex);p_glBindTexture(GL_TEXTURE_2D,tex);
    p_glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
    p_glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    if(depth==8)p_glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_SWIZZLE_A,GL_RED);
    p_glTexImage2D(GL_TEXTURE_2D,0,depth==8?GL_RED:GL_BGRA,width,height,0,f.format,f.type,NULL);
    check("texture allocation");
    p_glGenFramebuffers(1,&fb);p_glBindFramebuffer(GL_FRAMEBUFFER,fb);
    p_glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,tex,0);
    if(p_glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)FatalError("FBO incomplete\n");
    GLint rf,rt;p_glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT,&rf);p_glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE,&rt);
    if(width==1&&height==1)printf("depth=%d impl-read=0x%x/0x%x requested-native=0x%x/0x%x\n",depth,rf,rt,f.format,f.type);
    p_glPixelStorei(GL_UNPACK_ALIGNMENT,4);p_glPixelStorei(GL_UNPACK_ROW_LENGTH,stride/bpp);
    glamor_upload_pixels(&f,0,0,width,height,src,stride);check("actual upload helper");
    p_glPixelStorei(GL_UNPACK_ROW_LENGTH,0);
    p_glPixelStorei(GL_PACK_ALIGNMENT,4);p_glPixelStorei(GL_PACK_ROW_LENGTH,stride/bpp);
    glamor_read_pixels(&f,0,0,width,height,dst+16,stride);check("actual download helper");
    p_glPixelStorei(GL_PACK_ROW_LENGTH,0);
    size_t bad=0;
    for(int y=0;y<height;y++) {
        for(int x=0;x<width*bpp;x++)if(src[y*stride+x]!=dst[16+y*stride+x]) {
            if(!bad)printf("MISMATCH depth=%d mode=%s size=%dx%d xbyte=%d y=%d expected=%u actual=%u\n",depth,rgba_readback?"rgba-convert":"native",width,height,x,y,src[y*stride+x],dst[16+y*stride+x]);
            bad++;
        }
        for(int x=width*bpp;x<stride;x++)if(dst[16+y*stride+x]!=0xd9)FatalError("padding overwritten\n");
    }
    for(int x=0;x<16;x++)if(dst[x]!=0xd9||dst[16+bytes+x]!=0xd9)FatalError("guard overwritten\n");
    p_glDeleteFramebuffers(1,&fb);p_glDeleteTextures(1,&tex);free(src);free(dst);
    return bad!=0;
}
int main(int argc,char **argv) {
    if(argc!=3||strcmp(argv[1],"--mode")||strcmp(argv[2],"system")) {fprintf(stderr,"usage: %s --mode system (no fallback)\n",argv[0]);return 2;}
    void *egl_lib=dlopen("/system/lib64/libEGL.so",RTLD_NOW|RTLD_LOCAL);
    void *gl_lib=dlopen("/system/lib64/libGLESv2.so",RTLD_NOW|RTLD_LOCAL);
    if(!egl_lib||!gl_lib)FatalError("system EGL/GLES load failed: %s\n",dlerror());
    EGL_FUNCS(LOAD_EGL)
    GL_FUNCS(LOAD_GL)
    EGLDisplay display=p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if(display==EGL_NO_DISPLAY||!p_eglInitialize(display,NULL,NULL))FatalError("egl init failed 0x%x\n",p_eglGetError());
    if(!p_eglBindAPI(EGL_OPENGL_ES_API))FatalError("eglBindAPI failed\n");
    const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT|EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    const EGLint surfattrs[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    const EGLint ctxattrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    EGLConfig config;EGLint count;
    if(!p_eglChooseConfig(display,attrs,&config,1,&count)||count!=1)FatalError("egl config failed\n");
    EGLSurface surface=p_eglCreatePbufferSurface(display,config,surfattrs);
    EGLContext context=p_eglCreateContext(display,config,EGL_NO_CONTEXT,ctxattrs);
    if(surface==EGL_NO_SURFACE||context==EGL_NO_CONTEXT||!p_eglMakeCurrent(display,surface,surface,context))FatalError("egl context failed 0x%x\n",p_eglGetError());
    printf("mode=system EGL=/system/lib64/libEGL.so GLES=/system/lib64/libGLESv2.so renderer=%s version=%s\n",p_glGetString(GL_RENDERER),p_glGetString(GL_VERSION));
    GLint range[2],precision;p_glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER,GL_MEDIUM_FLOAT,range,&precision);printf("fragment mediump range=%d..%d precision=%d\n",range[0],range[1],precision);
    p_glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER,GL_HIGH_FLOAT,range,&precision);printf("fragment highp range=%d..%d precision=%d\n",range[0],range[1],precision);
    const int widths[]={1,2,3,4,5,7,15,31,63,257,701,1280};
    const int heights[]={1,3,17,61};
    const int depths[]={8,24,32};
    int failures=0,cases=0;
    for(unsigned d=0;d<sizeof(depths)/sizeof(*depths);d++)for(int rgba=0;rgba<2;rgba++)for(unsigned w=0;w<sizeof(widths)/sizeof(*widths);w++)for(unsigned h=0;h<sizeof(heights)/sizeof(*heights);h++) {failures+=run_format(depths[d],widths[w],heights[h],rgba);cases++;}
    printf("RESULT transfer cases=%d failed=%d; memory-picture unsized GL_RED allocation + actual transfer helpers; padding+guards checked\n",cases,failures);
    p_eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);p_eglDestroyContext(display,context);p_eglDestroySurface(display,surface);p_eglTerminate(display);
    return failures?1:0;
}
