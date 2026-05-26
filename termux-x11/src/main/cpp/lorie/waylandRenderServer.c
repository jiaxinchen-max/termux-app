#include <jni.h>
#include <android/log.h>
#include <sys/socket.h>
#include <linux/in.h>
#include <string.h>
#include <sys/endian.h>
#include <errno.h>
#include <unistd.h>
#include <pthread.h>
#include <list.h>
#include <sys/un.h>
#include <android/looper.h>
#include <sys/mman.h>
#include <asm-generic/ioctls.h>
#include <unistd.h>
#include <sys/mman.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <signal.h>
#include "waylandRenderServer.h"
#include "buffer.h"
#include "lorie.h"

#define MAX_WAITING_CONNECT_CLIENTS 5
#define SOCKET_DIR "/data/data/com.termux/files/home/tmp"
#define SOCKET_PATH SOCKET_DIR "/termux-render"
#ifndef TERMUX_RENDER_USE_SEQPACKET
#define TERMUX_RENDER_USE_SEQPACKET 0
#endif

#if TERMUX_RENDER_USE_SEQPACKET && defined(SOCK_SEQPACKET)
#define RENDER_SOCKET_TYPE SOCK_SEQPACKET
#define RENDER_SOCKET_TYPE_NAME "SOCK_SEQPACKET"
#define RENDER_INPUT_SOCKET_TYPE SOCK_SEQPACKET
#define RENDER_INPUT_SOCKET_TYPE_NAME "SOCK_SEQPACKET"
#else
#define RENDER_SOCKET_TYPE SOCK_STREAM
#define RENDER_SOCKET_TYPE_NAME "SOCK_STREAM"
#define RENDER_INPUT_SOCKET_TYPE SOCK_STREAM
#define RENDER_INPUT_SOCKET_TYPE_NAME "SOCK_STREAM"
#endif
#define log(prio, ...) __android_log_print(ANDROID_LOG_ ## prio, "LorieNative", __VA_ARGS__)
#define min(a, b) (((a) < (b)) ? (a) : (b))
static int event_fd = -1;
static int render_input_fd = -1;
static struct lorie_shared_server_state *shared_state = NULL;
static int shared_state_fd = -1;
static volatile int connection_alive = 1;
static pthread_mutex_t render_connection_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t render_server_init_lock = PTHREAD_MUTEX_INITIALIZER;
static bool render_server_started = false;
static pid_t render_client_pid = -1;
static pid_t render_client_pgid = -1;

static const char *eventTypeName(uint8_t type) {
    switch (type) {
    case EVENT_SHARED_SERVER_STATE: return "EVENT_SHARED_SERVER_STATE";
    case EVENT_ADD_BUFFER: return "EVENT_ADD_BUFFER";
    case EVENT_REMOVE_BUFFER: return "EVENT_REMOVE_BUFFER";
    case EVENT_SCREEN_SIZE: return "EVENT_SCREEN_SIZE";
    case EVENT_APPLY_SERVER_STATE: return "EVENT_APPLY_SERVER_STATE";
    case EVENT_APPLY_BUFFER: return "EVENT_APPLY_BUFFER";
    case EVENT_APPLY_EVENT_FD: return "EVENT_APPLY_EVENT_FD";
    case EVENT_SHARED_EVENT_FD: return "EVENT_SHARED_EVENT_FD";
    case EVENT_SERVER_VERIFY_SUCCEED: return "EVENT_SERVER_VERIFY_SUCCEED";
    case EVENT_CLIENT_VERIFY_SUCCEED: return "EVENT_CLIENT_VERIFY_SUCCEED";
    case EVENT_STOP_RENDER: return "EVENT_STOP_RENDER";
    default: return "UNKNOWN";
    }
}

extern struct {
    jclass self;
    jmethodID getInstance, clientConnectedStateChanged, resetIme, onRenderConnectionChanged;
} LorieViewRuntimeRegistry;

static int textureId = 0;

static void notifyRenderConnectionChanged(JNIEnv *env) {
    if (!env)
        return;

    jobject instance = (*env)->CallStaticObjectMethod(env,
                                                      LorieViewRuntimeRegistry.self,
                                                      LorieViewRuntimeRegistry.getInstance);
    if (instance)
        (*env)->CallVoidMethod(env, instance,
                               LorieViewRuntimeRegistry.onRenderConnectionChanged);
}

static void setRenderInputFd(int fd) {
    pthread_mutex_lock(&render_connection_lock);
    if (render_input_fd != -1)
        close(render_input_fd);
    render_input_fd = fd;
    pthread_mutex_unlock(&render_connection_lock);
}

bool waylandRenderConnected(void) {
    pthread_mutex_lock(&render_connection_lock);
    bool connected = event_fd != -1 && connection_alive;
    pthread_mutex_unlock(&render_connection_lock);
    return connected;
}

static void setRenderClientProcess(pid_t pid, pid_t pgid) {
    pthread_mutex_lock(&render_connection_lock);
    render_client_pid = pid;
    render_client_pgid = pgid;
    pthread_mutex_unlock(&render_connection_lock);
}

static void updateRenderClientProcess(int fd) {
    struct ucred cred = {0};
    socklen_t len = sizeof(cred);

    cred.pid = -1;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) == -1) {
        log(ERROR, "Failed to get render client credentials: %s", strerror(errno));
        setRenderClientProcess(-1, -1);
        return;
    }

    pid_t pgid = cred.pid > 0 ? getpgid(cred.pid) : -1;
    if (pgid < 0)
        log(ERROR, "Failed to get render client pgid pid=%d: %s", cred.pid, strerror(errno));

    log(INFO, "Render client process pid=%d pgid=%d", cred.pid, pgid);
    setRenderClientProcess(cred.pid, pgid);
}

bool waylandRenderKillExternalServer(int signal) {
    pid_t pid;
    pid_t pgid;
    pid_t self_pgid = getpgrp();

    pthread_mutex_lock(&render_connection_lock);
    pid = render_client_pid;
    pgid = render_client_pgid;
    pthread_mutex_unlock(&render_connection_lock);

    if (pgid > 1 && pgid != self_pgid) {
        if (kill(-pgid, signal) == 0) {
            log(INFO, "Sent signal %d to render client process group %d", signal, pgid);
            return true;
        }
        log(ERROR, "Failed to signal render client process group %d: %s", pgid, strerror(errno));
    }

    if (pid > 1 && pid != getpid()) {
        if (kill(pid, signal) == 0) {
            log(INFO, "Sent signal %d to render client pid %d", signal, pid);
            return true;
        }
        log(ERROR, "Failed to signal render client pid %d: %s", pid, strerror(errno));
    }

    return false;
}

bool waylandRenderSendEvent(const lorieEvent *event, const void *payload, size_t payloadSize) {
    bool sent = false;

    pthread_mutex_lock(&render_connection_lock);
    if (render_input_fd == -1)
        goto out;

    if (write(render_input_fd, event, sizeof(*event)) != sizeof(*event))
        goto out;

    if (payload && payloadSize && write(render_input_fd, payload, payloadSize) != (ssize_t) payloadSize)
        goto out;

    sent = true;

out:
    pthread_mutex_unlock(&render_connection_lock);
    return sent;
}

static int readFull(int fd, void *buffer, size_t size) {
    size_t offset = 0;

    while (offset < size) {
        ssize_t count = read(fd, (char *) buffer + offset, size - offset);
        if (count > 0) {
            offset += count;
            continue;
        }

        if (count == 0) {
            if (offset == 0)
                return 0;
            errno = ECONNRESET;
            return -1;
        }

        if (errno == EINTR)
            continue;

        if ((errno == EAGAIN || errno == EWOULDBLOCK) && offset == 0)
            return -2;

        return -1;
    }

    return 1;
}

static int readLorieEvent(int fd, lorieEvent *event) {
    memset(event, 0, sizeof(*event));
#if TERMUX_RENDER_USE_SEQPACKET && defined(SOCK_SEQPACKET)
    ssize_t count;
    do {
        count = read(fd, event, sizeof(*event));
    } while (count < 0 && errno == EINTR);

    if (count == 0)
        return 0;

    if (count < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            return -2;
        return -1;
    }

    if ((size_t) count != sizeof(*event)) {
        log(WARN, "Skipping non-event packet size=%zd expected=%zu first_byte=%u",
            count, sizeof(*event), event->type);
        return -2;
    }

    return 1;
#else
    return readFull(fd, event, sizeof(*event));
#endif
}

static void waylandSendSharedServerState(int fd, int memfd) {
    lorieEvent e = {.type = EVENT_SHARED_SERVER_STATE};
    log(INFO, "Sending EVENT_SHARED_SERVER_STATE memfd=%d sizeof(lorieEvent)=%zu", memfd, sizeof(lorieEvent));
    write(fd, &e, sizeof(e));
    int ret = ancil_send_fd(fd, memfd);
    log(INFO, "Sent EVENT_SHARED_SERVER_STATE fd result=%d", ret);
}

static void waylandRegisterBuffer(int fd, LorieBuffer *buffer) {
    if (!buffer) {
        log(ERROR, "Failed to allocate Wayland render buffer");
        return;
    }

    unsigned long id = LorieBuffer_description(buffer)->id;
    textureId = id;
    lorieEvent e = {.type = EVENT_ADD_BUFFER};
    log(INFO, "Sending EVENT_ADD_BUFFER sizeof(lorieEvent)=%zu", sizeof(lorieEvent));
    write(fd, &e, sizeof(e));
    LorieBuffer_sendHandleToUnixSocket(buffer, fd);
    rendererAddBuffer(buffer);
    const LorieBuffer_Desc *desc = LorieBuffer_description(buffer);
    log(INFO, "Sent shared buffer width %d stride %d height %d format %d type %d id %llu",
        desc->width, desc->stride, desc->height, desc->format, desc->type, (unsigned long long) desc->id);
}

static void waylandRegisterClientBuffer(int fd) {
    LorieBuffer *buffer = NULL;

    LorieBuffer_recvHandleFromUnixSocket(fd, &buffer);
    if (!buffer) {
        log(ERROR, "EVENT_ADD_BUFFER did not contain a valid buffer");
        return;
    }

    rendererAddBuffer(buffer);
    const LorieBuffer_Desc *desc = LorieBuffer_description(buffer);
    log(INFO, "Registered client buffer width %d stride %d height %d format %d type %d id %llu",
        desc->width, desc->stride, desc->height, desc->format, desc->type,
        (unsigned long long) desc->id);
}
static void cleanupSharedResources(JNIEnv *env);

static void cleanupSharedResources(JNIEnv *env) {
    connection_alive = 0;

    rendererSetSharedState(NULL);
    rendererSetExternalBufferMode(false);
    shared_state = NULL;

    if (shared_state_fd != -1) {
        close(shared_state_fd);
        shared_state_fd = -1;
    }
    rendererRemoveAllBuffers();

    setRenderInputFd(-1);
    notifyRenderConnectionChanged(env);
}

static int process(JNIEnv *env, int fd) {
    if (fd == -1) {
        return 0;
    }

    connection_alive = 1;

    while (connection_alive) {
        lorieEvent e = {0};
        int readStatus = readLorieEvent(fd, &e);
        if (readStatus > 0) {
            log(INFO, "Received event type=%u (%s)", e.type, eventTypeName(e.type));
            switch (e.type) {
                case EVENT_APPLY_BUFFER: {
                    log(INFO, "Handling EVENT_APPLY_BUFFER");
                    lorieEvent e2 = {0};
                    int width;
                    int height;
                    int expectedWidth = 0;
                    int expectedHeight = 0;

                    if (readLorieEvent(fd, &e2) <= 0) {
                        log(ERROR, "Failed to read complete screen size event");
                        cleanupSharedResources(env);
                        return -1;
                    }
                    log(INFO, "Received EVENT_SCREEN_SIZE width=%d height=%d framerate=%d format=%d type=%d",
                        e2.screenSize.width, e2.screenSize.height, e2.screenSize.framerate,
                        e2.screenSize.format, e2.screenSize.type);

                    width = e2.screenSize.width;
                    height = e2.screenSize.height;
                    rendererGetExpectedSize(&expectedWidth, &expectedHeight);
                    if (expectedWidth > 0 && expectedHeight > 0 &&
                        (expectedWidth != width || expectedHeight != height)) {
                        log(INFO, "Using LorieView resolution %dx%d instead of requested %dx%d",
                            expectedWidth, expectedHeight, width, height);
                        width = expectedWidth;
                        height = expectedHeight;
                    }

                    LorieBuffer *buffer = LorieBuffer_allocate(width,
                                                               height,
                                                               e2.screenSize.format,
                                                               e2.screenSize.type);
                    waylandRegisterBuffer(fd,buffer);
                    break;
                }
                case EVENT_APPLY_SERVER_STATE: {
                    log(INFO, "Handling EVENT_APPLY_SERVER_STATE");
                    struct lorie_shared_server_state *state = NULL;
                    int stateFd = LorieBuffer_createRegion("wayland", sizeof(*state));
                    if (stateFd == -1) {
                        log(ERROR, "FATAL: Failed to allocate server state.");
                        _exit(1);
                    }

                    state = mmap(NULL, sizeof(*state), PROT_READ | PROT_WRITE, MAP_SHARED,
                                 stateFd, 0);
                    if (state == MAP_FAILED) {
                        log(ERROR, "FATAL: Failed to map server state.");
                        _exit(1);
                    }

                    // Initialize cross-process synchronization primitives
                    pthread_mutexattr_t mutex_attr;
                    pthread_condattr_t cond_attr;

                    pthread_mutexattr_init(&mutex_attr);
                    pthread_mutexattr_setpshared(&mutex_attr, PTHREAD_PROCESS_SHARED);
                    pthread_mutexattr_settype(&mutex_attr, PTHREAD_MUTEX_RECURSIVE);
                    pthread_mutex_init(&state->lock, &mutex_attr);
                    pthread_mutex_init(&state->cursor.lock, &mutex_attr);

                    pthread_condattr_init(&cond_attr);
                    pthread_condattr_setpshared(&cond_attr, PTHREAD_PROCESS_SHARED);
                    pthread_cond_init(&state->cond, &cond_attr);

                    pthread_mutexattr_destroy(&mutex_attr);
                    pthread_condattr_destroy(&cond_attr);

                    log(DEBUG, "lorie_shared_server_state:%p", state);
                    state->rootWindowTextureID = textureId;
                    waylandSendSharedServerState(fd,stateFd);
                    shared_state = state;
                    shared_state_fd = stateFd;
                    rendererSetExternalBufferMode(true);
                    rendererSetSharedState(state);
                    break;
                }
                case EVENT_APPLY_EVENT_FD:{
                    log(INFO, "Handling EVENT_APPLY_EVENT_FD");
                    lorieEvent e1 = {.type = EVENT_SHARED_EVENT_FD};
                    log(INFO, "Sending EVENT_SHARED_EVENT_FD");
                    if (write(event_fd, &e1, sizeof(e1)) != sizeof(e1)) {
                        log(ERROR, "Failed to send SHARED_EVENT_FD");
                        _exit(1);
                    }
                    int client[2];
                    if (socketpair(AF_UNIX, RENDER_INPUT_SOCKET_TYPE, 0, client) != 0) {
                        log(ERROR, "Failed to create render input socketpair type=%s: %s",
                            RENDER_INPUT_SOCKET_TYPE_NAME, strerror(errno));
                        _exit(1);
                    }
                    setRenderInputFd(client[0]);
                    int ret = ancil_send_fd(fd,client[1]);
                    log(INFO, "Sent EVENT_SHARED_EVENT_FD fd=%d type=%s result=%d",
                        client[1], RENDER_INPUT_SOCKET_TYPE_NAME, ret);
                    close(client[1]);
                    break;
                }
                case EVENT_ADD_BUFFER: {
                    log(INFO, "Handling EVENT_ADD_BUFFER");
                    waylandRegisterClientBuffer(fd);
                    break;
                }
                case EVENT_REMOVE_BUFFER: {
                    log(INFO, "Handling EVENT_REMOVE_BUFFER id=%lu", e.removeBuffer.id);
                    rendererRemoveBuffer(e.removeBuffer.id);
                    break;
                }
                case EVENT_CLIENT_VERIFY_SUCCEED: {
                    log(INFO, "Handling EVENT_CLIENT_VERIFY_SUCCEED");
                    notifyRenderConnectionChanged(env);
                    break;
                }
                case EVENT_STOP_RENDER: {
                    cleanupSharedResources(env);
                    return 0;
                }
                default:
                    log(DEBUG, "Unknown event type: %d (%s)", e.type, eventTypeName(e.type));
                    break;
            }
        } else if (readStatus == 0) {
            cleanupSharedResources(env);
            return 0;
        } else if (readStatus == -2) {
            continue;
        } else {
            log(ERROR, "Failed to read complete event: %s", strerror(errno));
            cleanupSharedResources(env);
            return -1;
        }
    }
    cleanupSharedResources(env);
    return 0;
}

static void startRenderServer(JavaVM *vm) {
    int server_fd, client_fd, count;
    struct sockaddr_un address;
    uint8_t buffer[512] = {0};

    // 创建socket
    server_fd = socket(AF_UNIX, RENDER_SOCKET_TYPE, 0);
    if (server_fd < 0) {
        log(ERROR, "Socket creation failed: %s", strerror(errno));
        return;
    }
    log(INFO, "Render server socket type=%s path=%s", RENDER_SOCKET_TYPE_NAME, SOCKET_PATH);

    // 确保目录存在
    char socket_parent_dir[] = "/data/data/com.termux/files/home";
    if (access(socket_parent_dir, F_OK) != 0) {
        if (mkdir(socket_parent_dir, 0700) != 0 && errno != EEXIST) {
            log(ERROR, "Failed to create socket parent directory: %s", strerror(errno));
        }
    }
    if (access(SOCKET_DIR, F_OK) != 0) {
        if (mkdir(SOCKET_DIR, 0700) != 0 && errno != EEXIST) {
            log(ERROR, "Failed to create socket directory: %s", strerror(errno));
        }
    }
    
    // 绑定socket文件路径，先unlink避免路径已存在
    unlink(SOCKET_PATH);
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, SOCKET_PATH, sizeof(address.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr *) &address, sizeof(address)) < 0) {
        log(ERROR, "Socket bind failed: %s", strerror(errno));
        close(server_fd);
        return;
    }

    // 监听连接
    if (listen(server_fd, MAX_WAITING_CONNECT_CLIENTS) < 0) {
        log(ERROR, "Socket listen failed: %s", strerror(errno));
        close(server_fd);
        unlink(SOCKET_PATH);
        return;
    }

    while (1) {
        client_fd = accept(server_fd, NULL, NULL);
        if (client_fd < 0) {
            log(ERROR, "Socket accept failed: %s", strerror(errno));
            continue;
        }

        count = read(client_fd, buffer, sizeof(buffer));
        if (count > 0) {
            if (!memcmp(buffer, MAGIC, count < (int) sizeof(MAGIC) ? count : (int) sizeof(MAGIC))) {
                log(DEBUG, "New client connection!");
                updateRenderClientProcess(client_fd);
                lorieEvent e = {.type = EVENT_SERVER_VERIFY_SUCCEED};
                write(client_fd, &e, sizeof(e));
                event_fd = client_fd;

                JNIEnv *env = NULL;
                (*vm)->AttachCurrentThread(vm, &env, NULL);
                process(env, client_fd);
                close(client_fd);
                event_fd = -1;
                setRenderClientProcess(-1, -1);
                (*vm)->DetachCurrentThread(vm);
            } else {
                close(client_fd);
                log(ERROR, "Invalid client connection!");
            }
        }
    }

    close(server_fd);
    unlink(SOCKET_PATH);
}

void waylandRenderInit(JavaVM *vm) {
    pthread_t t;
    pthread_mutex_lock(&render_server_init_lock);
    if (render_server_started) {
        pthread_mutex_unlock(&render_server_init_lock);
        return;
    }

    render_server_started = true;
    pthread_create(&t, NULL, (void *(*)(void *)) startRenderServer, vm);
    pthread_detach(t);
    pthread_mutex_unlock(&render_server_init_lock);
}
