
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
#define GL_FUNCS(X) X(glGetString) X(glGetError) X(glGenTextures) X(glBindTexture) X(glTexParameteri) X(glTexImage2D) X(glTexSubImage2D) X(glPixelStorei) X(glGetIntegerv) X(glGenFramebuffers) X(glBindFramebuffer) X(glFramebufferTexture2D) X(glCheckFramebufferStatus) X(glReadPixels) X(glDeleteTextures) X(glDeleteFramebuffers) X(glClearColor) X(glClear) X(glFinish) X(glGetShaderPrecisionFormat) X(glCreateShader) X(glShaderSource) X(glCompileShader) X(glGetShaderiv) X(glGetShaderInfoLog) X(glCreateProgram) X(glAttachShader) X(glBindAttribLocation) X(glLinkProgram) X(glGetProgramiv) X(glGetProgramInfoLog) X(glUseProgram) X(glGetUniformLocation) X(glUniform1i) X(glUniform1f) X(glUniform2f) X(glUniform4f) X(glUniformMatrix3fv) X(glGenBuffers) X(glBindBuffer) X(glBufferData) X(glVertexAttribPointer) X(glEnableVertexAttribArray) X(glDisableVertexAttribArray) X(glDrawArrays) X(glViewport) X(glDisable) X(glIsEnabled)
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

#define GLAMOR_DEFAULT_PRECISION "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
#define gradient_fs_template	\
	    GLAMOR_DEFAULT_PRECISION\
	    "uniform mat3 transform_mat;\n"\
	    "uniform int repeat_type;\n"\
	    "uniform int hor_ver;\n"\
	    "uniform float pt_slope;\n"\
	    "uniform float cos_val;\n"\
	    "uniform float p1_distance;\n"\
	    "uniform float pt_distance;\n"\
	    "varying vec2 source_texture;\n"\
	    "\n"\
	    "vec4 get_color(float stop_len);\n"\
	    "\n"\
	    "float get_stop_len()\n"\
	    "{\n"\
	    "    vec3 tmp = vec3(source_texture.x, source_texture.y, 1.0);\n"\
	    "    float distance;\n"\
	    "    float _p1_distance;\n"\
	    "    float _pt_distance;\n"\
	    "    float y_dist;\n"\
	    "    vec3 source_texture_trans = transform_mat * tmp;\n"\
	    "    \n"\
	    "    if(hor_ver == 0) { \n" /*Normal case.*/\
	    "        y_dist = source_texture_trans.y - source_texture_trans.x*pt_slope;\n"\
	    "        distance = y_dist * cos_val;\n"\
	    "        _p1_distance = p1_distance * source_texture_trans.z;\n"\
	    "        _pt_distance = pt_distance * source_texture_trans.z;\n"\
	    "        \n"\
	    "    } else if (hor_ver == 1) {\n"/*horizontal case.*/\
	    "        distance = source_texture_trans.x;\n"\
	    "        _p1_distance = p1_distance * source_texture_trans.z;\n"\
	    "        _pt_distance = pt_distance * source_texture_trans.z;\n"\
	    "    } \n"\
	    "    \n"\
	    "    distance = (distance - _p1_distance) / _pt_distance;\n"\
	    "    \n"\
	    "    if(repeat_type == %d){\n" /* repeat normal*/\
	    "        distance = fract(distance);\n"\
	    "    }\n"\
	    "    \n"\
	    "    if(repeat_type == %d) {\n" /* repeat reflect*/\
	    "        distance = abs(fract(distance * 0.5 + 0.5) * 2.0 - 1.0);\n"\
	    "    }\n"\
	    "    \n"\
	    "    return distance;\n"\
	    "}\n"\
	    "\n"\
	    "void main()\n"\
	    "{\n"\
	    "    float stop_len = get_stop_len();\n"\
	    "    gl_FragColor = get_color(stop_len);\n"\
	    "}\n"\
	    "\n"\
            "%s" /* fs_getcolor_source */
#define gradient_radial_fs_template\
	    GLAMOR_DEFAULT_PRECISION\
	    "uniform mat3 transform_mat;\n"\
	    "uniform int repeat_type;\n"\
	    "uniform float A_value;\n"\
	    "uniform vec2 c1;\n"\
	    "uniform float r1;\n"\
	    "uniform vec2 c2;\n"\
	    "uniform float r2;\n"\
	    "varying vec2 source_texture;\n"\
	    "\n"\
	    "vec4 get_color(float stop_len);\n"\
	    "\n"\
	    "int t_invalid;\n"\
	    "\n"\
	    "float get_stop_len()\n"\
	    "{\n"\
	    "    float t = 0.0;\n"\
	    "    float sqrt_value;\n"\
	    "    t_invalid = 0;\n"\
	    "    \n"\
	    "    vec3 tmp = vec3(source_texture.x, source_texture.y, 1.0);\n"\
	    "    vec3 source_texture_trans = transform_mat * tmp;\n"\
	    "    source_texture_trans.xy = source_texture_trans.xy/source_texture_trans.z;\n"\
	    "    float B_value = (source_texture_trans.x - c1.x) * (c2.x - c1.x)\n"\
	    "                     + (source_texture_trans.y - c1.y) * (c2.y - c1.y)\n"\
	    "                     + r1 * (r2 - r1);\n"\
	    "    float C_value = (source_texture_trans.x - c1.x) * (source_texture_trans.x - c1.x)\n"\
	    "                     + (source_texture_trans.y - c1.y) * (source_texture_trans.y - c1.y)\n"\
	    "                     - r1*r1;\n"\
	    "    if(abs(A_value) < 0.00001) {\n"\
	    "        if(B_value == 0.0) {\n"\
	    "            t_invalid = 1;\n"\
	    "            return t;\n"\
	    "        }\n"\
	    "        t = 0.5 * C_value / B_value;"\
	    "    } else {\n"\
	    "        sqrt_value = B_value * B_value - A_value * C_value;\n"\
	    "        if(sqrt_value < 0.0) {\n"\
	    "            t_invalid = 1;\n"\
	    "            return t;\n"\
	    "        }\n"\
	    "        sqrt_value = sqrt(sqrt_value);\n"\
	    "        t = (B_value + sqrt_value) / A_value;\n"\
	    "    }\n"\
	    "    if(repeat_type == %d) {\n" /* RepeatNone case. */\
	    "        if((t <= 0.0) || (t > 1.0))\n"\
	    /*           try another if first one invalid*/\
	    "            t = (B_value - sqrt_value) / A_value;\n"\
	    "        \n"\
	    "        if((t <= 0.0) || (t > 1.0)) {\n" /*still invalid, return.*/\
	    "            t_invalid = 1;\n"\
	    "            return t;\n"\
	    "        }\n"\
	    "    } else {\n"\
	    "        if(t * (r2 - r1) <= -1.0 * r1)\n"\
	    /*           try another if first one invalid*/\
	    "            t = (B_value - sqrt_value) / A_value;\n"\
	    "        \n"\
	    "        if(t * (r2 -r1) <= -1.0 * r1) {\n" /*still invalid, return.*/\
	    "            t_invalid = 1;\n"\
	    "            return t;\n"\
	    "        }\n"\
	    "    }\n"\
	    "    \n"\
	    "    if(repeat_type == %d){\n" /* repeat normal*/\
	    "        t = fract(t);\n"\
	    "    }\n"\
	    "    \n"\
	    "    if(repeat_type == %d) {\n" /* repeat reflect*/\
	    "        t = abs(fract(t * 0.5 + 0.5) * 2.0 - 1.0);\n"\
	    "    }\n"\
	    "    \n"\
	    "    return t;\n"\
	    "}\n"\
	    "\n"\
	    "void main()\n"\
	    "{\n"\
	    "    float stop_len = get_stop_len();\n"\
	    "    if(t_invalid == 1) {\n"\
	    "        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);\n"\
	    "    } else {\n"\
	    "        gl_FragColor = get_color(stop_len);\n"\
	    "    }\n"\
	    "}\n"\
	    "\n"\
            "%s\n" /* fs_getcolor_source */
    const char *gradient_fs_getcolor_no_array =
        GLAMOR_DEFAULT_PRECISION
        "uniform int n_stop;\n"
        "uniform float stop0;\n"
        "uniform float stop1;\n"
        "uniform float stop2;\n"
        "uniform float stop3;\n"
        "uniform float stop4;\n"
        "uniform float stop5;\n"
        "uniform float stop6;\n"
        "uniform float stop7;\n"
        "uniform vec4 stop_color0;\n"
        "uniform vec4 stop_color1;\n"
        "uniform vec4 stop_color2;\n"
        "uniform vec4 stop_color3;\n"
        "uniform vec4 stop_color4;\n"
        "uniform vec4 stop_color5;\n"
        "uniform vec4 stop_color6;\n"
        "uniform vec4 stop_color7;\n"
        "\n"
        "vec4 get_color(float stop_len)\n"
        "{\n"
        "    vec4 stop_color_before;\n"
        "    vec4 stop_color_after;\n"
        "    vec4 gradient_color;\n"
        "    float stop_before;\n"
        "    float stop_delta;\n"
        "    float percentage; \n"
        "    \n"
        "    if((stop_len < stop0) && (n_stop >= 1)) {\n"
        "        stop_color_before = vec4(0.0, 0.0, 0.0, 0.0);\n"
        "        stop_delta = 0.0;\n"
        "    } else if((stop_len < stop1) && (n_stop >= 2)) {\n"
        "        stop_color_before = stop_color0;\n"
        "        stop_color_after = stop_color1;\n"
        "        stop_before = stop0;\n"
        "        stop_delta = stop1 - stop0;\n"
        "    } else if((stop_len < stop2) && (n_stop >= 3)) {\n"
        "        stop_color_before = stop_color1;\n"
        "        stop_color_after = stop_color2;\n"
        "        stop_before = stop1;\n"
        "        stop_delta = stop2 - stop1;\n"
        "    } else if((stop_len < stop3) && (n_stop >= 4)){\n"
        "        stop_color_before = stop_color2;\n"
        "        stop_color_after = stop_color3;\n"
        "        stop_before = stop2;\n"
        "        stop_delta = stop3 - stop2;\n"
        "    } else if((stop_len < stop4) && (n_stop >= 5)){\n"
        "        stop_color_before = stop_color3;\n"
        "        stop_color_after = stop_color4;\n"
        "        stop_before = stop3;\n"
        "        stop_delta = stop4 - stop3;\n"
        "    } else if((stop_len < stop5) && (n_stop >= 6)){\n"
        "        stop_color_before = stop_color4;\n"
        "        stop_color_after = stop_color5;\n"
        "        stop_before = stop4;\n"
        "        stop_delta = stop5 - stop4;\n"
        "    } else if((stop_len < stop6) && (n_stop >= 7)){\n"
        "        stop_color_before = stop_color5;\n"
        "        stop_color_after = stop_color6;\n"
        "        stop_before = stop5;\n"
        "        stop_delta = stop6 - stop5;\n"
        "    } else if((stop_len < stop7) && (n_stop >= 8)){\n"
        "        stop_color_before = stop_color6;\n"
        "        stop_color_after = stop_color7;\n"
        "        stop_before = stop6;\n"
        "        stop_delta = stop7 - stop6;\n"
        "    } else {\n"
        "        stop_color_before = vec4(0.0, 0.0, 0.0, 0.0);\n"
        "        stop_delta = 0.0;\n"
        "    }\n"
        "    if(stop_delta > 2.0)\n"
        "        percentage = 0.0;\n" //For comply with pixman, walker->stepper overflow.
        "    else if(stop_delta < 0.000001)\n"
        "        percentage = 0.0;\n"
        "    else\n"
        "        percentage = (stop_len - stop_before)/stop_delta;\n"
        "    \n"
        "    gradient_color = stop_color_before;\n"
        "    if(percentage != 0.0)\n"
        "        gradient_color += (stop_color_after - gradient_color)*percentage;\n"
        "    return vec4(gradient_color.rgb * gradient_color.a, gradient_color.a);\n"
        "}\n";

    const char *gradient_vs =
        GLAMOR_DEFAULT_PRECISION
        "attribute vec4 v_position;\n"
        "attribute vec4 v_texcoord;\n"
        "varying vec2 source_texture;\n"
        "\n"
        "void main()\n"
        "{\n"
        "    gl_Position = v_position;\n"
        "    source_texture = v_texcoord.xy;\n"
        "}\n";


static char *select_precision(const char *s,int highp) {
    char *result=strdup(s);
    if(highp) {
        char *found=result;
        while((found=strstr(found,"mediump"))) {
            size_t tail=strlen(found+7)+1;
            memcpy(found,"highp",5);memmove(found+5,found+7,tail);found+=5;
        }
    }
    return result;
}
static GLuint compile(GLenum type,const char *text,int highp) {
    char *selected=select_precision(text,highp);
    GLuint shader=p_glCreateShader(type);
    p_glShaderSource(shader,1,(const GLchar **)&selected,NULL);p_glCompileShader(shader);
    GLint ok;p_glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
    if(!ok) {char log[4096];p_glGetShaderInfoLog(shader,sizeof(log),NULL,log);FatalError("shader compile failed %s\n",log);}
    free(selected);return shader;
}
static void uf(GLuint prog,const char *name,float value) {p_glUniform1f(p_glGetUniformLocation(prog,name),value);}
static void ui(GLuint prog,const char *name,int value) {p_glUniform1i(p_glGetUniformLocation(prog,name),value);}
static int run_gradient(int radial,int highp,int translated,const char *directory) {
    const int width=1280,height=64;
    const float shift=translated?16.0f:0.0f;
    char *fragment;
    if(radial)asprintf(&fragment,gradient_radial_fs_template,0,1,3,gradient_fs_getcolor_no_array);
    else asprintf(&fragment,gradient_fs_template,1,3,gradient_fs_getcolor_no_array);
    GLuint program=p_glCreateProgram();
    p_glAttachShader(program,compile(GL_VERTEX_SHADER,gradient_vs,highp));
    p_glAttachShader(program,compile(GL_FRAGMENT_SHADER,fragment,highp));free(fragment);
    p_glBindAttribLocation(program,0,"v_position");p_glBindAttribLocation(program,1,"v_texcoord");p_glLinkProgram(program);
    GLint ok;p_glGetProgramiv(program,GL_LINK_STATUS,&ok);
    if(!ok){char log[4096];p_glGetProgramInfoLog(program,sizeof(log),NULL,log);FatalError("program link failed %s\n",log);}
    p_glUseProgram(program);
    GLuint texture,fb,vbo;
    p_glGenTextures(1,&texture);p_glBindTexture(GL_TEXTURE_2D,texture);
    p_glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);p_glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    p_glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    p_glGenFramebuffers(1,&fb);p_glBindFramebuffer(GL_FRAMEBUFFER,fb);p_glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
    if(p_glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)FatalError("gradient FBO incomplete\n");
    p_glViewport(0,0,width,height);p_glDisable(GL_BLEND);p_glDisable(GL_SCISSOR_TEST);
    const GLfloat vertices[]={-1,-1,0,shift,1,-1,1,shift,-1,1,0,shift+1,1,1,1,shift+1};
    p_glGenBuffers(1,&vbo);p_glBindBuffer(GL_ARRAY_BUFFER,vbo);p_glBufferData(GL_ARRAY_BUFFER,sizeof(vertices),vertices,GL_STATIC_DRAW);
    p_glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(GLfloat),(void*)0);p_glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(GLfloat),(void*)(2*sizeof(GLfloat)));
    p_glEnableVertexAttribArray(0);p_glEnableVertexAttribArray(1);
    const GLfloat matrix[]={1,0,0,0,1,0,0,0,1};
    p_glUniformMatrix3fv(p_glGetUniformLocation(program,"transform_mat"),1,GL_FALSE,matrix);
    ui(program,"n_stop",4);ui(program,"repeat_type",2);ui(program,"hor_ver",0);
    uf(program,"pt_slope",0);uf(program,"cos_val",1);uf(program,"p1_distance",shift);uf(program,"pt_distance",1);
    if(radial) {p_glUniform2f(p_glGetUniformLocation(program,"c1"),0.5f,shift+0.5f);p_glUniform2f(p_glGetUniformLocation(program,"c2"),0.5f,shift+0.5f);uf(program,"r1",0);uf(program,"r2",0.5);uf(program,"A_value",-0.25);}
    for(int i=0;i<8;i++) {
        char name[32];snprintf(name,sizeof(name),"stop%d",i);uf(program,name,i==0?-2147483647.0f:i==1?0:i==2?1:2147483647.0f);
        snprintf(name,sizeof(name),"stop_color%d",i);
        float color=i<2?246.0f/255:214.0f/255;
        p_glUniform4f(p_glGetUniformLocation(program,name),color,color,color,1);
    }
    check("gradient setup");
    uint8_t *pixels=malloc((size_t)width*height*4),*again=malloc((size_t)width*height*4);
    p_glDrawArrays(GL_TRIANGLE_STRIP,0,4);check("gradient render");
    p_glPixelStorei(GL_PACK_ROW_LENGTH,0);p_glPixelStorei(GL_PACK_ALIGNMENT,4);p_glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);check("gradient readback");
    p_glDrawArrays(GL_TRIANGLE_STRIP,0,4);p_glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,again);check("gradient repeat");
    size_t changed=0,large_row_spread=0;int max_row_spread=0,max_alpha_delta=0;
    for(size_t i=0;i<(size_t)width*height*4;i++)changed+=pixels[i]!=again[i];
    for(int y=0;y<height;y++) {
        int lo=255,hi=0;
        for(int x=0;x<width;x++){uint8_t *p=pixels+((size_t)y*width+x)*4;if(p[0]<lo)lo=p[0];if(p[0]>hi)hi=p[0];int ad=abs(255-p[3]);if(ad>max_alpha_delta)max_alpha_delta=ad;}
        if(hi-lo>max_row_spread)max_row_spread=hi-lo;if(hi-lo>2)large_row_spread++;
    }
    char *filename;asprintf(&filename,"%s/gradient-%s-%s-shift%d.rgba",directory,radial?"radial":"linear",highp?"highp":"mediump",translated);
    FILE *file=fopen(filename,"wb");if(!file)FatalError("cannot open %s\n",filename);fwrite(pixels,4,(size_t)width*height,file);fclose(file);
    printf("gradient=%s precision=%s shift=%g repeated_changed_bytes=%zu max_row_spread=%d rows_spread_gt2=%zu max_alpha_delta=%d file=%s\n",radial?"radial":"linear",highp?"highp":"mediump",shift,changed,max_row_spread,large_row_spread,max_alpha_delta,filename);
    free(filename);free(pixels);free(again);
    return changed||(!radial&&max_row_spread>2)||max_alpha_delta;
}
int main(int argc,char **argv) {
    if(argc!=6||strcmp(argv[1],"--mode")||strcmp(argv[2],"system")||strcmp(argv[3],"--precision")||(strcmp(argv[4],"mediump")&&strcmp(argv[4],"highp"))) {fprintf(stderr,"usage: %s --mode system --precision mediump|highp OUTPUT_DIRECTORY\n",argv[0]);return 2;}
    const int highp=!strcmp(argv[4],"highp");
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

    printf("dither-enabled=%d\n",p_glIsEnabled(GL_DITHER));
    int failures=0;
    for(int radial=0;radial<2;radial++)for(int translated=0;translated<2;translated++)failures+=run_gradient(radial,highp,translated,argv[5]);
    printf("RESULT gradient precision=%s anomalous_cases=%d (diagnostic, not UI validation)\n",argv[4],failures);
    return 0;
}
