#ifndef LORIE_VORTEK_BACKEND_H
#define LORIE_VORTEK_BACKEND_H

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct VkContext VkContext;

bool vortekBackendInitialize(JNIEnv *env);
bool vortekBackendIsReady(void);
VkContext *vortekBackendCreateContext(int clientFd);
void vortekBackendDestroyContext(VkContext *context);
bool vortekBackendHandleExtraData(VkContext *context, uint16_t requestId, int requestLength);

#endif
