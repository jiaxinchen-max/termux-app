#include <jni.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/epoll.h>
#include <sys/mman.h>
#include "include/buffer.h"
#include "include/render.h"
#include "include/tlog.h"

static int event_fd = -1, conn_fd=-1, epfd = -1, stateFd = -1;
static struct epoll_event ev, events[5];
static int connect_retry = 0;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
static volatile int buffer_ready = 0;
static volatile int event_loop_running = 0;
static pthread_t event_thread_id = 0;

static void (*onExitCallback)(void) = NULL;

static int screen_width = 1080;
static int screen_height = 720;
static int screen_framerate = 10;
static int screen_format = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
static int screen_type = LORIEBUFFER_AHARDWAREBUFFER;

#define MAX_RETRY_TIMES 5

#ifdef SOCK_SEQPACKET
#define RENDER_SOCKET_TYPE SOCK_SEQPACKET
#define RENDER_SOCKET_TYPE_NAME "SOCK_SEQPACKET"
#else
#define RENDER_SOCKET_TYPE SOCK_STREAM
#define RENDER_SOCKET_TYPE_NAME "SOCK_STREAM"
#endif

#define SOCKET_DIR "/data/data/com.termux/files/home/tmp"
#define SOCKET_PATH SOCKET_DIR "/wayland-0"

LorieBuffer *lorieBuffer;
struct lorie_shared_server_state *serverState;

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
    return readFull(fd, event, sizeof(*event));
}

JNIEXPORT jstring JNICALL
Java_com_termux_wayland_NativeLib_stringFromJNI(
        JNIEnv *env,
        jobject this) {
    return "hello";
}

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

void setScreenConfig(int width, int height, int framerate) {
    if (width > 0) screen_width = width;
    if (height > 0) screen_height = height;
    if (framerate > 0) screen_framerate = framerate;
    tlog(LOG_INFO, "setScreenConfig width=%d height=%d framerate=%d format=%d type=%d",
         screen_width, screen_height, screen_framerate, screen_format, screen_type);
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
    tlog(LOG_INFO, "Render event loop started, event_fd=%d sizeof(lorieEvent)=%zu",
         event_fd, sizeof(lorieEvent));

    while (event_loop_running) {
        int nfds = epoll_wait(epfd, events, 5, 1000);
        if (nfds == -1) {
            if (errno == EINTR) continue;
            tlog(LOG_ERR, "epoll_wait error: %s", strerror(errno));
            break;
        }

        if (nfds == 0) continue;

        for (int i = 0; i < nfds; ++i) {
            if (events[i].data.fd == event_fd) {
                if (events[i].events & EPOLLIN) {
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
                                break;
                            }
                            case EVENT_SHARED_SERVER_STATE: {
                                waylandApplySharedServerState();
                                break;
                            }
                            case EVENT_ADD_BUFFER: {
                                waylandApplyBuffer();
                                break;
                            }
                            case EVENT_SHARED_EVENT_FD:{
                                waylandApplyEventFD();
                                break;
                            }
                            case EVENT_STOP_RENDER: {
                                event_loop_running = 0;
                                waylandDestroyBuffer();
                                waylandDestroySharedServerState();
                                if (epfd != -1) {
                                    close(epfd);
                                    epfd = -1;
                                }
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
                                tlog(LOG_WARNING, "Unknown event type: %d (%s)", e.type, eventTypeName(e.type));
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
                } else if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                    tlog(LOG_ERR, "Connection error/hangup detected");
                    goto cleanup;
                }
            }
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

    if (epfd != -1) {
        close(epfd);
        epfd = -1;
    }
    if (event_fd != -1) {
        close(event_fd);
        event_fd = -1;
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

        epfd = epoll_create1(0);
        if (epfd == -1) {
            tlog(LOG_ERR, "epoll_create1 failed: %s", strerror(errno));
            close(event_fd);
            event_fd = -1;
            return EXIT_FAILURE;
        }

        ev.events = EPOLLIN;
        ev.data.fd = event_fd;
        if (epoll_ctl(epfd, EPOLL_CTL_ADD, event_fd, &ev) == -1) {
            tlog(LOG_ERR, "epoll_ctl failed: %s", strerror(errno));
            close(event_fd);
            event_fd = -1;
            close(epfd);
            epfd = -1;
            return EXIT_FAILURE;
        }

        if (pthread_create(&event_thread_id, NULL, eventLoopThread, NULL) != 0) {
            tlog(LOG_ERR, "Failed to create event loop thread");
            close(event_fd);
            event_fd = -1;
            close(epfd);
            epfd = -1;
            return EXIT_FAILURE;
        }
        tlog(LOG_INFO, "Created render event thread");

        char hello[] = MAGIC;
        if (write(event_fd, hello, sizeof(hello)) != sizeof(hello)) {
            tlog(LOG_ERR, "Failed to send handshake");
            close(event_fd);
            event_fd = -1;
            close(epfd);
            epfd = -1;
            return EXIT_FAILURE;
        }
        tlog(LOG_INFO, "Sent handshake magic length=%zu", sizeof(hello));

        if (waitForInitialization() != 0) {
            tlog(LOG_ERR, "Resource initialization failed");
            event_loop_running = 0;
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

    if (event_fd != -1) {
        lorieEvent e = {.type = EVENT_STOP_RENDER};
        write(event_fd, &e, sizeof(e));
    }

    waylandDestroyBuffer();
    waylandDestroySharedServerState();

    if (event_fd != -1) {
        close(event_fd);
        event_fd = -1;
    }
    if (epfd != -1) {
        close(epfd);
        epfd = -1;
    }
    if (conn_fd!=-1){
        close(conn_fd);
        conn_fd=-1;
    }

    pthread_mutex_lock(&mutex);
    buffer_ready = 0;
    pthread_mutex_unlock(&mutex);
}
