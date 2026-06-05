#pragma clang diagnostic ignored "-Wunknown-pragmas"
#pragma ide diagnostic ignored "bugprone-reserved-identifier"
#pragma ide diagnostic ignored "ConstantParameter"
#pragma ide diagnostic ignored "OCUnusedGlobalDeclarationInspection"
#pragma ide diagnostic ignored "OCUnusedMacroInspection"
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <stdbool.h>
#ifndef TERMUX_RENDER_FD_ONLY
#include <dlfcn.h>
#endif
#include <linux/memfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <errno.h>
#ifndef TERMUX_RENDER_FD_ONLY
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/sharedmem.h>
#include <linux/ashmem.h>
#else
typedef unsigned int GLuint;
typedef void *EGLImage;
#endif
#include <unistd.h>
#include "include/list.h"
#include "include/buffer.h"
#include "include/tlog.h"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif

struct LorieBuffer {
    int16_t refcount;
    LorieBuffer_Desc desc;

    int8_t locked;
    void* lockedData;

    // file descriptor of shared memory fragment for shared memory backed buffer
    int fd;
    size_t size;
    off_t offset;

    GLuint id;
    EGLImage image;
    struct xorg_list link;
};

#ifndef TERMUX_RENDER_FD_ONLY
typedef int (*ASharedMemory_create_fn)(const char *name, size_t size);
typedef int (*AHardwareBuffer_allocate_fn)(const AHardwareBuffer_Desc *desc,
                                           AHardwareBuffer **outBuffer);
typedef void (*AHardwareBuffer_describe_fn)(const AHardwareBuffer *buffer,
                                            AHardwareBuffer_Desc *outDesc);
typedef void (*AHardwareBuffer_release_fn)(AHardwareBuffer *buffer);
typedef int (*AHardwareBuffer_lock_fn)(AHardwareBuffer *buffer,
                                       uint64_t usage, int32_t fence,
                                       const ARect *rect, void **outVirtualAddress);
typedef int (*AHardwareBuffer_unlock_fn)(AHardwareBuffer *buffer,
                                         int32_t *fence);
typedef int (*AHardwareBuffer_recvHandleFromUnixSocket_fn)(int socketFd,
                                                           AHardwareBuffer **outBuffer);
typedef int (*AHardwareBuffer_sendHandleToUnixSocket_fn)(const AHardwareBuffer *buffer,
                                                         int socketFd);

static void *
libandroidSymbol(const char *name)
{
    static void *handle;
    static bool attempted;

    if (!attempted) {
        attempted = true;
        handle = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle)
            tlog(LOG_WARNING, "dlopen libandroid.so failed: %s", dlerror());
    }

    if (!handle)
        return NULL;

    dlerror();
    void *symbol = dlsym(handle, name);
    if (!symbol)
        tlog(LOG_WARNING, "dlsym %s failed: %s", name, dlerror());
    return symbol;
}

#define LIBANDROID_SYMBOL(name, type) \
static type \
name##_dynamic(void) \
{ \
    static type symbol; \
    static bool attempted; \
    if (!attempted) { \
        attempted = true; \
        symbol = (type) libandroidSymbol(#name); \
    } \
    return symbol; \
}

LIBANDROID_SYMBOL(ASharedMemory_create, ASharedMemory_create_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_allocate, AHardwareBuffer_allocate_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_describe, AHardwareBuffer_describe_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_release, AHardwareBuffer_release_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_lock, AHardwareBuffer_lock_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_unlock, AHardwareBuffer_unlock_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_recvHandleFromUnixSocket,
                  AHardwareBuffer_recvHandleFromUnixSocket_fn)
LIBANDROID_SYMBOL(AHardwareBuffer_sendHandleToUnixSocket,
                  AHardwareBuffer_sendHandleToUnixSocket_fn)

static int
androidSharedMemoryCreate(const char *name, size_t size)
{
    ASharedMemory_create_fn create = ASharedMemory_create_dynamic();

    if (!create) {
        errno = ENOSYS;
        return -1;
    }

    return create(name, size);
}

static int
androidHardwareBufferAllocate(const AHardwareBuffer_Desc *desc,
                              AHardwareBuffer **outBuffer)
{
    AHardwareBuffer_allocate_fn allocate = AHardwareBuffer_allocate_dynamic();

    if (!allocate) {
        errno = ENOSYS;
        return -1;
    }

    return allocate(desc, outBuffer);
}

static bool
androidHardwareBufferDescribe(const AHardwareBuffer *buffer,
                              AHardwareBuffer_Desc *outDesc)
{
    AHardwareBuffer_describe_fn describe = AHardwareBuffer_describe_dynamic();

    if (!describe) {
        errno = ENOSYS;
        return false;
    }

    describe(buffer, outDesc);
    return true;
}

static void
androidHardwareBufferRelease(AHardwareBuffer *buffer)
{
    AHardwareBuffer_release_fn release = AHardwareBuffer_release_dynamic();

    if (release)
        release(buffer);
}

static int
androidHardwareBufferLock(AHardwareBuffer *buffer, uint64_t usage,
                          int32_t fence, const ARect *rect,
                          void **outVirtualAddress)
{
    AHardwareBuffer_lock_fn lock = AHardwareBuffer_lock_dynamic();

    if (!lock) {
        errno = ENOSYS;
        return -1;
    }

    return lock(buffer, usage, fence, rect, outVirtualAddress);
}

static int
androidHardwareBufferUnlock(AHardwareBuffer *buffer, int32_t *fence)
{
    AHardwareBuffer_unlock_fn unlock = AHardwareBuffer_unlock_dynamic();

    if (!unlock) {
        errno = ENOSYS;
        return -1;
    }

    return unlock(buffer, fence);
}

static int
androidHardwareBufferRecvHandleFromUnixSocket(int socketFd,
                                              AHardwareBuffer **outBuffer)
{
    AHardwareBuffer_recvHandleFromUnixSocket_fn recvHandle =
        AHardwareBuffer_recvHandleFromUnixSocket_dynamic();

    if (!recvHandle) {
        errno = ENOSYS;
        return -1;
    }

    return recvHandle(socketFd, outBuffer);
}

static int
androidHardwareBufferSendHandleToUnixSocket(AHardwareBuffer *buffer,
                                            int socketFd)
{
    AHardwareBuffer_sendHandleToUnixSocket_fn sendHandle =
        AHardwareBuffer_sendHandleToUnixSocket_dynamic();

    if (!sendHandle) {
        errno = ENOSYS;
        return -1;
    }

    return sendHandle(buffer, socketFd);
}
#endif

__attribute__((unused))
static int lorie_memfd_create(const char *name, unsigned int flags) {
#ifndef __NR_memfd_create
#if defined __i386__
#define __NR_memfd_create 356
#elif defined __x86_64__
    #define __NR_memfd_create 319
#elif defined __arm__
#define __NR_memfd_create 385
#elif defined __aarch64__
#define __NR_memfd_create 279
#endif
#endif

#ifdef __NR_memfd_create
    return syscall(__NR_memfd_create, name, flags); // NOLINT(cppcoreguidelines-narrowing-conversions)
#else
    errno = ENOSYS;
	return -1;
#endif
}

static inline size_t alignToPage(size_t size) {
    size_t page_size = sysconf(_SC_PAGE_SIZE);
    return (size + page_size - 1) & ~(page_size - 1);
}

static int readFullFromSocket(int fd, void *buffer, size_t size) {
    size_t offset = 0;

    while (offset < size) {
        ssize_t count = read(fd, (char *) buffer + offset, size - offset);
        if (count > 0) {
            offset += count;
            continue;
        }
        if (count == 0) {
            tlog(LOG_ERR, "readFullFromSocket closed after %zu/%zu bytes", offset, size);
            return offset == 0 ? 0 : -1;
        }
        if (errno == EINTR) {
            continue;
        }
        tlog(LOG_ERR, "readFullFromSocket failed after %zu/%zu bytes: %s",
             offset, size, strerror(errno));
        return -1;
    }

    return 1;
}

static int writeFullToSocket(int fd, const void *buffer, size_t size) {
    size_t offset = 0;

    while (offset < size) {
        ssize_t count = write(fd, (const char *) buffer + offset, size - offset);
        if (count > 0) {
            offset += count;
            continue;
        }
        if (count < 0 && errno == EINTR)
            continue;
        tlog(LOG_ERR, "writeFullToSocket failed after %zu/%zu bytes: %s",
             offset, size, strerror(errno));
        return -1;
    }

    return 0;
}

#pragma clang diagnostic push
#pragma ide diagnostic ignored "UnreachableCallsOfFunction"
int LorieBuffer_createRegion(char const* name, size_t size) {
#ifndef TERMUX_RENDER_FD_ONLY
    int fd = androidSharedMemoryCreate(name, size);
    if (fd >= 0)
        return fd;
#else
    int fd = -1;
#endif

    fd = lorie_memfd_create(name, MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if (fd >= 0) {
        ftruncate (fd, size);
        return fd;
    }

#ifndef TERMUX_RENDER_FD_ONLY
    fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0)
        return fd;

    char name_buffer[ASHMEM_NAME_LEN] = {0};
    strncpy(name_buffer, name, sizeof(name_buffer));
    name_buffer[sizeof(name_buffer)-1] = 0;

    int ret = ioctl(fd, ASHMEM_SET_NAME, name_buffer);
    if (ret < 0) goto error;

    ret = ioctl(fd, ASHMEM_SET_SIZE, size);
    if (ret < 0) goto error;

    return fd;
    error:
    close(fd);
    return ret;
#else
    return fd;
#endif
}
#pragma clang diagnostic pop

static LorieBuffer* allocate(int32_t width, int32_t stride, int32_t height, int8_t format, int8_t type, AHardwareBuffer *buf, int fd, size_t size, off_t offset, bool takeFd) {
#ifndef TERMUX_RENDER_FD_ONLY
    AHardwareBuffer_Desc desc = {0};
#endif
    static uint64_t id = 0;
    bool acceptable = (format == AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM || format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM) && width > 0 && height > 0;
    LorieBuffer b = { .desc = { .width = width, .stride = stride, .height = height, .format = format, .type = type, .buffer = buf, .id = id++ }, .fd = takeFd ? fd : dup(fd), .size = size, .offset = offset };

    if (type != LORIEBUFFER_AHARDWAREBUFFER && !acceptable)
        return NULL;

    __sync_fetch_and_add(&b.refcount, 1);

    switch (type) {
        case LORIEBUFFER_REGULAR:
            b.desc.data = calloc(1, stride * height * sizeof(uint32_t));
            if (!b.desc.data)
                return NULL;
            break;
        case LORIEBUFFER_FD:
            if (b.fd < 0)
                return NULL;

            b.desc.data = mmap(NULL, b.size, PROT_READ|PROT_WRITE, MAP_SHARED, b.fd, b.offset);
            if (b.desc.data == NULL || b.desc.data == MAP_FAILED) {
                close(b.fd);
                return NULL;
            }
            break;
        case LORIEBUFFER_AHARDWAREBUFFER: {
#ifdef TERMUX_RENDER_FD_ONLY
            return NULL;
#else
            if (!b.desc.buffer)
                return NULL;

            if (!androidHardwareBufferDescribe(b.desc.buffer, &desc))
                return NULL;
            b.desc.width = desc.width;
            b.desc.height = desc.height;
            b.desc.stride = desc.stride;
            b.desc.format = desc.format;
            break;
#endif
        }
        default: return NULL;
    }

    LorieBuffer* buffer = calloc(1, sizeof(*buffer));
    if (!buffer) {
        switch (type) {
            case LORIEBUFFER_REGULAR:
                free(b.desc.data);
                break;
            case LORIEBUFFER_FD:
                munmap(b.desc.data, b.size);
                close(b.fd);
                break;
            case LORIEBUFFER_AHARDWAREBUFFER:
#ifndef TERMUX_RENDER_FD_ONLY
                androidHardwareBufferRelease(b.desc.buffer);
#endif
                break;
            default: break;
        }

        return NULL;
    }

    *buffer = b;
    xorg_list_init(&buffer->link);
    return buffer;
}

LorieBuffer* LorieBuffer_allocate(int32_t width, int32_t height, int8_t format, int8_t type) {
    int fd = -1;
    size_t size = 0;
    AHardwareBuffer *ahardwarebuffer = NULL;

    if (type == LORIEBUFFER_FD) {
        size = alignToPage(width * height * sizeof(uint32_t));
        fd = LorieBuffer_createRegion("LorieBuffer", size);
        if (fd < 0)
            return NULL;
    } else if (type == LORIEBUFFER_AHARDWAREBUFFER) {
#ifdef TERMUX_RENDER_FD_ONLY
        return NULL;
#else
        AHardwareBuffer_Desc desc = { .width = width, .height = height, .format = format, .layers = 1,
                .usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE };
        int err = androidHardwareBufferAllocate(&desc, &ahardwarebuffer);
        if (err != 0)
            dprintf(2, "FATAL: failed to allocate AHardwareBuffer (width %d height %d format %d): error %d\n", width, height, format, err);
#endif
    }

    return allocate(width, width, height, format, type, ahardwarebuffer, fd, size, 0, true);
}

LorieBuffer* LorieBuffer_wrapFileDescriptor(int32_t width, int32_t stride, int32_t height, int8_t format, int fd, off_t offset) {
    return allocate(width, stride, height, format, LORIEBUFFER_FD, NULL, fd, stride * height * sizeof(uint32_t), offset, false);
}

LorieBuffer* LorieBuffer_wrapAHardwareBuffer(AHardwareBuffer* buffer) {
#ifdef TERMUX_RENDER_FD_ONLY
    (void)buffer;
    return NULL;
#else
    return allocate(0, 0, 0, 0, LORIEBUFFER_AHARDWAREBUFFER, buffer, -1, 0, 0, false);
#endif
}

void __LorieBuffer_free(LorieBuffer *buffer) {
    if (!buffer)
        return;

    if (buffer->locked)
        LorieBuffer_unlock(buffer);

    switch (buffer->desc.type) {
        case LORIEBUFFER_REGULAR:
            free(buffer->desc.data);
            break;
        case LORIEBUFFER_FD:
            if (buffer->desc.data && buffer->desc.data != MAP_FAILED)
                munmap(buffer->desc.data, buffer->size);
            if (buffer->fd >= 0)
                close(buffer->fd);
            break;
        case LORIEBUFFER_AHARDWAREBUFFER:
#ifndef TERMUX_RENDER_FD_ONLY
            if (buffer->desc.buffer)
                androidHardwareBufferRelease(buffer->desc.buffer);
#endif
            break;
        default:
            break;
    }

    xorg_list_del(&buffer->link);
    free(buffer);
}

void LorieBuffer_convert(LorieBuffer *buffer, int8_t type, int8_t format) {
    void *src, *dst = NULL;
    LorieBuffer *converted;
    const LorieBuffer_Desc *srcDesc;

    if (!buffer || buffer->desc.type != LORIEBUFFER_REGULAR)
        return;

    if (type != LORIEBUFFER_FD
#ifndef TERMUX_RENDER_FD_ONLY
        && type != LORIEBUFFER_AHARDWAREBUFFER
#endif
    )
        return;

    if (format != AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM &&
        format != AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM)
        return;

    converted = LorieBuffer_allocate(buffer->desc.width, buffer->desc.height,
                                     format, type);
    if (!converted)
        return;

    srcDesc = LorieBuffer_description(buffer);
    src = buffer->desc.data;
    if (LorieBuffer_lock(converted, &dst) != 0 || !dst) {
        LorieBuffer_release(converted);
        return;
    }

    for (int y = 0; y < srcDesc->height; y++) {
        memcpy((char *) dst + y * converted->desc.stride * sizeof(uint32_t),
               (char *) src + y * srcDesc->stride * sizeof(uint32_t),
               srcDesc->width * sizeof(uint32_t));
    }
    LorieBuffer_unlock(converted);

    free(buffer->desc.data);
    buffer->desc = converted->desc;
    buffer->fd = converted->fd;
    buffer->size = converted->size;
    buffer->offset = converted->offset;
    buffer->locked = false;
    buffer->lockedData = NULL;

    converted->fd = -1;
    converted->size = 0;
    converted->desc.data = NULL;
    converted->desc.buffer = NULL;
    LorieBuffer_release(converted);
}

const LorieBuffer_Desc* LorieBuffer_description(LorieBuffer* buffer) {
    static const LorieBuffer_Desc none = {0};
    return buffer ? &buffer->desc : &none;
}

 int LorieBuffer_lock(LorieBuffer* buffer, void** out) {
    int ret = 0;
    if (!buffer)
        return ENODEV;

    if (buffer->locked) {
        dprintf(2, "tried to lock already locked buffer\n");
        if (out)
            *out = buffer->lockedData;
        return EEXIST;
    }

    if (buffer->desc.type == LORIEBUFFER_REGULAR || buffer->desc.type == LORIEBUFFER_FD)
        buffer->lockedData = buffer->desc.data;
#ifndef TERMUX_RENDER_FD_ONLY
    else if (buffer->desc.type == LORIEBUFFER_AHARDWAREBUFFER)
        ret = androidHardwareBufferLock(buffer->desc.buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &buffer->lockedData);
#else
    else if (buffer->desc.type == LORIEBUFFER_AHARDWAREBUFFER)
        return ENOTSUP;
#endif

    if (out)
        *out = buffer->lockedData;

    buffer->locked = 1;

    return ret;
}

int LorieBuffer_unlock(LorieBuffer* buffer) {
    int ret = 0;
    if (!buffer)
        return ENODEV;

    if (!buffer->locked) {
        dprintf(2, "tried to unlock non-locked buffer\n");
        return ENOENT;
    }

    if (buffer->desc.type == LORIEBUFFER_AHARDWAREBUFFER) {
#ifndef TERMUX_RENDER_FD_ONLY
        ret = androidHardwareBufferUnlock(buffer->desc.buffer, NULL);
#else
        return ENOTSUP;
#endif
    }

    buffer->lockedData = NULL;
    buffer->locked = false;

    return ret;
}


void LorieBuffer_sendHandleToUnixSocket(LorieBuffer* _Nonnull buffer, int socketFd) {
    if (socketFd < 0 || !buffer)
        return;

    tlog(LOG_INFO, "Sending LorieBuffer payload sizeof(LorieBuffer)=%zu sizeof(desc)=%zu width=%d stride=%d height=%d format=%d type=%d id=%llu",
         sizeof(*buffer), sizeof(buffer->desc), buffer->desc.width, buffer->desc.stride,
         buffer->desc.height, buffer->desc.format, buffer->desc.type,
         (unsigned long long) buffer->desc.id);

    if (writeFullToSocket(socketFd, buffer, sizeof(*buffer)) != 0)
        return;

    if (buffer->desc.type == LORIEBUFFER_FD)
        ancil_send_fd(socketFd, buffer->fd);
#ifndef TERMUX_RENDER_FD_ONLY
    else if (buffer->desc.type == LORIEBUFFER_AHARDWAREBUFFER)
        androidHardwareBufferSendHandleToUnixSocket(buffer->desc.buffer, socketFd);
#endif
}

void LorieBuffer_recvHandleFromUnixSocket(int socketFd, LorieBuffer** outBuffer) {
    LorieBuffer buffer = {0}, *ret = NULL;
    // We should read buffer from socket despite outbuffer is NULL, otherwise we will get protocol error
    if (socketFd < 0)
        return;
    tlog(LOG_INFO, "Receiving LorieBuffer from socket fd=%d sizeof(LorieBuffer)=%zu sizeof(desc)=%zu",
         socketFd, sizeof(buffer), sizeof(buffer.desc));

    // Reset process-specific data;
    buffer.refcount = 0;
    buffer.locked = false;
    buffer.fd = -1;
    buffer.lockedData = NULL;
    __sync_fetch_and_add(&buffer.refcount, 1); // refcount is the first object in the struct

    if (readFullFromSocket(socketFd, &buffer, sizeof(buffer)) <= 0) {
        if (outBuffer)
            *outBuffer = NULL;
        tlog(LOG_ERR, "Failed to receive LorieBuffer payload");
        return;
    }
    buffer.refcount = 1;
    buffer.locked = false;
    buffer.lockedData = NULL;
    buffer.fd = -1;
    buffer.id = 0;
    buffer.image = NULL; // Only for process-local use
    tlog(LOG_INFO, "Received raw LorieBuffer desc width=%d stride=%d height=%d format=%d type=%d id=%llu fd=%d",
         buffer.desc.width, buffer.desc.stride, buffer.desc.height,
         buffer.desc.format, buffer.desc.type, (unsigned long long) buffer.desc.id, buffer.fd);
    if (buffer.desc.type == LORIEBUFFER_FD) {
        size_t size = buffer.desc.stride * buffer.desc.height * sizeof(uint32_t);
        buffer.fd = ancil_recv_fd(socketFd);
        tlog(LOG_INFO, "Received LorieBuffer fd handle=%d", buffer.fd);
        if (buffer.fd == -1) {
            if (outBuffer)
                *outBuffer = NULL;
            return;
        }

        buffer.desc.data = mmap(NULL, size, PROT_READ|PROT_WRITE, MAP_SHARED, buffer.fd, 0);
        if (buffer.desc.data == NULL || buffer.desc.data == MAP_FAILED) {
            close(buffer.fd);
            if (outBuffer)
                *outBuffer = NULL;
            return;
        }
    } else if (buffer.desc.type == LORIEBUFFER_AHARDWAREBUFFER) {
#ifdef TERMUX_RENDER_FD_ONLY
        tlog(LOG_ERR, "Received AHardwareBuffer in fd-only build");
        if (outBuffer)
            *outBuffer = NULL;
        return;
#else
        if (androidHardwareBufferRecvHandleFromUnixSocket(socketFd,
                                                          &buffer.desc.buffer) != 0) {
            tlog(LOG_ERR, "Failed to receive AHardwareBuffer handle: %s",
                 strerror(errno));
            if (outBuffer)
                *outBuffer = NULL;
            return;
        }
        tlog(LOG_INFO, "Received AHardwareBuffer handle=%p", buffer.desc.buffer);
#endif
    }

#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
    if (outBuffer)
        ret = calloc(1, sizeof(buffer));
#pragma clang diagnostic pop
    if (!ret) {
        if (buffer.fd >= 0)
            close(buffer.fd);
#ifndef TERMUX_RENDER_FD_ONLY
        if (buffer.desc.buffer)
            androidHardwareBufferRelease(buffer.desc.buffer);
#endif
        if (outBuffer)
            *outBuffer = NULL;
        tlog(LOG_ERR, "Failed to allocate client LorieBuffer copy");
        return;
    }

    *ret = buffer;
    xorg_list_init(&ret->link);
    *outBuffer = ret;
    tlog(LOG_INFO, "LorieBuffer receive completed out=%p", ret);
}

int LorieBuffer_getWidth(LorieBuffer *buffer) {
    return LorieBuffer_description(buffer)->width;
}

int LorieBuffer_getHeight(LorieBuffer *buffer) {
    return LorieBuffer_description(buffer)->height;
}

bool LorieBuffer_isRgba(LorieBuffer *buffer) {
    return LorieBuffer_description(buffer)->format != AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
}

void LorieBuffer_addToList(LorieBuffer* _Nullable buffer, struct xorg_list* _Nullable list) {
    if (buffer && list) {
        xorg_list_del(&buffer->link);
        xorg_list_add(&buffer->link, list);
    }
}

void LorieBuffer_removeFromList(LorieBuffer* _Nullable buffer) {
    if (buffer)
        xorg_list_del(&buffer->link);
}

LorieBuffer* _Nullable LorieBufferList_first(struct xorg_list* _Nullable list) {
    return !list || xorg_list_is_empty(list) ? NULL : xorg_list_first_entry(list, LorieBuffer, link);
}

LorieBuffer* _Nullable LorieBufferList_findById(struct xorg_list* _Nullable list, uint64_t id) {
    LorieBuffer *buffer;

    if (!list)
        return NULL;

    xorg_list_for_each_entry(buffer, list, link)
        if (buffer->desc.id == id)
            return buffer;
    return NULL;
}

int ancil_send_fd(int sock, int fd) {
    char nothing = '!';
    struct iovec nothing_ptr = { .iov_base = &nothing, .iov_len = 1 };

    struct {
        struct cmsghdr align;
        int fd[1];
    } ancillary_data_buffer;

    struct msghdr message_header = {
            .msg_name = NULL,
            .msg_namelen = 0,
            .msg_iov = &nothing_ptr,
            .msg_iovlen = 1,
            .msg_flags = 0,
            .msg_control = &ancillary_data_buffer,
            .msg_controllen = sizeof(struct cmsghdr) + sizeof(int)
    };

#pragma clang diagnostic push
#pragma ide diagnostic ignored "NullDereference"
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&message_header);
    cmsg->cmsg_len = message_header.msg_controllen;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    ((int*) CMSG_DATA(cmsg))[0] = fd;
#pragma clang diagnostic pop

    if (sendmsg(sock, &message_header, 0) < 0) {
        tlog(LOG_ERR, "ancil_send_fd failed: %s", strerror(errno));
        return -1;
    }

    return 0;
}

int ancil_recv_fd(int sock) {
    char nothing = '!';
    struct iovec nothing_ptr = { .iov_base = &nothing, .iov_len = 1 };

    struct {
        struct cmsghdr align;
        int fd[1];
    } ancillary_data_buffer;

    struct msghdr message_header = {
            .msg_name = NULL,
            .msg_namelen = 0,
            .msg_iov = &nothing_ptr,
            .msg_iovlen = 1,
            .msg_flags = 0,
            .msg_control = &ancillary_data_buffer,
            .msg_controllen = sizeof(struct cmsghdr) + sizeof(int)
    };

#pragma clang diagnostic push
#pragma ide diagnostic ignored "NullDereference"
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&message_header);
    cmsg->cmsg_len = message_header.msg_controllen;
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    ((int*) CMSG_DATA(cmsg))[0] = -1;
#pragma clang diagnostic pop

    if (recvmsg(sock, &message_header, 0) < 0) {
        tlog(LOG_ERR, "ancil_recv_fd failed: %s", strerror(errno));
        return -1;
    }

    int fd = ((int*) CMSG_DATA(cmsg))[0];
    tlog(LOG_INFO, "ancil_recv_fd sock=%d fd=%d", sock, fd);
    return fd;
}
