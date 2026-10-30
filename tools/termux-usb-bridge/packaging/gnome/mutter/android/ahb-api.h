/* SPDX-License-Identifier: MIT
 * The package still targets API 24. Resolve AHB at runtime (Android >=26),
 * following the existing libandroid-stub platform namespace forwarding.
 */
#pragma once
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
static inline void *android_ahb_resolve (const char *name) {
  static void *library;
  if (!library) library = dlopen ("libandroid.so", RTLD_NOW | RTLD_LOCAL);
  void *function = library ? dlsym (library, name) : NULL;
  if (!function) { fprintf (stderr, "AHB runtime API missing: %s\n", name); abort (); }
  return function;
}
#define ANDROID_AHB_API(result, suffix, parameters, args) \
static inline result android_ahb_##suffix parameters { \
  typedef result (*Function) parameters; \
  static Function function; \
  if (!function) function = (Function) android_ahb_resolve ("AHardwareBuffer_" #suffix); \
  return function args; \
}
ANDROID_AHB_API(int, allocate, (const AHardwareBuffer_Desc *desc, AHardwareBuffer **out), (desc, out))
ANDROID_AHB_API(void, release, (AHardwareBuffer *buffer), (buffer))
ANDROID_AHB_API(void, describe, (const AHardwareBuffer *buffer, AHardwareBuffer_Desc *desc), (buffer, desc))
ANDROID_AHB_API(int, recvHandleFromUnixSocket, (int fd, AHardwareBuffer **out), (fd, out))
ANDROID_AHB_API(int, sendHandleToUnixSocket, (const AHardwareBuffer *buffer, int fd), (buffer, fd))
#undef ANDROID_AHB_API
