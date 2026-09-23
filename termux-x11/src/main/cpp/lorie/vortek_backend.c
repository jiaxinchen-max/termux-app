#include "vortek_backend.h"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include "vk_context.h"
#include "vulkan_helper.h"
#include "vulkan_wrapper.h"

#define VORTEK_LOG(priority, ...) \
    __android_log_print(ANDROID_LOG_##priority, "VortekBackend", __VA_ARGS__)

VulkanWrapper vulkanWrapper = {0};
bool vortekSerializerCastVkObject = true;

struct VortekBackendState {
    pthread_mutex_t lock;
    JavaVM *vm;
    void *vulkanLibrary;
    jobject callbacks;
    jobject options;
    bool ready;
};

static struct VortekBackendState backend = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
};

static JNIEnv *attachCurrentThread(bool *attached) {
    *attached = false;
    if (!backend.vm) return NULL;

    JNIEnv *env = NULL;
    jint result = (*backend.vm)->GetEnv(backend.vm, (void **) &env, JNI_VERSION_1_6);
    if (result == JNI_OK) return env;
    if (result != JNI_EDETACHED ||
        (*backend.vm)->AttachCurrentThread(backend.vm, &env, NULL) != JNI_OK)
        return NULL;
    *attached = true;
    return env;
}

bool vortekBackendInitialize(JNIEnv *env) {
    pthread_mutex_lock(&backend.lock);
    if (backend.ready) {
        pthread_mutex_unlock(&backend.lock);
        return true;
    }

    if ((*env)->GetJavaVM(env, &backend.vm) != JNI_OK) goto error;
    jclass callbacksClass = (*env)->FindClass(env, "com/termux/x11/VortekServerCallbacks");
    jclass optionsClass = (*env)->FindClass(env, "com/termux/x11/VortekServerOptions");
    if (!callbacksClass || !optionsClass) goto error;

    jmethodID callbacksCtor = (*env)->GetMethodID(env, callbacksClass, "<init>", "()V");
    jmethodID optionsCtor = (*env)->GetMethodID(env, optionsClass, "<init>", "()V");
    if (!callbacksCtor || !optionsCtor) goto error;

    jobject callbacks = (*env)->NewObject(env, callbacksClass, callbacksCtor);
    jobject options = (*env)->NewObject(env, optionsClass, optionsCtor);
    if (!callbacks || !options) goto error;

    backend.callbacks = (*env)->NewGlobalRef(env, callbacks);
    backend.options = (*env)->NewGlobalRef(env, options);
    if (!backend.callbacks || !backend.options) goto error;

    backend.vulkanLibrary = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!backend.vulkanLibrary) {
        VORTEK_LOG(ERROR, "Cannot load Bionic Vulkan: %s", dlerror());
        goto error;
    }
    initVulkanWrapper(&vulkanWrapper, backend.vulkanLibrary);
    if (!vulkanWrapper.vkCreateInstance || !vulkanWrapper.vkGetInstanceProcAddr ||
        !vulkanWrapper.vkGetDeviceProcAddr) {
        VORTEK_LOG(ERROR, "Bionic Vulkan is missing required entry points");
        goto error;
    }

    backend.ready = true;
    pthread_mutex_unlock(&backend.lock);
    VORTEK_LOG(INFO, "Vortek Vulkan RPC backend initialized");
    return true;

error:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (backend.callbacks) {
        (*env)->DeleteGlobalRef(env, backend.callbacks);
        backend.callbacks = NULL;
    }
    if (backend.options) {
        (*env)->DeleteGlobalRef(env, backend.options);
        backend.options = NULL;
    }
    if (backend.vulkanLibrary) {
        dlclose(backend.vulkanLibrary);
        backend.vulkanLibrary = NULL;
    }
    backend.vm = NULL;
    pthread_mutex_unlock(&backend.lock);
    return false;
}

bool vortekBackendIsReady(void) {
    pthread_mutex_lock(&backend.lock);
    bool ready = backend.ready;
    pthread_mutex_unlock(&backend.lock);
    return ready;
}

VkContext *vortekBackendCreateContext(int clientFd) {
    pthread_mutex_lock(&backend.lock);
    bool attached = false;
    JNIEnv *env = backend.ready ? attachCurrentThread(&attached) : NULL;
    VkContext *context = env ? createVkContext(env, backend.callbacks, clientFd, backend.options) : NULL;
    pthread_mutex_unlock(&backend.lock);
    if (attached) (*backend.vm)->DetachCurrentThread(backend.vm);
    return context;
}

void vortekBackendDestroyContext(VkContext *context) {
    if (!context) return;
    pthread_mutex_lock(&backend.lock);
    bool attached = false;
    JNIEnv *env = attachCurrentThread(&attached);
    if (env) destroyVkContext(env, context);
    pthread_mutex_unlock(&backend.lock);
    if (attached) (*backend.vm)->DetachCurrentThread(backend.vm);
}

bool vortekBackendHandleExtraData(VkContext *context, uint16_t requestId, int requestLength) {
    return context && requestLength >= 0 && handleExtraDataRequest(context, requestId, requestLength);
}
