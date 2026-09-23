#ifndef LORIE_VULKAN_BROKER_H
#define LORIE_VULKAN_BROKER_H

#include <stdbool.h>
#include <stdint.h>

#include <android/hardware_buffer.h>

/* The protocol is intentionally small until the GLIBC Vulkan ICD is introduced. */
#define LORIE_VULKAN_BROKER_MAGIC 0x564B4252u /* "VKBR" */
#define LORIE_VULKAN_BROKER_PROTOCOL_VERSION 1u

/* Vulkan device extensions required for importing a Lorie AHardwareBuffer. */
#define LORIE_VULKAN_BROKER_CAPABILITY_ANDROID_HARDWARE_BUFFER (1u << 0)

bool vulkanBrokerStart(const char *socketPath);
void vulkanBrokerStop(void);
bool vulkanBrokerIsRunning(void);
bool vulkanBrokerAcquireWindowBuffer(uint32_t windowId, bool bgra8888,
                                     AHardwareBuffer **outBuffer,
                                     uint32_t *outWidth, uint32_t *outHeight);
bool vulkanBrokerGetWindowSize(uint32_t windowId, uint32_t *outWidth, uint32_t *outHeight);
/* Releases the reference returned by vulkanBrokerAcquireWindowBuffer(). */
void vulkanBrokerReleaseWindowBuffer(AHardwareBuffer *buffer);
/* Presents the independently allocated swapchain image identified by buffer. */
void vulkanBrokerNotifyWindowContent(uint32_t windowId, AHardwareBuffer *buffer);

#endif
