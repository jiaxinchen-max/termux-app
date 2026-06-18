#pragma once

#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include "lorie.h"

void waylandRenderInit(JavaVM *vm);
bool waylandRenderConnected(void);
int waylandRenderKeycodeFormat(void);
bool waylandRenderSendEvent(const lorieEvent *event, const void *payload, size_t payloadSize);
bool waylandRenderKillExternalServer(int signal);
