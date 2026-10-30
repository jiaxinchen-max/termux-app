#!/usr/bin/env python3
"""Test real InitOutput GLX mode parsing and vendor registration gating."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--cc', default='cc')
args = parser.parse_args()
source = (args.source / 'hw/lorie/InitOutput.c').read_text()
start = source.index('void\nInitOutput(')
body = source[start:]
parse = body[body.index('    const char *glxEnv'):body.index('    loriePixmapDepths[lorieScreen.depth]')]
gate = body[body.index('    if (!noGlxExtension)'):body.index('    lorieInitClipboard();')]
harness = '''#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#define TRUE 1
#define FALSE 0
#define LORIE_HAVE_GLAMOR 1
#define lorieLog(...) fprintf(stderr, __VA_ARGS__)
static int noGlxExtension, pushes, vendors, lorieGlxProvider;
static void GlxPushProvider(void *provider) { (void)provider; ++pushes; }
static void xorgGlxCreateVendor(void) { ++vendors; }
static void FatalError(const char *format, ...) {
    va_list args; va_start(args, format); vfprintf(stderr, format, args); va_end(args); exit(47);
}
int main(int argc, char **argv) {
    noGlxExtension = argc>1 ? atoi(argv[1]) : 0;
'''
harness += parse + gate
harness += '''
    printf("disabled=%d pushes=%d vendors=%d\\n", noGlxExtension, pushes, vendors);
    return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-glx-mode-') as temp:
    test, binary = Path(temp)/'test.c', Path(temp)/'test'
    test.write_text(harness)
    subprocess.run([args.cc, '-std=c11', '-Wall', '-Wextra', '-Werror', str(test), '-o', str(binary)], check=True)
    env = dict(os.environ)
    env.pop('XLORIE_GLX', None)
    cases = [(None, 0, 0, 'disabled=0 pushes=1 vendors=1'),
             (None, 1, 0, 'disabled=1 pushes=0 vendors=0'),
             ('0', 0, 0, 'disabled=1 pushes=0 vendors=0'),
             ('1', 1, 0, 'disabled=0 pushes=1 vendors=1'),
             ('', 0, 47, ''), ('auto', 0, 47, ''), ('2', 0, 47, '')]
    for value, disabled, code, output in cases:
        trial = dict(env)
        if value is not None:
            trial['XLORIE_GLX'] = value
        result = subprocess.run([str(binary), str(disabled)], env=trial, text=True, capture_output=True)
        assert result.returncode == code, (value, result.returncode, result.stderr)
        assert result.stdout.strip() == output, (value, result.stdout)
print('PASS: GLX=0 disables registration; GLX=1 enables; unset preserves server option; invalid values reject')
