#!/usr/bin/env python3
"""Compile the production viewport event branch against a small Clutter boundary stub.

Run on the build machine, not an Android device. This does not build Mutter or
substitute for checking rotation through the App UI.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--cc', required=True)
    args = parser.parse_args()
    source = args.source.read_text()
    start = source.index('else if(e.type==EVENT_SCREEN_SIZE) {')
    opening = source.index('{', start)
    level = 1
    end = opening + 1
    while level:
        level += (source[end] == '{') - (source[end] == '}')
        end += 1
    body = source[opening + 1:end - 1]
    harness = r'''
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
typedef struct { int width, height, refresh, input_width, input_height, system; size_t discard; } MetaBackendAndroid;
typedef struct { struct { int width, height; size_t name_size; } screenSize; } lorieEvent;
static MetaBackendAndroid *active;
static int stage, redraws;
#define META_BACKEND(value) (value)
#define CLUTTER_ACTOR(value) (value)
#define g_message(...) ((void)0)
static void require(int valid, const char *message) {
  if (!valid) { fprintf(stderr,"FAIL: %s\n",message); exit(1); }
}
static __attribute__((unused)) void *meta_backend_get_stage(MetaBackendAndroid *backend) {
  require(backend==active,"redraw targeted another backend"); return &stage;
}
static __attribute__((unused)) void clutter_actor_queue_redraw(void *actor) {
  require(actor==&stage,"redraw targeted another stage"); redraws++;
}
static void viewport(MetaBackendAndroid *a, lorieEvent e) {
''' + body + r'''
}
int main(void) {
  MetaBackendAndroid a={.width=1280,.height=800,.refresh=60,.input_width=1280,.input_height=800,.system=1};
  active=&a;
  viewport(&a,(lorieEvent){.screenSize={1080,2400,7}});
  require(redraws==1,"valid viewport did not request a frame without input");
  require(a.input_width==1080 && a.input_height==2400 && a.discard==7,"input viewport/payload not preserved");
  viewport(&a,(lorieEvent){.screenSize={1080,2400,0}});
  require(redraws==2,"same-size Surface recreation did not request a frame");
  viewport(&a,(lorieEvent){.screenSize={2400,1080,0}});
  require(redraws==3 && a.input_width==2400 && a.input_height==1080,"rotation did not update viewport and request a frame");
  viewport(&a,(lorieEvent){.screenSize={0,0,0}});
  viewport(&a,(lorieEvent){.screenSize={1080,0,0}});
  require(redraws==3 && a.input_width==2400 && a.input_height==1080,"invalid viewport changed rendering/input state");
  require(a.width==1280 && a.height==800 && a.refresh==60 && a.system==1,"viewport changed negotiated output or renderer");
  puts("PASS: viewport/rotation/same-size Surface events redraw without changing output mode");
}
'''
    with tempfile.TemporaryDirectory(prefix='mutter-viewport-') as temporary:
        folder = Path(temporary)
        (folder / 'test.c').write_text(harness)
        subprocess.run([args.cc, '-std=c11', '-Wall', '-Wextra', '-Werror',
                        str(folder / 'test.c'), '-o', str(folder / 'test')], check=True)
        subprocess.run([str(folder / 'test')], check=True)


if __name__ == '__main__':
    main()
