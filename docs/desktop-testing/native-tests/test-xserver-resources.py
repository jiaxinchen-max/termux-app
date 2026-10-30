#!/usr/bin/env python3
"""Inject setup failures into actual Xlorie resource and socket entry points."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--cc', default='cc')
args = parser.parse_args()
source = (args.source / 'hw/lorie/InitOutput.c').read_text()
resources = source[source.index('static Bool\nlorieCreateScreenResources(ScreenPtr pScreen)'):source.index('void\nlorieConfigureNotify(')]
adapter = (args.source / 'hw/lorie/render-adapter.c').read_text()
connect = adapter[adapter.index('bool\nlorieRenderConnect('):adapter.index('void\nlorieRenderDisconnect(')]
harness = r'''
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <errno.h>
#define TRUE 1
#define FALSE 0
#define LORIE_HAVE_GLAMOR 1
#define ErrorF(...) fprintf(stderr, __VA_ARGS__)
#define lorieLog(...) fprintf(stderr, __VA_ARGS__)
typedef int Bool;
typedef void *ScreenPtr;
static Bool callbackOk=TRUE, damageOk=TRUE, gpuOk=TRUE, glamorActive=TRUE;
static int gpuCalls, lorieGlMode=1;
static int lorieRootImage, lorieRootTexture;
static Bool createCallback(ScreenPtr screen) { (void)screen; return callbackOk; }
static struct {
    Bool (*createScreenResources)(ScreenPtr);
    void *rootBuffer;
} lorieScreen={.createScreenResources=createCallback};
static Bool lorieCreateRootDamage(ScreenPtr screen) { (void)screen; return damageOk; }
static Bool lorieGlamorBackScreenPixmap(ScreenPtr screen, void *root, int *image, int *texture) {
    (void)screen; (void)root; (void)image; (void)texture;
    ++gpuCalls; return gpuOk;
}
#define TERMUX_RENDER_SOCKET_PATH "/data/data/com.termux/files/home/tmp/termux-render"
#define XLORIE_RENDER_BUFFER_TYPE 1
#define LORIE_KEYCODE_XKB 1
static bool connected;
static const char *requestedSocketPath;
static uint64_t activeRootBufferId;
static int renderBufferFormat, connectionAttempts;
typedef struct { int width, height, stride, format, type; uint64_t id; } LorieBuffer_Desc;
static LorieBuffer_Desc description={10,20,10,5,1,42};
static struct { uint64_t rootWindowTextureID; } state;
static void lorieRenderChooseReceiveMode(void) { }
static void setScreenConfig(int w,int h,int f,int format,int type) {
    (void)w; (void)h; (void)f; (void)format; (void)type;
}
static void setKeycodeFormat(int format) { (void)format; }
static int connectToRender(void) { ++connectionAttempts; return 0; }
static void *get_lorieBuffer(void) { return &description; }
static typeof(state) *get_serverState(void) { return &state; }
static int get_conn_fd(void) { return 1; }
static void stopEventLoop(void) { }
static const LorieBuffer_Desc *LorieBuffer_description(void *buffer) { return buffer; }
'''
harness += resources + connect
harness += r'''
int main(void) {
    assert(lorieCreateScreenResources(NULL));
    callbackOk=FALSE; gpuCalls=0;
    assert(!lorieCreateScreenResources(NULL) && gpuCalls==0);
    callbackOk=TRUE; damageOk=FALSE;
    assert(!lorieCreateScreenResources(NULL) && gpuCalls==0);
    damageOk=TRUE; gpuOk=FALSE;
    assert(!lorieCreateScreenResources(NULL) && gpuCalls==1);
    gpuOk=TRUE;
    assert(lorieCreateScreenResources(NULL));
    requestedSocketPath="/wrong/socket";
    assert(!lorieRenderConnect(10,20,60) && connectionAttempts==0);
    requestedSocketPath=TERMUX_RENDER_SOCKET_PATH;
    assert(lorieRenderConnect(10,20,60) && connectionAttempts==1);
    assert(state.rootWindowTextureID==42 && activeRootBufferId==42);
    puts("PASS: initial callback/damage/GPU failures reject; wrong socket never connects; success path retained");
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-resource-test-') as temp:
    test, binary=Path(temp)/'test.c',Path(temp)/'test'
    test.write_text(harness)
    subprocess.run([args.cc,'-std=gnu11','-Wall','-Wextra','-Werror',str(test),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
