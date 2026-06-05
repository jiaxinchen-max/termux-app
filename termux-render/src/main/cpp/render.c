#ifndef TERMUX_RENDER_FD_ONLY
#include <jni.h>
#endif
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include "include/buffer.h"
#define TERMUX_RENDER_NO_SET_SCREEN_CONFIG_MACRO
#include "include/render.h"
#include "include/tlog.h"

static int event_fd = -1, conn_fd=-1, stateFd = -1;
static int connect_retry = 0;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t control_write_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
static volatile int buffer_ready = 0;
static volatile int event_loop_running = 0;
static pthread_t event_thread_id = 0;

typedef enum {
    HANDSHAKE_WAIT_SERVER_VERIFY,
    HANDSHAKE_WAIT_ADD_BUFFER,
    HANDSHAKE_WAIT_SERVER_STATE,
    HANDSHAKE_WAIT_EVENT_FD,
    HANDSHAKE_COMPLETE,
} handshakePhase;

static volatile handshakePhase handshake_phase = HANDSHAKE_WAIT_SERVER_VERIFY;

static void (*onExitCallback)(void) = NULL;

static int screen_width = 1080;
static int screen_height = 720;
static int screen_framerate = 10;
static int screen_format = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
#ifdef TERMUX_RENDER_FD_ONLY
static int screen_type = LORIEBUFFER_FD;
#else
static int screen_type = LORIEBUFFER_AHARDWAREBUFFER;
#endif

#define MAX_RETRY_TIMES 5

#ifndef TERMUX_RENDER_USE_SEQPACKET
#define TERMUX_RENDER_USE_SEQPACKET 0
#endif

#if TERMUX_RENDER_USE_SEQPACKET && defined(SOCK_SEQPACKET)
#define RENDER_SOCKET_TYPE SOCK_SEQPACKET
#define RENDER_SOCKET_TYPE_NAME "SOCK_SEQPACKET"
#else
#define RENDER_SOCKET_TYPE SOCK_STREAM
#define RENDER_SOCKET_TYPE_NAME "SOCK_STREAM"
#endif

#define SOCKET_DIR "/data/data/com.termux/files/home/tmp"
#define SOCKET_PATH SOCKET_DIR "/termux-render"

LorieBuffer *lorieBuffer;
struct lorie_shared_server_state *serverState;

static bool isSupportedScreenFormat(int format) {
    return format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM ||
           format == AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;
}

static bool isSupportedScreenType(int type) {
    return type == LORIEBUFFER_FD
#ifndef TERMUX_RENDER_FD_ONLY
           || type == LORIEBUFFER_AHARDWAREBUFFER
#endif
           ;
}

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

static const char *handshakePhaseName(handshakePhase phase) {
    switch (phase) {
    case HANDSHAKE_WAIT_SERVER_VERIFY: return "WAIT_SERVER_VERIFY";
    case HANDSHAKE_WAIT_ADD_BUFFER: return "WAIT_ADD_BUFFER";
    case HANDSHAKE_WAIT_SERVER_STATE: return "WAIT_SERVER_STATE";
    case HANDSHAKE_WAIT_EVENT_FD: return "WAIT_EVENT_FD";
    case HANDSHAKE_COMPLETE: return "COMPLETE";
    default: return "UNKNOWN";
    }
}

static bool isKnownEventType(uint8_t type) {
    switch (type) {
    case EVENT_SHARED_SERVER_STATE:
    case EVENT_ADD_BUFFER:
    case EVENT_REMOVE_BUFFER:
    case EVENT_SCREEN_SIZE:
    case EVENT_TOUCH:
    case EVENT_MOUSE:
    case EVENT_KEY:
    case EVENT_STYLUS:
    case EVENT_STYLUS_ENABLE:
    case EVENT_UNICODE:
    case EVENT_CLIPBOARD_ENABLE:
    case EVENT_CLIPBOARD_ANNOUNCE:
    case EVENT_CLIPBOARD_REQUEST:
    case EVENT_CLIPBOARD_SEND:
    case EVENT_WINDOW_FOCUS_CHANGED:
    case EVENT_APPLY_SERVER_STATE:
    case EVENT_APPLY_BUFFER:
    case EVENT_APPLY_EVENT_FD:
    case EVENT_SHARED_EVENT_FD:
    case EVENT_SERVER_VERIFY_SUCCEED:
    case EVENT_CLIENT_VERIFY_SUCCEED:
    case EVENT_STOP_RENDER:
        return true;
    default:
        return false;
    }
}

static bool handshakeAllowsEvent(uint8_t type) {
    switch (handshake_phase) {
    case HANDSHAKE_WAIT_SERVER_VERIFY:
        return type == EVENT_SERVER_VERIFY_SUCCEED;
    case HANDSHAKE_WAIT_ADD_BUFFER:
        return type == EVENT_ADD_BUFFER;
    case HANDSHAKE_WAIT_SERVER_STATE:
        return type == EVENT_SHARED_SERVER_STATE;
    case HANDSHAKE_WAIT_EVENT_FD:
        return type == EVENT_SHARED_EVENT_FD;
    case HANDSHAKE_COMPLETE:
        return true;
    default:
        return false;
    }
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

static int writeFull(int fd, const void *buffer, size_t size) {
    size_t offset = 0;

    while (offset < size) {
        ssize_t count = write(fd, (const char *) buffer + offset, size - offset);
        if (count > 0) {
            offset += count;
            continue;
        }

        if (count < 0 && errno == EINTR)
            continue;

        return -1;
    }

    return 0;
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
        tlog(LOG_ERR, "Protocol error during handshake: unexpected packet size=%zd expected=%zu first_byte=%u phase=%s",
             count, sizeof(*event), event->type, handshakePhaseName(handshake_phase));
        errno = EPROTO;
        return -1;
    }

    if (!isKnownEventType(event->type)) {
        tlog(LOG_ERR, "Protocol error during handshake: unknown event type=%u packet size=%zd phase=%s",
             event->type, count, handshakePhaseName(handshake_phase));
        errno = EPROTO;
        return -1;
    }

    if (handshake_phase != HANDSHAKE_COMPLETE && !handshakeAllowsEvent(event->type)) {
        tlog(LOG_ERR, "Protocol error during handshake: unexpected event type=%u (%s) phase=%s",
             event->type, eventTypeName(event->type), handshakePhaseName(handshake_phase));
        errno = EPROTO;
        return -1;
    }

    return 1;
#else
    int ret = readFull(fd, event, sizeof(*event));
    if (ret <= 0)
        return ret;

    if (!isKnownEventType(event->type)) {
        tlog(LOG_ERR, "Protocol error during handshake: unknown event type=%u phase=%s",
             event->type, handshakePhaseName(handshake_phase));
        errno = EPROTO;
        return -1;
    }

    if (handshake_phase != HANDSHAKE_COMPLETE && !handshakeAllowsEvent(event->type)) {
        tlog(LOG_ERR, "Protocol error during handshake: unexpected event type=%u (%s) phase=%s",
             event->type, eventTypeName(event->type), handshakePhaseName(handshake_phase));
        errno = EPROTO;
        return -1;
    }

    return 1;
#endif
}

#ifndef TERMUX_RENDER_FD_ONLY
JNIEXPORT jstring JNICALL
Java_com_termux_wayland_NativeLib_stringFromJNI(
        JNIEnv *env,
        jobject this) {
    return "hello";
}
#endif

bool waylandConnectionAlive(void) {
    return event_fd != -1 && event_loop_running && lorieBuffer && serverState;
}

void setExitCallback(void (*callback)(void)) {
    onExitCallback = callback;
}

int get_conn_fd(void) {
    return conn_fd;
}

LorieBuffer *get_lorieBuffer(void) {
    return lorieBuffer;
}

struct lorie_shared_server_state *get_serverState(void) {
    return serverState;
}

int registerBufferToRender(LorieBuffer *buffer) {
    lorieEvent event = {.type = EVENT_ADD_BUFFER};

    if (!buffer || event_fd < 0 || handshake_phase != HANDSHAKE_COMPLETE) {
        errno = ENOTCONN;
        return -1;
    }

    pthread_mutex_lock(&control_write_mutex);
    if (writeFull(event_fd, &event, sizeof(event)) != 0) {
        pthread_mutex_unlock(&control_write_mutex);
        return -1;
    }
    LorieBuffer_sendHandleToUnixSocket(buffer, event_fd);
    pthread_mutex_unlock(&control_write_mutex);
    return 0;
}

int unregisterBufferFromRender(LorieBuffer *buffer) {
    const LorieBuffer_Desc *desc;
    lorieEvent event = {0};

    if (!buffer || event_fd < 0 || handshake_phase != HANDSHAKE_COMPLETE) {
        errno = ENOTCONN;
        return -1;
    }

    desc = LorieBuffer_description(buffer);
    event.removeBuffer.t = EVENT_REMOVE_BUFFER;
    event.removeBuffer.id = desc->id;

    pthread_mutex_lock(&control_write_mutex);
    if (writeFull(event_fd, &event, sizeof(event)) != 0) {
        pthread_mutex_unlock(&control_write_mutex);
        return -1;
    }
    pthread_mutex_unlock(&control_write_mutex);
    return 0;
}

static void applyScreenBufferConfig(int format, int type) {
    if (isSupportedScreenFormat(format))
        screen_format = format;
    else
        tlog(LOG_WARNING, "Ignoring unsupported screen buffer format=%d", format);

    if (isSupportedScreenType(type))
        screen_type = type;
    else
        tlog(LOG_WARNING, "Ignoring unsupported screen buffer type=%d", type);
}

void setScreenConfig(int width, int height, int framerate, int option_count, ...) {
    if (width > 0) screen_width = width;
    if (height > 0) screen_height = height;
    if (framerate > 0) screen_framerate = framerate;

    if (option_count >= 2) {
        va_list args;
        int format;
        int type;

        va_start(args, option_count);
        format = va_arg(args, int);
        type = va_arg(args, int);
        va_end(args);

        applyScreenBufferConfig(format, type);
    } else if (option_count != 0) {
        tlog(LOG_WARNING, "Ignoring unsupported setScreenConfig option count=%d",
             option_count);
    }

    tlog(LOG_INFO, "setScreenConfig width=%d height=%d framerate=%d "
         "format=%d type=%d",
         screen_width, screen_height, screen_framerate, screen_format,
         screen_type);
}

static void waylandApplyBuffer() {
    tlog(LOG_INFO, "Handling EVENT_ADD_BUFFER");
    LorieBuffer_recvHandleFromUnixSocket(event_fd, &lorieBuffer);
    if (!lorieBuffer) {
        tlog(LOG_ERR, "EVENT_ADD_BUFFER did not produce a LorieBuffer");
        return;
    }
    const LorieBuffer_Desc *desc = LorieBuffer_description(lorieBuffer);
    tlog(LOG_INFO, "Received buffer width=%d stride=%d height=%d format=%d type=%d id=%llu",
         desc->width, desc->stride, desc->height, desc->format, desc->type,
         (unsigned long long) desc->id);
    lorieEvent e = {.type = EVENT_APPLY_SERVER_STATE};
    if (write(event_fd, &e, sizeof(e)) != sizeof(e)) {
        tlog(LOG_ERR, "Failed to send APPLY_SERVER_STATE");
        exit(EXIT_FAILURE);
    }
    tlog(LOG_INFO, "Sent EVENT_APPLY_SERVER_STATE");
}

static void waylandDestroyBuffer() {
    if (lorieBuffer) {
        lorieBuffer = NULL;
    }
}

static void waylandApplySharedServerState() {
    tlog(LOG_INFO, "Handling EVENT_SHARED_SERVER_STATE");
    stateFd = ancil_recv_fd(event_fd);

    if (stateFd < 0) {
        tlog(LOG_ERR, "Failed to parse server state: %s", strerror(errno));
        exit(EXIT_FAILURE);
    }
    tlog(LOG_INFO, "Received shared server state fd=%d", stateFd);

    serverState = mmap(NULL, sizeof(*serverState), PROT_READ | PROT_WRITE, MAP_SHARED, stateFd, 0);
    if (!serverState || serverState == MAP_FAILED) {
        tlog(LOG_ERR, "Failed to map server state: %s", strerror(errno));
        serverState = NULL;
        exit(EXIT_FAILURE);
    }
    tlog(LOG_INFO, "Mapped shared server state at %p", serverState);
    close(stateFd);
    lorieEvent e = {.type = EVENT_APPLY_EVENT_FD};
    if (write(event_fd, &e, sizeof(e)) != sizeof(e)) {
        tlog(LOG_ERR, "Failed to send APPLY_EVENT_FD");
        exit(EXIT_FAILURE);
    }
    tlog(LOG_INFO, "Sent EVENT_APPLY_EVENT_FD");
}

static void waylandDestroySharedServerState() {
    if (serverState) {
        munmap(serverState, sizeof(*serverState));
        serverState = NULL;
    }
}

static void waylandApplyEventFD(){
    tlog(LOG_INFO, "Handling EVENT_SHARED_EVENT_FD");
    conn_fd = ancil_recv_fd(event_fd);

    if (conn_fd < 0) {
        tlog(LOG_ERR, "Failed to parse event fd: %s", strerror(errno));
        exit(EXIT_FAILURE);
    }
    tlog(LOG_INFO, "Received input event fd=%d", conn_fd);
    serverState->lockingPid = getpid();
    lorieEvent e = {.type = EVENT_CLIENT_VERIFY_SUCCEED};
    write(event_fd, &e, sizeof(e));
    tlog(LOG_INFO, "Sent EVENT_CLIENT_VERIFY_SUCCEED");

    pthread_mutex_lock(&mutex);
    buffer_ready = 1;
    pthread_cond_signal(&cond);
    pthread_mutex_unlock(&mutex);
    tlog(LOG_INFO, "Buffer initialization completed");
}

static void *eventLoopThread(void *arg) {
    event_loop_running = 1;
    tlog(LOG_INFO, "Render blocking event loop started, event_fd=%d sizeof(lorieEvent)=%zu",
         event_fd, sizeof(lorieEvent));

    while (event_loop_running) {
        lorieEvent e = {0};
        int readStatus = readLorieEvent(event_fd, &e);
        if (readStatus > 0) {
            tlog(LOG_INFO, "Received event type=%u (%s)", e.type, eventTypeName(e.type));
            switch (e.type) {
                case EVENT_SERVER_VERIFY_SUCCEED: {
                    tlog(LOG_INFO, "Handling EVENT_SERVER_VERIFY_SUCCEED");
                    lorieEvent e1 = {.type = EVENT_APPLY_BUFFER};
                    if (write(event_fd, &e1, sizeof(e1)) != sizeof(e1)) {
                        tlog(LOG_ERR, "Failed to send APPLY_BUFFER");
                        goto cleanup;
                    }
                    tlog(LOG_INFO, "Sent EVENT_APPLY_BUFFER");
                    lorieEvent e2 = {.screenSize = {.t = EVENT_SCREEN_SIZE, .width = screen_width, .height = screen_height, .framerate = screen_framerate, .format = screen_format, .type = screen_type}};
                    if (write(event_fd, &e2, sizeof(e2)) != sizeof(e2)) {
                        tlog(LOG_ERR, "Failed to send BUFFER PROPERTIES");
                        goto cleanup;
                    }
                    tlog(LOG_INFO, "Sent EVENT_SCREEN_SIZE width=%d height=%d framerate=%d format=%d type=%d",
                         screen_width, screen_height, screen_framerate, screen_format, screen_type);
                    handshake_phase = HANDSHAKE_WAIT_ADD_BUFFER;
                    break;
                }
                case EVENT_SHARED_SERVER_STATE: {
                    waylandApplySharedServerState();
                    handshake_phase = HANDSHAKE_WAIT_EVENT_FD;
                    break;
                }
                case EVENT_ADD_BUFFER: {
                    waylandApplyBuffer();
                    if (!lorieBuffer) {
                        tlog(LOG_ERR, "Protocol error during handshake: EVENT_ADD_BUFFER did not initialize buffer");
                        goto cleanup;
                    }
                    handshake_phase = HANDSHAKE_WAIT_SERVER_STATE;
                    break;
                }
                case EVENT_SHARED_EVENT_FD:{
                    waylandApplyEventFD();
                    handshake_phase = HANDSHAKE_COMPLETE;
                    break;
                }
                case EVENT_STOP_RENDER: {
                    event_loop_running = 0;
                    waylandDestroyBuffer();
                    waylandDestroySharedServerState();
                    if (event_fd != -1) {
                        close(event_fd);
                        event_fd = -1;
                    }
                    if (onExitCallback) {
                        onExitCallback();
                    } else {
                        exit(0);
                    }
                    return NULL;
                }
                default:
                    if (handshake_phase != HANDSHAKE_COMPLETE) {
                        tlog(LOG_ERR, "Protocol error during handshake: unexpected event type=%d (%s) phase=%s",
                             e.type, eventTypeName(e.type), handshakePhaseName(handshake_phase));
                        errno = EPROTO;
                        goto cleanup;
                    }
                    tlog(LOG_WARNING, "Unexpected control event type: %d (%s)", e.type, eventTypeName(e.type));
                    break;
            }
        } else if (readStatus == 0) {
            tlog(LOG_ERR, "Connection closed");
            goto cleanup;
        } else if (readStatus == -2) {
            continue;
        } else {
            tlog(LOG_ERR, "Failed to read complete event: %s", strerror(errno));
            goto cleanup;
        }
    }

    cleanup:
    tlog(LOG_ERR, "Render event loop cleanup, buffer_ready=%d event_fd=%d conn_fd=%d",
         buffer_ready, event_fd, conn_fd);
    pthread_mutex_lock(&mutex);
    if (buffer_ready == 0) {
        buffer_ready = -1;
        pthread_cond_signal(&cond);
    }
    pthread_mutex_unlock(&mutex);

    waylandDestroyBuffer();
    waylandDestroySharedServerState();

    if (event_fd != -1) {
        close(event_fd);
        event_fd = -1;
    }
    if (conn_fd != -1) {
        close(conn_fd);
        conn_fd = -1;
    }

    event_loop_running = 0;
    return NULL;
}

static int waitForInitialization(void) {
    struct timespec timeout;
    clock_gettime(CLOCK_REALTIME, &timeout);
    timeout.tv_sec += 10;

    tlog(LOG_INFO, "Waiting for buffer initialization");
    pthread_mutex_lock(&mutex);
    while (buffer_ready == 0) {
        int ret = pthread_cond_timedwait(&cond, &mutex, &timeout);
        if (ret == ETIMEDOUT) {
            pthread_mutex_unlock(&mutex);
            tlog(LOG_ERR, "Timeout waiting for buffer initialization, lorieBuffer=%p serverState=%p conn_fd=%d event_loop_running=%d",
                 lorieBuffer, serverState, conn_fd, event_loop_running);
            return EXIT_FAILURE;
        }
    }
    int status = buffer_ready;
    pthread_mutex_unlock(&mutex);

    if (status < 0) {
        tlog(LOG_ERR, "Buffer initialization failed");
        return EXIT_FAILURE;
    }

    return 0;
}

int connectToRender() {
    buffer_ready = 0;
    handshake_phase = HANDSHAKE_WAIT_SERVER_VERIFY;
    tlog(LOG_INFO, "connectToRender start socket=%s type=%s sizeof(lorieEvent)=%zu",
         SOCKET_PATH, RENDER_SOCKET_TYPE_NAME, sizeof(lorieEvent));

    for (connect_retry = 0; connect_retry < MAX_RETRY_TIMES; connect_retry++) {
        struct sockaddr_un serverAddr;

        event_fd = socket(AF_UNIX, RENDER_SOCKET_TYPE, 0);
        if (event_fd < 0) {
            tlog(LOG_ERR, "socket: %s", strerror(errno));
            return EXIT_FAILURE;
        }

        memset(&serverAddr, 0, sizeof(serverAddr));
        serverAddr.sun_family = AF_UNIX;
        strncpy(serverAddr.sun_path, SOCKET_PATH, sizeof(serverAddr.sun_path) - 1);

        int ret = connect(event_fd, (const struct sockaddr *) &serverAddr, sizeof(serverAddr));
        if (ret < 0) {
            tlog(LOG_ERR, "connect attempt %d/%d failed: %s", connect_retry + 1, MAX_RETRY_TIMES, strerror(errno));
            close(event_fd);
            event_fd = -1;
            if (connect_retry + 1 < MAX_RETRY_TIMES)
                sleep(5);
            continue;
        }
        tlog(LOG_INFO, "Connected to render socket, event_fd=%d attempt=%d/%d",
             event_fd, connect_retry + 1, MAX_RETRY_TIMES);

        if (pthread_create(&event_thread_id, NULL, eventLoopThread, NULL) != 0) {
            tlog(LOG_ERR, "Failed to create event loop thread");
            close(event_fd);
            event_fd = -1;
            return EXIT_FAILURE;
        }
        tlog(LOG_INFO, "Created render event thread");

        char hello[] = MAGIC;
        if (write(event_fd, hello, sizeof(hello)) != sizeof(hello)) {
            tlog(LOG_ERR, "Failed to send handshake");
            stopEventLoop();
            return EXIT_FAILURE;
        }
        tlog(LOG_INFO, "Sent handshake magic length=%zu", sizeof(hello));

        if (waitForInitialization() != 0) {
            tlog(LOG_ERR, "Resource initialization failed");
            stopEventLoop();
            return EXIT_FAILURE;
        }

        return 0;
    }

    return EXIT_FAILURE;
}

void stopEventLoop(void) {
    if (!event_loop_running) {
        return;
    }

    int fd = event_fd;
    if (fd != -1) {
        lorieEvent e = {.type = EVENT_STOP_RENDER};
        write(fd, &e, sizeof(e));
        shutdown(fd, SHUT_RDWR);
    }

    event_loop_running = 0;

    if (event_thread_id != 0) {
        int waited = 0;
        while (event_loop_running && waited < 50) {
            usleep(100000);
            waited++;
        }

        int ret = pthread_join(event_thread_id, NULL);
        if (ret != 0) {
            tlog(LOG_ERR, "pthread_join failed: %s", strerror(ret));
        }

        event_thread_id = 0;
    }

    waylandDestroyBuffer();
    waylandDestroySharedServerState();

    if (event_fd != -1) {
        close(event_fd);
        event_fd = -1;
    }
    if (conn_fd!=-1){
        close(conn_fd);
        conn_fd=-1;
    }

    pthread_mutex_lock(&mutex);
    buffer_ready = 0;
    pthread_mutex_unlock(&mutex);
}
