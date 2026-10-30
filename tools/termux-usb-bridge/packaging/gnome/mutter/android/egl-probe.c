/* SPDX-License-Identifier: GPL-2.0-or-later */
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
extern void *platform_dlopen(const char *,int);
int main(void) {
  char value[PROP_VALUE_MAX]={0};
  __system_property_get("ro.hardware.egl",value);
  printf("default ro.hardware.egl=%s\n",value);fflush(stdout);
  platform_dlopen("/data/data/com.termux/files/usr/lib/mutter-16/libvndksupport.so",RTLD_NOW|RTLD_LOCAL);
  void *handle=platform_dlopen("/system/lib64/libEGL.so",RTLD_NOW|RTLD_LOCAL);
  if(!handle){fprintf(stderr,"platform EGL load: %s\n",dlerror());return 1;}
  typedef int (*GetProperty)(const char *,char *);
  GetProperty get=(GetProperty)dlsym(handle,"__system_property_get");
  printf("property pointers default=%p platform=%p\n",(void *)__system_property_get,(void *)get);
  value[0]=0;if(get)get("ro.hardware.egl",value);
  printf("platform ro.hardware.egl=%s\n",value);fflush(stdout);
  EGLDisplay (*display)(EGLNativeDisplayType)=(void *)dlsym(handle,"eglGetDisplay");
  EGLBoolean (*init)(EGLDisplay,EGLint *,EGLint *)=(void *)dlsym(handle,"eglInitialize");
  EGLint (*error)(void)=(void *)dlsym(handle,"eglGetError");
  const char *(*query)(EGLDisplay,EGLint)=(void *)dlsym(handle,"eglQueryString");
  EGLDisplay d=display(EGL_DEFAULT_DISPLAY);EGLint major=0,minor=0;
  printf("display=%p\n",d);fflush(stdout);
  if(!init(d,&major,&minor)){fprintf(stderr,"EGL initialize failed: 0x%x\n",error());return 2;}
  printf("SYSTEM_EGL_PROBE_PASS version=%d.%d vendor=%s\n",major,minor,query(d,EGL_VENDOR));
  return 0;
}
