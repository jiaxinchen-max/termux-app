/* SPDX-License-Identifier: LGPL-3.0-only
 * C adapter for the KWin system-egl forwarding API. Loaded only by the
 * compositor in explicitly selected system mode. No global library replacement.
 */
#include <EGL/egl.h>
#include <dlfcn.h>
#include <glib.h>
extern void *platform_dlopen(const char *, int);
static void *system_egl(void) {
  static gsize library;
  if(g_once_init_enter(&library)) {
#if defined(__LP64__)
    const char *path="/system/lib64/libEGL.so";
#else
    const char *path="/system/lib/libEGL.so";
#endif
    void *drivers=platform_dlopen("/data/data/com.termux/files/usr/lib/mutter-16/libvndksupport.so",RTLD_NOW|RTLD_LOCAL);
    if(!drivers)g_error("Mutter Android cannot load private driver namespace: %s",dlerror());
    void *handle=platform_dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(!handle)g_error("Mutter Android cannot open system EGL: %s",dlerror());
    g_once_init_leave(&library,(gsize)handle);
  }
  return (void *)library;
}
static void *resolve(const char *name) {
  void *address=dlsym(system_egl(),name);
  if(!address)g_error("Mutter Android missing system EGL function %s: %s",name,dlerror());
  return address;
}
__attribute__((visibility("default"))) const char *mutter_android_system_egl_dispatch(void) {
  system_egl();return "android-system-egl-v1";
}
#define EGL_FORWARD(result,name,parameters,arguments) \
__attribute__((visibility("default"))) EGLAPI result EGLAPIENTRY name parameters { \
  typedef result (EGLAPIENTRYP Function) parameters; \
  static gsize address; \
  if(g_once_init_enter(&address))g_once_init_leave(&address,(gsize)resolve(#name)); \
  return ((Function)address) arguments; \
}
EGL_FORWARD(EGLBoolean, eglBindAPI, (EGLenum api), (api))
EGL_FORWARD(EGLBoolean, eglBindTexImage, (EGLDisplay d, EGLSurface s, EGLint b), (d,s,b))
EGL_FORWARD(EGLBoolean, eglChooseConfig, (EGLDisplay d, const EGLint *a, EGLConfig *c, EGLint n, EGLint *r), (d,a,c,n,r))
EGL_FORWARD(EGLBoolean, eglCopyBuffers, (EGLDisplay d, EGLSurface s, EGLNativePixmapType p), (d,s,p))
EGL_FORWARD(EGLContext, eglCreateContext, (EGLDisplay d, EGLConfig c, EGLContext s, const EGLint *a), (d,c,s,a))
EGL_FORWARD(EGLSurface, eglCreatePbufferFromClientBuffer, (EGLDisplay d, EGLenum t, EGLClientBuffer b, EGLConfig c, const EGLint *a), (d,t,b,c,a))
EGL_FORWARD(EGLSurface, eglCreatePbufferSurface, (EGLDisplay d, EGLConfig c, const EGLint *a), (d,c,a))
EGL_FORWARD(EGLSurface, eglCreatePixmapSurface, (EGLDisplay d, EGLConfig c, EGLNativePixmapType p, const EGLint *a), (d,c,p,a))
EGL_FORWARD(EGLSurface, eglCreateWindowSurface, (EGLDisplay d, EGLConfig c, EGLNativeWindowType w, const EGLint *a), (d,c,w,a))
EGL_FORWARD(EGLBoolean, eglDestroyContext, (EGLDisplay d, EGLContext c), (d,c))
EGL_FORWARD(EGLBoolean, eglDestroySurface, (EGLDisplay d, EGLSurface s), (d,s))
EGL_FORWARD(EGLBoolean, eglGetConfigAttrib, (EGLDisplay d, EGLConfig c, EGLint a, EGLint *v), (d,c,a,v))
EGL_FORWARD(EGLBoolean, eglGetConfigs, (EGLDisplay d, EGLConfig *c, EGLint n, EGLint *r), (d,c,n,r))
EGL_FORWARD(EGLContext, eglGetCurrentContext, (void), ())
EGL_FORWARD(EGLDisplay, eglGetCurrentDisplay, (void), ())
EGL_FORWARD(EGLSurface, eglGetCurrentSurface, (EGLint r), (r))
EGL_FORWARD(EGLDisplay, eglGetDisplay, (EGLNativeDisplayType d), (d))
EGL_FORWARD(EGLint, eglGetError, (void), ())
EGL_FORWARD(__eglMustCastToProperFunctionPointerType, eglGetProcAddress, (const char *n), (n))
EGL_FORWARD(EGLBoolean, eglInitialize, (EGLDisplay d, EGLint *a, EGLint *b), (d,a,b))
EGL_FORWARD(EGLBoolean, eglMakeCurrent, (EGLDisplay d, EGLSurface a, EGLSurface b, EGLContext c), (d,a,b,c))
EGL_FORWARD(EGLenum, eglQueryAPI, (void), ())
EGL_FORWARD(EGLBoolean, eglQueryContext, (EGLDisplay d, EGLContext c, EGLint a, EGLint *v), (d,c,a,v))
EGL_FORWARD(const char *, eglQueryString, (EGLDisplay d, EGLint n), (d,n))
EGL_FORWARD(EGLBoolean, eglQuerySurface, (EGLDisplay d, EGLSurface s, EGLint a, EGLint *v), (d,s,a,v))
EGL_FORWARD(EGLBoolean, eglReleaseTexImage, (EGLDisplay d, EGLSurface s, EGLint b), (d,s,b))
EGL_FORWARD(EGLBoolean, eglReleaseThread, (void), ())
EGL_FORWARD(EGLBoolean, eglSurfaceAttrib, (EGLDisplay d, EGLSurface s, EGLint a, EGLint v), (d,s,a,v))
EGL_FORWARD(EGLBoolean, eglSwapBuffers, (EGLDisplay d, EGLSurface s), (d,s))
EGL_FORWARD(EGLBoolean, eglSwapInterval, (EGLDisplay d, EGLint i), (d,i))
EGL_FORWARD(EGLBoolean, eglTerminate, (EGLDisplay d), (d))
EGL_FORWARD(EGLBoolean, eglWaitClient, (void), ())
EGL_FORWARD(EGLBoolean, eglWaitGL, (void), ())
EGL_FORWARD(EGLBoolean, eglWaitNative, (EGLint e), (e))
EGL_FORWARD(EGLSync, eglCreateSync, (EGLDisplay d, EGLenum t, const EGLAttrib *a), (d,t,a))
EGL_FORWARD(EGLBoolean, eglDestroySync, (EGLDisplay d, EGLSync s), (d,s))
EGL_FORWARD(EGLint, eglClientWaitSync, (EGLDisplay d, EGLSync s, EGLint f, EGLTime t), (d,s,f,t))
EGL_FORWARD(EGLBoolean, eglGetSyncAttrib, (EGLDisplay d, EGLSync s, EGLint a, EGLAttrib *v), (d,s,a,v))
EGL_FORWARD(EGLImage, eglCreateImage, (EGLDisplay d, EGLContext c, EGLenum t, EGLClientBuffer b, const EGLAttrib *a), (d,c,t,b,a))
EGL_FORWARD(EGLBoolean, eglDestroyImage, (EGLDisplay d, EGLImage i), (d,i))
EGL_FORWARD(EGLDisplay, eglGetPlatformDisplay, (EGLenum p, void *d, const EGLAttrib *a), (p,d,a))
EGL_FORWARD(EGLSurface, eglCreatePlatformWindowSurface, (EGLDisplay d, EGLConfig c, void *w, const EGLAttrib *a), (d,c,w,a))
EGL_FORWARD(EGLSurface, eglCreatePlatformPixmapSurface, (EGLDisplay d, EGLConfig c, void *p, const EGLAttrib *a), (d,c,p,a))
EGL_FORWARD(EGLBoolean, eglWaitSync, (EGLDisplay d, EGLSync s, EGLint f), (d,s,f))
