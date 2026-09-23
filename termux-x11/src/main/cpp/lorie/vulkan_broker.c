#include "vulkan_broker.h"
#include "lorie.h"
#include "vortek_backend.h"
#include "dix.h"

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#define BROKER_LOG(priority, ...) \
    __android_log_print(ANDROID_LOG_##priority, "VulkanBroker", __VA_ARGS__)

#define BROKER_DEVICE_NAME_SIZE 256
#define ANDROID_HARDWARE_BUFFER_EXTENSION "VK_ANDROID_external_memory_android_hardware_buffer"

extern ScreenPtr pScreenPtr;

struct __attribute__((packed)) VulkanBrokerHello {
    uint32_t magic;
    uint16_t protocolVersion;
    uint16_t size;
};

struct __attribute__((packed)) VulkanBrokerCapabilities {
    uint32_t magic;
    uint16_t protocolVersion;
    uint16_t size;
    int32_t status;
    uint32_t apiVersion;
    uint32_t vendorId;
    uint32_t deviceId;
    uint32_t deviceType;
    uint32_t capabilities;
    char deviceName[BROKER_DEVICE_NAME_SIZE];
};

struct VulkanBackend {
    void *library;
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue graphicsQueue;
    uint32_t graphicsQueueFamily;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr;
    PFN_vkDestroyInstance destroyInstance;
    PFN_vkDestroyDevice destroyDevice;
    struct VulkanBrokerCapabilities capabilities;
};

struct VulkanBrokerState {
    pthread_mutex_t lock;
    pthread_t thread;
    int listener;
    bool running;
    bool stopping;
    char socketPath[sizeof(((struct sockaddr_un *) 0)->sun_path)];
    struct VulkanBackend backend;
    struct BrokerWindowBuffer *windowBuffers;
};

/*
 * A Vortek swapchain image owns one AHardwareBuffer reference.  The matching
 * LorieBuffer stays registered until that image is destroyed, so the renderer
 * can consume the queued copy without falling back to the root pixmap.
 */
struct BrokerWindowBuffer {
    XID windowId;
    AHardwareBuffer *hardwareBuffer;
    LorieVulkanWindowBuffer *lorieBuffer;
    uint32_t presentRefs;
    bool released;
    struct BrokerWindowBuffer *next;
};

struct WindowBufferRequest {
    pthread_mutex_t lock;
    pthread_cond_t done;
    XID windowId;
    bool bgra8888;
    bool complete;
    bool success;
    AHardwareBuffer *buffer;
    LorieVulkanWindowBuffer *windowBuffer;
    struct BrokerWindowBuffer *record;
    uint32_t width;
    uint32_t height;
};

struct WindowContentRequest {
    XID windowId;
    struct BrokerWindowBuffer *record;
};

struct WindowBufferDestroyRequest {
    LorieVulkanWindowBuffer *windowBuffer;
};

struct WindowSizeRequest {
    pthread_mutex_t lock;
    pthread_cond_t done;
    XID windowId;
    bool complete;
    bool success;
    uint32_t width;
    uint32_t height;
};

static struct VulkanBrokerState broker = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
    .listener = -1,
};

static bool writeAll(int fd, const void *data, size_t length) {
    const uint8_t *cursor = data;
    while (length > 0) {
        ssize_t written = send(fd, cursor, length, MSG_NOSIGNAL);
        if (written < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (written == 0) return false;
        cursor += written;
        length -= (size_t) written;
    }
    return true;
}

static bool readAll(int fd, void *data, size_t length) {
    uint8_t *cursor = data;
    while (length > 0) {
        ssize_t readLength = recv(fd, cursor, length, 0);
        if (readLength < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (readLength == 0) return false;
        cursor += readLength;
        length -= (size_t) readLength;
    }
    return true;
}

static bool ensureSocketParentDirectory(const char *socketPath) {
    char directory[sizeof(((struct sockaddr_un *) 0)->sun_path)];
    snprintf(directory, sizeof(directory), "%s", socketPath);
    char *lastSlash = strrchr(directory, '/');
    if (!lastSlash || lastSlash == directory) return lastSlash != NULL;
    *lastSlash = '\0';

    for (char *slash = directory + 1; *slash; ++slash) {
        if (*slash != '/') continue;
        *slash = '\0';
        if (mkdir(directory, S_IRWXU) != 0 && errno != EEXIST) return false;
        *slash = '/';
    }
    return mkdir(directory, S_IRWXU) == 0 || errno == EEXIST;
}

static Bool acquireWindowBufferOnXorgThread(__unused ClientPtr client, void *closure) {
    struct WindowBufferRequest *request = closure;
    AHardwareBuffer *buffer = NULL;
    uint32_t width = 0;
    uint32_t height = 0;
    LorieVulkanWindowBuffer *windowBuffer = NULL;
    bool success = lorieVulkanBrokerCreateWindowBuffer(request->windowId, request->bgra8888,
                                                       &windowBuffer, &buffer, &width, &height);

    pthread_mutex_lock(&request->lock);
    request->buffer = buffer;
    request->windowBuffer = windowBuffer;
    if (success && request->record) {
        request->record->windowId = request->windowId;
        request->record->hardwareBuffer = buffer;
        request->record->lorieBuffer = windowBuffer;
    }
    request->width = width;
    request->height = height;
    request->success = success;
    request->complete = true;
    pthread_cond_signal(&request->done);
    pthread_mutex_unlock(&request->lock);
    return TRUE;
}

static bool acquireWindowBuffer(XID windowId, bool bgra8888, AHardwareBuffer **outBuffer,
                                uint32_t *outWidth, uint32_t *outHeight) {
    struct BrokerWindowBuffer *record = calloc(1, sizeof(*record));
    struct WindowBufferRequest request = {
        .lock = PTHREAD_MUTEX_INITIALIZER,
        .done = PTHREAD_COND_INITIALIZER,
        .windowId = windowId,
        .bgra8888 = bgra8888,
        .record = record,
    };
    if (!record || !outBuffer || !outWidth || !outHeight || !pScreenPtr) {
        free(record);
        return false;
    }
    *outBuffer = NULL;
    *outWidth = *outHeight = 0;
    pthread_mutex_lock(&request.lock);
    QueueWorkProc(acquireWindowBufferOnXorgThread, NULL, &request);
    lorieWakeServer();
    while (!request.complete)
        pthread_cond_wait(&request.done, &request.lock);
    bool success = request.complete && request.success;
    if (success) {
        pthread_mutex_lock(&broker.lock);
        record->next = broker.windowBuffers;
        broker.windowBuffers = record;
        pthread_mutex_unlock(&broker.lock);
        *outBuffer = request.buffer;
        *outWidth = request.width;
        *outHeight = request.height;
    }
    pthread_mutex_unlock(&request.lock);
    if (!success) free(record);
    return success;
}

static void unlinkWindowBufferLocked(struct BrokerWindowBuffer *record) {
    struct BrokerWindowBuffer **cursor = &broker.windowBuffers;
    while (*cursor) {
        if (*cursor == record) {
            *cursor = record->next;
            return;
        }
        cursor = &(*cursor)->next;
    }
}

static Bool notifyWindowContentOnXorgThread(__unused ClientPtr client, void *closure) {
    struct WindowContentRequest *request = closure;
    struct BrokerWindowBuffer *record = request->record;
    bool destroy = false;
    lorieVulkanBrokerPresentWindowBuffer(request->windowId, record->lorieBuffer);
    pthread_mutex_lock(&broker.lock);
    if (record->presentRefs > 0) record->presentRefs--;
    if (record->released && record->presentRefs == 0) {
        unlinkWindowBufferLocked(record);
        destroy = true;
    }
    pthread_mutex_unlock(&broker.lock);
    if (destroy) {
        lorieVulkanBrokerDestroyWindowBuffer(record->lorieBuffer);
        free(record);
    }
    free(request);
    return TRUE;
}

static Bool destroyWindowBufferOnXorgThread(__unused ClientPtr client, void *closure) {
    struct WindowBufferDestroyRequest *request = closure;
    lorieVulkanBrokerDestroyWindowBuffer(request->windowBuffer);
    free(request);
    return TRUE;
}

static void destroyWindowBuffer(LorieVulkanWindowBuffer *windowBuffer) {
    struct WindowBufferDestroyRequest *request;
    if (!windowBuffer) return;
    request = calloc(1, sizeof(*request));
    if (!request) {
        BROKER_LOG(ERROR, "Cannot queue destruction of Vulkan window buffer");
        return;
    }
    request->windowBuffer = windowBuffer;
    QueueWorkProc(destroyWindowBufferOnXorgThread, NULL, request);
    lorieWakeServer();
}

static Bool getWindowSizeOnXorgThread(__unused ClientPtr client, void *closure) {
    struct WindowSizeRequest *request = closure;
    uint32_t width = 0;
    uint32_t height = 0;
    bool success = lorieVulkanBrokerGetWindowSize(request->windowId, &width, &height);

    pthread_mutex_lock(&request->lock);
    request->width = width;
    request->height = height;
    request->success = success;
    request->complete = true;
    pthread_cond_signal(&request->done);
    pthread_mutex_unlock(&request->lock);
    return TRUE;
}

static bool getWindowSize(XID windowId, uint32_t *outWidth, uint32_t *outHeight) {
    struct WindowSizeRequest request = {
        .lock = PTHREAD_MUTEX_INITIALIZER,
        .done = PTHREAD_COND_INITIALIZER,
        .windowId = windowId,
    };
    if (!outWidth || !outHeight || !pScreenPtr) return false;
    *outWidth = *outHeight = 0;
    pthread_mutex_lock(&request.lock);
    QueueWorkProc(getWindowSizeOnXorgThread, NULL, &request);
    lorieWakeServer();
    while (!request.complete)
        pthread_cond_wait(&request.done, &request.lock);
    bool success = request.complete && request.success;
    if (success) {
        *outWidth = request.width;
        *outHeight = request.height;
    }
    pthread_mutex_unlock(&request.lock);
    return success;
}

static void resetCapabilities(struct VulkanBackend *backend) {
    memset(&backend->capabilities, 0, sizeof(backend->capabilities));
    backend->capabilities.magic = LORIE_VULKAN_BROKER_MAGIC;
    backend->capabilities.protocolVersion = LORIE_VULKAN_BROKER_PROTOCOL_VERSION;
    backend->capabilities.size = sizeof(backend->capabilities);
    backend->capabilities.status = -ENODEV;
}

static void destroyVulkanBackend(struct VulkanBackend *backend) {
    if (backend->device && backend->destroyDevice)
        backend->destroyDevice(backend->device, NULL);
    if (backend->instance && backend->destroyInstance)
        backend->destroyInstance(backend->instance, NULL);
    if (backend->library)
        dlclose(backend->library);
    memset(backend, 0, sizeof(*backend));
}

static void setBackendFailure(struct VulkanBackend *backend, int32_t status) {
    destroyVulkanBackend(backend);
    resetCapabilities(backend);
    backend->capabilities.status = status;
}

static bool supportsDeviceExtension(PFN_vkEnumerateDeviceExtensionProperties enumerateExtensions,
                                    VkPhysicalDevice device, const char *name) {
    uint32_t count = 0;
    if (enumerateExtensions(device, NULL, &count, NULL) != VK_SUCCESS || count == 0)
        return false;

    VkExtensionProperties extensions[count];
    if (enumerateExtensions(device, NULL, &count, extensions) != VK_SUCCESS)
        return false;

    for (uint32_t index = 0; index < count; ++index) {
        if (strcmp(extensions[index].extensionName, name) == 0)
            return true;
    }
    return false;
}

static bool verifyAndroidHardwareBufferImport(struct VulkanBackend *backend) {
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getProperties =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID) backend->getDeviceProcAddr(backend->device,
            "vkGetAndroidHardwareBufferPropertiesANDROID");
    if (!getProperties) {
        BROKER_LOG(WARN, "Vulkan loader exposes no Android Hardware Buffer properties entry point");
        return false;
    }

    AHardwareBuffer_Desc description = {
        .width = 16,
        .height = 16,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
    };
    AHardwareBuffer *buffer = NULL;
    int allocationStatus = AHardwareBuffer_allocate(&description, &buffer);
    if (allocationStatus != 0 || !buffer) {
        BROKER_LOG(WARN, "Cannot allocate probe Android Hardware Buffer: %d", allocationStatus);
        return false;
    }

    PFN_vkCreateImage createImage =
        (PFN_vkCreateImage) backend->getDeviceProcAddr(backend->device, "vkCreateImage");
    PFN_vkDestroyImage destroyImage =
        (PFN_vkDestroyImage) backend->getDeviceProcAddr(backend->device, "vkDestroyImage");
    PFN_vkAllocateMemory allocateMemory =
        (PFN_vkAllocateMemory) backend->getDeviceProcAddr(backend->device, "vkAllocateMemory");
    PFN_vkFreeMemory freeMemory =
        (PFN_vkFreeMemory) backend->getDeviceProcAddr(backend->device, "vkFreeMemory");
    PFN_vkBindImageMemory bindImageMemory =
        (PFN_vkBindImageMemory) backend->getDeviceProcAddr(backend->device, "vkBindImageMemory");
    if (!createImage || !destroyImage || !allocateMemory || !freeMemory || !bindImageMemory)
        return false;

    VkAndroidHardwareBufferPropertiesANDROID properties = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
    };
    VkResult result = getProperties(backend->device, buffer, &properties);
    if (result != VK_SUCCESS || properties.memoryTypeBits == 0) {
        BROKER_LOG(WARN, "Android Hardware Buffer import probe failed: result=%d size=%llu types=%#x",
            result, (unsigned long long) properties.allocationSize, properties.memoryTypeBits);
        AHardwareBuffer_release(buffer);
        return false;
    }

    VkExternalFormatANDROID externalFormat = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
    };
    VkExternalMemoryImageCreateInfo externalMemoryImage = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = &externalFormat,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkImageCreateInfo imageInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &externalMemoryImage,
        .flags = VK_IMAGE_CREATE_ALIAS_BIT,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {.width = 16, .height = 16, .depth = 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    VkImage image = VK_NULL_HANDLE;
    result = createImage(backend->device, &imageInfo, NULL, &image);
    if (result != VK_SUCCESS) {
        BROKER_LOG(WARN, "Cannot create probe Vulkan image: %d", result);
        AHardwareBuffer_release(buffer);
        return false;
    }

    uint32_t memoryType = (uint32_t) __builtin_ctz(properties.memoryTypeBits);
    VkImportAndroidHardwareBufferInfoANDROID importBuffer = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .buffer = buffer,
    };
    VkMemoryDedicatedAllocateInfo dedicatedAllocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &importBuffer,
        .image = image,
    };
    VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicatedAllocation,
        .allocationSize = properties.allocationSize,
        .memoryTypeIndex = memoryType,
    };
    VkDeviceMemory memory = VK_NULL_HANDLE;
    result = allocateMemory(backend->device, &allocation, NULL, &memory);
    if (result == VK_SUCCESS)
        result = bindImageMemory(backend->device, image, memory, 0);
    if (result != VK_SUCCESS) {
        BROKER_LOG(WARN, "Cannot import probe Android Hardware Buffer as Vulkan image: %d", result);
    }
    if (memory)
        freeMemory(backend->device, memory, NULL);
    destroyImage(backend->device, image, NULL);
    AHardwareBuffer_release(buffer);
    return result == VK_SUCCESS;
}

static void initializeVulkanBackend(struct VulkanBackend *backend) {
    destroyVulkanBackend(backend);
    resetCapabilities(backend);

    backend->library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!backend->library) {
        BROKER_LOG(WARN, "Cannot load Android Vulkan loader: %s", dlerror());
        return;
    }

    backend->getInstanceProcAddr = dlsym(backend->library, "vkGetInstanceProcAddr");
    if (!backend->getInstanceProcAddr) {
        BROKER_LOG(WARN, "Android Vulkan loader has no vkGetInstanceProcAddr");
        setBackendFailure(backend, -ENOSYS);
        return;
    }
    PFN_vkCreateInstance createInstance =
        (PFN_vkCreateInstance) backend->getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    if (!createInstance) {
        BROKER_LOG(WARN, "Android Vulkan loader has no vkCreateInstance");
        setBackendFailure(backend, -ENOSYS);
        return;
    }

    VkApplicationInfo applicationInfo = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Termux Vulkan Broker",
        .applicationVersion = 1,
        .pEngineName = "Termux:X11",
        .engineVersion = 1,
        .apiVersion = VK_API_VERSION_1_0,
    };
    VkInstanceCreateInfo createInfo = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &applicationInfo,
    };
    VkResult result = createInstance(&createInfo, NULL, &backend->instance);
    if (result != VK_SUCCESS) {
        BROKER_LOG(WARN, "vkCreateInstance failed: %d", result);
        setBackendFailure(backend, (int32_t) result);
        return;
    }

    backend->destroyInstance =
        (PFN_vkDestroyInstance) backend->getInstanceProcAddr(backend->instance, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices =
        (PFN_vkEnumeratePhysicalDevices) backend->getInstanceProcAddr(backend->instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties =
        (PFN_vkGetPhysicalDeviceProperties) backend->getInstanceProcAddr(backend->instance, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getQueueFamilyProperties =
        (PFN_vkGetPhysicalDeviceQueueFamilyProperties) backend->getInstanceProcAddr(backend->instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    PFN_vkCreateDevice createDevice =
        (PFN_vkCreateDevice) backend->getInstanceProcAddr(backend->instance, "vkCreateDevice");
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensions =
        (PFN_vkEnumerateDeviceExtensionProperties) backend->getInstanceProcAddr(backend->instance,
            "vkEnumerateDeviceExtensionProperties");
    if (!backend->destroyInstance || !enumeratePhysicalDevices || !getPhysicalDeviceProperties ||
        !getQueueFamilyProperties || !createDevice || !enumerateDeviceExtensions) {
        setBackendFailure(backend, -ENOSYS);
        return;
    }

    uint32_t deviceCount = 0;
    result = enumeratePhysicalDevices(backend->instance, &deviceCount, NULL);
    if (result == VK_SUCCESS && deviceCount > 0) {
        VkPhysicalDevice device = VK_NULL_HANDLE;
        uint32_t requestedDeviceCount = 1;
        result = enumeratePhysicalDevices(backend->instance, &requestedDeviceCount, &device);
        if (result == VK_SUCCESS) {
            VkPhysicalDeviceProperties properties;
            getPhysicalDeviceProperties(device, &properties);
            backend->physicalDevice = device;
            backend->capabilities.apiVersion = properties.apiVersion;
            backend->capabilities.vendorId = properties.vendorID;
            backend->capabilities.deviceId = properties.deviceID;
            backend->capabilities.deviceType = properties.deviceType;
            snprintf(backend->capabilities.deviceName, sizeof(backend->capabilities.deviceName), "%s",
                properties.deviceName);
        }
    }
    if (!backend->physicalDevice) {
        setBackendFailure(backend, result == VK_SUCCESS ? -ENODEV : (int32_t) result);
        return;
    }

    bool hasAndroidHardwareBufferImport = supportsDeviceExtension(enumerateDeviceExtensions,
        backend->physicalDevice, ANDROID_HARDWARE_BUFFER_EXTENSION);
    if (!hasAndroidHardwareBufferImport) {
        BROKER_LOG(WARN, "Selected Vulkan device does not support Android Hardware Buffer import");
    }

    uint32_t queueFamilyCount = 0;
    getQueueFamilyProperties(backend->physicalDevice, &queueFamilyCount, NULL);
    if (!queueFamilyCount) {
        setBackendFailure(backend, -ENODEV);
        return;
    }
    VkQueueFamilyProperties queueFamilies[queueFamilyCount];
    getQueueFamilyProperties(backend->physicalDevice, &queueFamilyCount, queueFamilies);
    bool foundGraphicsQueue = false;
    for (uint32_t index = 0; index < queueFamilyCount; ++index) {
        if (queueFamilies[index].queueCount > 0 &&
            (queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            backend->graphicsQueueFamily = index;
            foundGraphicsQueue = true;
            break;
        }
    }
    if (!foundGraphicsQueue) {
        setBackendFailure(backend, -ENODEV);
        return;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = backend->graphicsQueueFamily,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    const char *deviceExtensions[1];
    uint32_t deviceExtensionCount = 0;
    if (hasAndroidHardwareBufferImport) {
        deviceExtensions[deviceExtensionCount++] =
            ANDROID_HARDWARE_BUFFER_EXTENSION;
    }
    VkDeviceCreateInfo deviceCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCreateInfo,
        .enabledExtensionCount = deviceExtensionCount,
        .ppEnabledExtensionNames = deviceExtensions,
    };
    result = createDevice(backend->physicalDevice, &deviceCreateInfo, NULL, &backend->device);
    if (result != VK_SUCCESS) {
        setBackendFailure(backend, (int32_t) result);
        return;
    }
    backend->getDeviceProcAddr =
        (PFN_vkGetDeviceProcAddr) backend->getInstanceProcAddr(backend->instance, "vkGetDeviceProcAddr");
    if (!backend->getDeviceProcAddr) {
        setBackendFailure(backend, -ENOSYS);
        return;
    }
    backend->destroyDevice =
        (PFN_vkDestroyDevice) backend->getDeviceProcAddr(backend->device, "vkDestroyDevice");
    PFN_vkGetDeviceQueue getDeviceQueue =
        (PFN_vkGetDeviceQueue) backend->getDeviceProcAddr(backend->device, "vkGetDeviceQueue");
    if (!backend->destroyDevice || !getDeviceQueue) {
        setBackendFailure(backend, -ENOSYS);
        return;
    }
    getDeviceQueue(backend->device, backend->graphicsQueueFamily, 0, &backend->graphicsQueue);
    if (!backend->graphicsQueue) {
        setBackendFailure(backend, -ENODEV);
        return;
    }
    if (hasAndroidHardwareBufferImport && verifyAndroidHardwareBufferImport(backend)) {
        backend->capabilities.capabilities |= LORIE_VULKAN_BROKER_CAPABILITY_ANDROID_HARDWARE_BUFFER;
    } else if (hasAndroidHardwareBufferImport) {
        BROKER_LOG(WARN, "Vulkan device failed Android Hardware Buffer import probe");
    }
    backend->capabilities.status = 0;
    if (backend->capabilities.status == 0)
        BROKER_LOG(INFO, "Created Vulkan device %s (vendor=%#x, device=%#x)",
            backend->capabilities.deviceName, backend->capabilities.vendorId,
            backend->capabilities.deviceId);
}

static void serveClient(int client) {
    uint32_t header[2];
    if (!readAll(client, header, sizeof(header))) return;

    if (header[0] == 1 && header[1] == 0 && vortekBackendIsReady()) {
        VkContext *context = vortekBackendCreateContext(client);
        if (!context) {
            BROKER_LOG(ERROR, "Cannot create Vortek Vulkan context");
            return;
        }

        while (readAll(client, header, sizeof(header))) {
            uint16_t requestCode = (uint16_t) (header[0] >> 16);
            uint16_t requestId = (uint16_t) header[0];
            int requestLength = (int) header[1];
            if (requestCode != 2 || requestLength < 0 ||
                !vortekBackendHandleExtraData(context, requestId, requestLength)) {
                BROKER_LOG(WARN, "Rejected malformed Vortek extra-data request");
                break;
            }
        }
        vortekBackendDestroyContext(context);
        return;
    }

    struct timeval timeout = { .tv_sec = 1, .tv_usec = 0 };
    setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    struct VulkanBrokerHello hello;
    memcpy(&hello, header, sizeof(header));
    if (hello.magic != LORIE_VULKAN_BROKER_MAGIC ||
        hello.protocolVersion != LORIE_VULKAN_BROKER_PROTOCOL_VERSION ||
        hello.size != sizeof(hello)) {
        BROKER_LOG(WARN, "Rejected Vulkan broker client with incompatible handshake");
        return;
    }

    pthread_mutex_lock(&broker.lock);
    struct VulkanBrokerCapabilities capabilities = broker.backend.capabilities;
    pthread_mutex_unlock(&broker.lock);
    writeAll(client, &capabilities, sizeof(capabilities));
}

static void *brokerThread(void *unused) {
    (void) unused;
    for (;;) {
        pthread_mutex_lock(&broker.lock);
        bool running = broker.running;
        int listener = broker.listener;
        pthread_mutex_unlock(&broker.lock);
        if (!running || listener < 0) break;

        int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            pthread_mutex_lock(&broker.lock);
            running = broker.running;
            pthread_mutex_unlock(&broker.lock);
            if (running) BROKER_LOG(WARN, "Vulkan broker accept failed: %s", strerror(errno));
            continue;
        }
        serveClient(client);
        close(client);
    }
    return NULL;
}

bool vulkanBrokerStart(const char *socketPath) {
    if (!socketPath || socketPath[0] == '\0' || strlen(socketPath) >= sizeof(broker.socketPath))
        return false;

    pthread_mutex_lock(&broker.lock);
    if (broker.running) {
        bool samePath = strcmp(broker.socketPath, socketPath) == 0;
        pthread_mutex_unlock(&broker.lock);
        return samePath;
    }
    if (broker.stopping) {
        pthread_mutex_unlock(&broker.lock);
        return false;
    }

    int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (listener < 0) {
        BROKER_LOG(ERROR, "Cannot create Vulkan broker socket: %s", strerror(errno));
        pthread_mutex_unlock(&broker.lock);
        return false;
    }

    if (!ensureSocketParentDirectory(socketPath)) {
        BROKER_LOG(ERROR, "Cannot create Vulkan broker socket directory for %s: %s", socketPath,
            strerror(errno));
        close(listener);
        pthread_mutex_unlock(&broker.lock);
        return false;
    }

    struct sockaddr_un address = { .sun_family = AF_UNIX };
    snprintf(address.sun_path, sizeof(address.sun_path), "%s", socketPath);
    unlink(address.sun_path);
    if (bind(listener, (const struct sockaddr *) &address, sizeof(address)) != 0 ||
        chmod(address.sun_path, S_IRUSR | S_IWUSR) != 0 ||
        listen(listener, 8) != 0) {
        BROKER_LOG(ERROR, "Cannot bind Vulkan broker socket %s: %s", socketPath, strerror(errno));
        close(listener);
        unlink(address.sun_path);
        pthread_mutex_unlock(&broker.lock);
        return false;
    }

    broker.listener = listener;
    initializeVulkanBackend(&broker.backend);
    broker.running = true;
    snprintf(broker.socketPath, sizeof(broker.socketPath), "%s", socketPath);
    if (pthread_create(&broker.thread, NULL, brokerThread, NULL) != 0) {
        BROKER_LOG(ERROR, "Cannot start Vulkan broker thread");
        close(listener);
        unlink(broker.socketPath);
        broker.listener = -1;
        broker.running = false;
        broker.socketPath[0] = '\0';
        pthread_mutex_unlock(&broker.lock);
        return false;
    }
    pthread_mutex_unlock(&broker.lock);
    BROKER_LOG(INFO, "Vulkan broker listening on %s", socketPath);
    return true;
}

void vulkanBrokerStop(void) {
    pthread_mutex_lock(&broker.lock);
    if (!broker.running) {
        pthread_mutex_unlock(&broker.lock);
        return;
    }
    int listener = broker.listener;
    broker.listener = -1;
    broker.running = false;
    broker.stopping = true;
    char socketPath[sizeof(broker.socketPath)];
    snprintf(socketPath, sizeof(socketPath), "%s", broker.socketPath);
    broker.socketPath[0] = '\0';
    pthread_mutex_unlock(&broker.lock);

    shutdown(listener, SHUT_RDWR);
    close(listener);
    pthread_join(broker.thread, NULL);
    unlink(socketPath);
    pthread_mutex_lock(&broker.lock);
    destroyVulkanBackend(&broker.backend);
    broker.stopping = false;
    pthread_mutex_unlock(&broker.lock);
}

bool vulkanBrokerIsRunning(void) {
    pthread_mutex_lock(&broker.lock);
    bool running = broker.running;
    pthread_mutex_unlock(&broker.lock);
    return running;
}

bool vulkanBrokerAcquireWindowBuffer(uint32_t windowId, bool bgra8888,
                                     AHardwareBuffer **outBuffer,
                                     uint32_t *outWidth, uint32_t *outHeight) {
    if (!vulkanBrokerIsRunning()) return false;
    return acquireWindowBuffer((XID) windowId, bgra8888, outBuffer, outWidth, outHeight);
}

bool vulkanBrokerGetWindowSize(uint32_t windowId, uint32_t *outWidth, uint32_t *outHeight) {
    if (!vulkanBrokerIsRunning()) return false;
    return getWindowSize((XID) windowId, outWidth, outHeight);
}

void vulkanBrokerReleaseWindowBuffer(AHardwareBuffer *buffer) {
    struct BrokerWindowBuffer *record = NULL;
    bool destroy = false;
    bool alreadyReleased = false;
    if (!buffer) return;

    pthread_mutex_lock(&broker.lock);
    for (record = broker.windowBuffers; record; record = record->next) {
        if (record->hardwareBuffer == buffer) {
            alreadyReleased = record->released;
            if (alreadyReleased) break;
            record->released = true;
            if (record->presentRefs == 0) {
                unlinkWindowBufferLocked(record);
                destroy = true;
            }
            break;
        }
    }
    pthread_mutex_unlock(&broker.lock);

    if (alreadyReleased) {
        BROKER_LOG(WARN, "Vulkan window buffer %p was released twice", buffer);
        return;
    }
    AHardwareBuffer_release(buffer);
    if (!record) {
        BROKER_LOG(WARN, "Released unknown Vulkan window buffer %p", buffer);
        return;
    }
    if (destroy) {
        destroyWindowBuffer(record->lorieBuffer);
        free(record);
    }
}

void vulkanBrokerNotifyWindowContent(uint32_t windowId, AHardwareBuffer *buffer) {
    struct BrokerWindowBuffer *record;
    if (!vulkanBrokerIsRunning()) return;
    if (!buffer) return;

    pthread_mutex_lock(&broker.lock);
    for (record = broker.windowBuffers; record; record = record->next) {
        if (!record->released && record->windowId == (XID) windowId &&
            record->hardwareBuffer == buffer)
            break;
    }
    if (record) record->presentRefs++;
    pthread_mutex_unlock(&broker.lock);
    if (!record) {
        BROKER_LOG(WARN, "Present for unknown Vulkan window buffer %p", buffer);
        return;
    }

    struct WindowContentRequest *request = calloc(1, sizeof(*request));
    if (!request) {
        bool destroy = false;
        pthread_mutex_lock(&broker.lock);
        if (record->presentRefs > 0) record->presentRefs--;
        if (record->released && record->presentRefs == 0) {
            unlinkWindowBufferLocked(record);
            destroy = true;
        }
        pthread_mutex_unlock(&broker.lock);
        if (destroy) {
            destroyWindowBuffer(record->lorieBuffer);
            free(record);
        }
        return;
    }
    request->windowId = (XID) windowId;
    request->record = record;
    QueueWorkProc(notifyWindowContentOnXorgThread, NULL, request);
    lorieWakeServer();
}
