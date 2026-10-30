/* SPDX-License-Identifier: LGPL-3.0-only
 * Private EGL driver loader. The system sphal namespace links back to Termux's
 * default namespace, where NDK wrappers cannot satisfy platform symbol versions.
 * Load drivers in a child sharing the platform namespace's existing libraries,
 * so EGL, AHardwareBuffer and the driver keep one libbinder ProcessState.
 * Installed privately and loaded only into libandroid-stub's platform namespace.
 */
#include <android/dlext.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __LP64__
#define DRIVER_PATHS "/vendor/lib64:/vendor/lib64/egl:/vendor/lib64/hw:/odm/lib64:/system/lib64:/system_ext/lib64"
#else
#define DRIVER_PATHS "/vendor/lib:/vendor/lib/egl:/vendor/lib/hw:/odm/lib:/system/lib:/system_ext/lib"
#endif
static pthread_once_t once = PTHREAD_ONCE_INIT;
static struct android_namespace_t *driver_ns;
static void initialize(void) {
  typedef struct android_namespace_t *(*Create)(const char *, const char *, const char *,
      uint64_t, const char *, struct android_namespace_t *, const void *);
  Create create = (Create)dlsym(RTLD_DEFAULT, "__loader_android_create_namespace");
  if (!create) return;
  /* SHARED copies already loaded platform libraries, not the Termux wrappers.
   * A regular namespace retains Android SELinux access checks. */
  driver_ns = create("mutter-android-drivers", DRIVER_PATHS, DRIVER_PATHS,
                     2, NULL, NULL, (const void *)&initialize);
}
__attribute__((visibility("default"))) bool android_is_in_vendor_process(void) {
  return false;
}
__attribute__((visibility("default"))) void *android_load_sphal_library(const char *name, int flags) {
  pthread_once(&once, initialize);
  if (!driver_ns) return NULL;
  android_dlextinfo info = { .flags = ANDROID_DLEXT_USE_NAMESPACE, .library_namespace = driver_ns };
  return android_dlopen_ext(name, flags, &info);
}
__attribute__((visibility("default"))) int android_unload_sphal_library(void *handle) {
  return dlclose(handle);
}
