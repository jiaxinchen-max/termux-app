#!/usr/bin/env python3
"""Test real Present deadline and frame publishing code with deterministic mocks."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--cc', default='cc')
args = parser.parse_args()
source = (args.source / 'hw/lorie/InitOutput.c').read_text()
helpers = source[source.index('static int\nloriePresentDelayMillis('):source.index('static Bool\nloriePresentCheckFlip(')]
adapter = (args.source / 'hw/lorie/render-adapter.c').read_text()
publish = adapter[adapter.index('bool\nlorieRenderSignalFrame('):adapter.index('bool\nlorieRenderUseBuffer(')]
harness = r'''
#include <stdint.h>
#include <stdbool.h>
#include <limits.h>
#include <assert.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include "list.h"
typedef int Bool;
#define TRUE 1
#define FALSE 0
#define LORIE_DEFAULT_FRAMERATE 60
typedef struct { struct xorg_list link; uint64_t msc; } lorieVblankRec;
static struct { struct xorg_list vblankQueue; uint64_t vblankInterval; } lorieScreen;
static uint64_t now;
static uint64_t GetTimeInMicros(void) { return now; }
static void AdjustWaitForDelay(void *wait, int delay) {
    int *timeout=wait;
    if (*timeout<0 || delay<*timeout) *timeout=delay;
}
struct lorie_shared_server_state {
    pthread_mutex_t lock;
    int lockingPid;
    uint64_t rootWindowTextureID;
    int waitForNextFrame, drawRequested;
};
static struct lorie_shared_server_state state={.lock=PTHREAD_MUTEX_INITIALIZER};
typedef struct { uint64_t id; } LorieBuffer_Desc;
typedef struct { LorieBuffer_Desc desc; } LorieBuffer;
static LorieBuffer buffer={{42}};
static LorieBuffer *get_lorieBuffer(void) { return &buffer; }
static struct lorie_shared_server_state *get_serverState(void) { return &state; }
static const LorieBuffer_Desc *LorieBuffer_description(LorieBuffer *value) { return &value->desc; }
static uint64_t activeRootBufferId;
static pthread_cond_t rendererCondition=PTHREAD_COND_INITIALIZER;
static pthread_cond_t *rendererCond=&rendererCondition;
static pthread_mutex_t readyMutex=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t readyCondition=PTHREAD_COND_INITIALIZER;
static int lockAttempted;
static void lorie_mutex_lock(pthread_mutex_t *lock, int *pid) {
    (void)pid;
    pthread_mutex_lock(&readyMutex);
    lockAttempted=1;
    pthread_cond_signal(&readyCondition);
    pthread_mutex_unlock(&readyMutex);
    pthread_mutex_lock(lock);
}
static void lorie_mutex_unlock(pthread_mutex_t *lock, int *pid) {
    (void)pid;
    pthread_mutex_unlock(lock);
}
'''
harness += helpers + publish
harness += r'''
static void *publishThread(void *unused) {
    (void)unused;
    assert(lorieRenderSignalFrame());
    return NULL;
}
int main(void) {
    int timeout;
    lorieVblankRec early={.msc=101}, late={.msc=120};
    assert(loriePresentDelayMillis(100,1000000,10000)==0);
    assert(loriePresentDelayMillis(99,1000000,10000)==0);
    assert(loriePresentDelayMillis(101,1000000,10000)==10);
    assert(loriePresentDelayMillis(101,1009999,10000)==1);
    assert(loriePresentDelayMillis(101,1000999,10000)==10);
    assert(loriePresentDelayMillis(UINT64_MAX,1000000,10000)==INT_MAX);
    assert(loriePresentDelayMillis(1,0,0)==17);
    xorg_list_init(&lorieScreen.vblankQueue);
    lorieScreen.vblankInterval=10000;
    now=1000000;
    timeout=-1;
    loriePresentArmTimeout(&timeout);
    assert(timeout==-1);  /* No periodic polling without queued events. */
    xorg_list_add(&early.link,&lorieScreen.vblankQueue);
    xorg_list_add(&late.link,&lorieScreen.vblankQueue);
    loriePresentArmTimeout(&timeout);
    assert(timeout==10);  /* Earliest deadline, independent of list order. */
    timeout=5;
    loriePresentArmTimeout(&timeout);
    assert(timeout==5);   /* Preserve a different subsystem's earlier wake. */
    early.msc=100; timeout=-1;
    loriePresentArmTimeout(&timeout);
    assert(timeout==0);

    /* Producer attempts publication while renderer owns the shared lock.
     * Renderer then finishes and sets its old-frame flags. The pending newer
     * producer frame must survive that completion rather than being erased. */
    pthread_t thread;
    alarm(5);
    pthread_mutex_lock(&state.lock);
    assert(!pthread_create(&thread,NULL,publishThread,NULL));
    pthread_mutex_lock(&readyMutex);
    while (!lockAttempted) pthread_cond_wait(&readyCondition,&readyMutex);
    pthread_mutex_unlock(&readyMutex);
    state.drawRequested=0;
    state.waitForNextFrame=1;
    pthread_mutex_unlock(&state.lock);
    assert(!pthread_join(thread,NULL));
    assert(state.drawRequested==1 && state.waitForNextFrame==0);
    assert(state.rootWindowTextureID==42);
    alarm(0);
    puts("PASS: Present deadlines/rounding/queue order/no idle polling; new frame survives renderer completion");
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-present-wakeup-') as temp:
    test, binary = Path(temp)/'test.c', Path(temp)/'test'
    test.write_text(harness)
    subprocess.run([args.cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', '-pthread', '-I'+str(args.source/'include'), str(test), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
