#include <stdio.h>
#include <signal.h>
#include <sys/time.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>

extern "C" {
#include "include/render.h"
#include "include/tlog.h"

extern LorieBuffer *lorieBuffer;
extern struct lorie_shared_server_state *serverState;
}

#define STB_IMAGE_IMPLEMENTATION
#include "include/stb_image.h"

static volatile sig_atomic_t keep_running = 1;
static char path[256];
static int frame_number = 0;
static const char *animation_folder = "animation_wayland";

#ifndef TERMUX_RENDER_ASSETS_DIR
#define TERMUX_RENDER_ASSETS_DIR "/data/data/com.termux/files/usr/share/termux-render/assets"
#endif

#define MAX_FRAMES 64
struct CachedFrame {
    uint8_t *data;
    int width;
    int height;
    bool loaded;
};

static int frame_count = 0;
static CachedFrame frame_cache[MAX_FRAMES] = {};

static const char *getAssetsDir()
{
    const char *assetsDir = getenv("TERMUX_RENDER_ASSETS_DIR");
    return assetsDir && assetsDir[0] ? assetsDir : TERMUX_RENDER_ASSETS_DIR;
}

static int getAnimationFrameCount()
{
    int count = 0;
    for (int i = 0; i < MAX_FRAMES; i++) {
        snprintf(path, sizeof(path), "%s/%s/frame_%03d.png", getAssetsDir(), animation_folder, i);
        if (access(path, R_OK) != 0) {
            break;
        }
        count++;
    }
    return count;
}

static uint8_t *loadAndPreprocessFrame(int frameId, int bufferWidth, int bufferHeight)
{
    snprintf(path, sizeof(path), "%s/%s/frame_%03d.png", getAssetsDir(), animation_folder, frameId);

    int width, height, channel;
    uint8_t *chs = stbi_load(path, &width, &height, &channel, STBI_rgb_alpha);

    if (!chs) {
        tlog(LOG_ERR, "Failed to load image: %s", path);
        return NULL;
    }

    uint8_t *processedImage = static_cast<uint8_t *>(calloc(bufferWidth * bufferHeight, 4));
    if (!processedImage) {
        tlog(LOG_ERR, "Failed to allocate memory for frame %d", frameId);
        stbi_image_free(chs);
        return NULL;
    }

    int copyWidth = (width < bufferWidth) ? width : bufferWidth;
    int copyHeight = (height < bufferHeight) ? height : bufferHeight;
    int xOffset = (bufferWidth - copyWidth) / 2;
    int yOffset = (bufferHeight - copyHeight) / 2;

    for (int y = 0; y < copyHeight; y++) {
        memcpy(processedImage + ((yOffset + y) * bufferWidth + xOffset) * 4,
               chs + y * width * 4,
               copyWidth * 4);
    }

    stbi_image_free(chs);
    return processedImage;
}

static int animate()
{
    int ret;
    void *shared_buffer;
    if (serverState == NULL || lorieBuffer == NULL) {
        tlog(LOG_ERR, "Render resources are not initialized");
        return -1;
    }

    lorie_mutex_lock(&serverState->lock, &serverState->lockingPid);
    ret = LorieBuffer_lock(lorieBuffer, &shared_buffer);
    if (ret != 0) {
        tlog(LOG_ERR, "Failed to AHardwareBuffer_lock");
        lorie_mutex_unlock(&serverState->lock, &serverState->lockingPid);
        return ret;
    }

    const LorieBuffer_Desc *desc = LorieBuffer_description(lorieBuffer);
    int bufferWidth = desc->width;
    int bufferHeight = desc->height;
    int stride = desc->stride;

    int frameId = frame_number % frame_count;
    frame_number++;

    if (!frame_cache[frameId].loaded) {
        frame_cache[frameId].data = loadAndPreprocessFrame(frameId, bufferWidth, bufferHeight);
        if (!frame_cache[frameId].data) {
            LorieBuffer_unlock(lorieBuffer);
            lorie_mutex_unlock(&serverState->lock, &serverState->lockingPid);
            return -1;
        }
        frame_cache[frameId].width = bufferWidth;
        frame_cache[frameId].height = bufferHeight;
        frame_cache[frameId].loaded = true;
    }

    uint8_t *frameData = frame_cache[frameId].data;

    if (stride == bufferWidth) {
        memcpy(shared_buffer, frameData, bufferWidth * bufferHeight * 4);
    } else {
        int strideBytes = stride * 4;
        for (int y = 0; y < bufferHeight; y++) {
            memcpy(static_cast<uint8_t *>(shared_buffer) + y * strideBytes,
                   frameData + y * bufferWidth * 4,
                   bufferWidth * 4);
        }
    }

    serverState->waitForNextFrame = false;
    serverState->drawRequested = 1;
    if (rendererCond)
        pthread_cond_signal(rendererCond);
    lorie_mutex_unlock(&serverState->lock, &serverState->lockingPid);
    ret = LorieBuffer_unlock(lorieBuffer);
    return ret;
}

static void cleanupFrameCache()
{
    for (int i = 0; i < MAX_FRAMES; i++) {
        if (frame_cache[i].loaded && frame_cache[i].data) {
            free(frame_cache[i].data);
            frame_cache[i].data = NULL;
            frame_cache[i].loaded = false;
        }
    }
}

static void timer_handler(int signum)
{
    (void)signum;
    animate();
}

static void int_handler(int signum)
{
    (void)signum;
    keep_running = 0;
}

static void exit_handler()
{
    cleanupFrameCache();
    stopEventLoop();
    exit(0);
}

int main(int count, char **argv)
{
    int fps = 10;
    int width = 1080;
    int height = 720;

    for (int i = 1; i < count; i++) {
        char *arg = argv[i];
        char *value = NULL;
        char key[64] = {};

        if (strncmp(arg, "--", 2) != 0) {
            continue;
        }

        char *eq = strchr(arg, '=');
        if (eq) {
            int key_len = static_cast<int>(eq - arg);
            if (key_len < 64) {
                strncpy(key, arg, key_len);
                value = eq + 1;
            }
        } else if (i + 1 < count) {
            strncpy(key, arg, 63);
            value = argv[++i];
        }

        if (!value) {
            continue;
        }

        if (strcmp(key, "--fps") == 0) {
            fps = atoi(value);
            if (fps <= 0 || fps > 60) {
                tlog(LOG_ERR, "Invalid fps: %d (range: 1-60). Using default 10", fps);
                fps = 10;
            }
        } else if (strcmp(key, "--folder") == 0) {
            animation_folder = value;
        } else if (strcmp(key, "--width") == 0) {
            width = atoi(value);
            if (width <= 0) {
                tlog(LOG_ERR, "Invalid width: %d. Using default 1080", width);
                width = 800;
            }
        } else if (strcmp(key, "--height") == 0) {
            height = atoi(value);
            if (height <= 0) {
                tlog(LOG_ERR, "Invalid height: %d. Using default 720", height);
                height = 800;
            }
        }
    }

    frame_count = getAnimationFrameCount();
    if (frame_count <= 0) {
        tlog(LOG_ERR, "No animation frames found in %s/%s", getAssetsDir(), animation_folder);
        return 1;
    }

    int interval_usec = 1000000 / fps;

    setScreenConfig(width, height, fps);

    if (connectToRender() != 0) {
        return 1;
    }

    setExitCallback(exit_handler);

    struct sigaction sa_timer, sa_int;
    struct itimerval timer;

    sa_timer.sa_handler = timer_handler;
    sa_timer.sa_flags = SA_RESTART;
    sigemptyset(&sa_timer.sa_mask);
    if (sigaction(SIGALRM, &sa_timer, NULL) == -1) {
        tlog(LOG_ERR, "sigaction timer failed");
        return 1;
    }

    sa_int.sa_handler = int_handler;
    sa_int.sa_flags = 0;
    sigemptyset(&sa_int.sa_mask);
    if (sigaction(SIGINT, &sa_int, NULL) == -1) {
        tlog(LOG_ERR, "sigaction int failed");
        return 1;
    }

    timer.it_value.tv_sec = interval_usec / 1000000;
    timer.it_value.tv_usec = interval_usec % 1000000;
    timer.it_interval.tv_sec = interval_usec / 1000000;
    timer.it_interval.tv_usec = interval_usec % 1000000;

    if (setitimer(ITIMER_REAL, &timer, NULL) == -1) {
        tlog(LOG_ERR, "setitimer failed");
        return 1;
    }

    while (keep_running) {
        pause();
    }

    cleanupFrameCache();
    stopEventLoop();

    return 0;
}
