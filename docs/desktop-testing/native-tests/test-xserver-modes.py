#!/usr/bin/env python3
"""Exercise the real Xlorie mode parser and provider environment without EGL."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--cc', default='cc')
args = parser.parse_args()
source = (args.source / 'hw/lorie/lorie-egl.c').read_text()
header = (args.source / 'hw/lorie/lorie-egl.h').read_text()
enum = re.search(r'enum lorie_gl_mode \{.*?\n\};', header, re.S).group(0)
mode = source[source.index('static enum lorie_gl_mode\nmode_from_string('):source.index('static Bool\nhas_egl_extension(')]
config = source[source.index('static Bool\nconfigure_mode_environment('):source.index('static EGLDisplay\nget_platform_display(')]
harness = '''#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdarg.h>
typedef int Bool;
#define TRUE 1
#define FALSE 0
#define lorieLog(...) fprintf(stderr, __VA_ARGS__)
static void FatalError(const char *format, ...) {
    va_list args; va_start(args, format); vfprintf(stderr, format, args); va_end(args); exit(47);
}
'''
harness += enum + '\n' + mode + '\n' + config
harness += '''
int main(void) {
    enum lorie_gl_mode mode = lorieEglResolveMode();
    if (!configure_mode_environment(mode)) return 48;
    const char *names[] = {"TERMUX_ANDROID_ZINK", "MESA_LOADER_DRIVER_OVERRIDE", "GALLIUM_DRIVER", "LIBGL_ALWAYS_SOFTWARE", "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "TERMUX_VULKAN_BROKER_SOCKET"};
    for (unsigned i=0; i<sizeof(names)/sizeof(names[0]); ++i)
        printf("%s=%s\\n", names[i], getenv(names[i]) ? getenv(names[i]) : "<unset>");
    return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-mode-test-') as temp:
    base = Path(temp)
    test, binary, icd = base/'test.c', base/'test', base/'test-icd.json'
    test.write_text(harness)
    icd.write_text('{}')
    subprocess.run([args.cc, '-std=c11', '-Wall', '-Wextra', '-Werror', str(test), '-o', str(binary)], check=True)
    env = dict(os.environ)
    for name in ('XLORIE_GL_MODE', 'KWIN_ANDROID_GL_MODE', 'VK_DRIVER_FILES', 'VK_ICD_FILENAMES'):
        env.pop(name, None)
    def run(updates, code):
        result = subprocess.run([str(binary)], env={**env, **updates}, text=True, capture_output=True)
        assert result.returncode == code, (updates, result.returncode, result.stderr)
        return dict(line.split('=', 1) for line in result.stdout.splitlines())
    run({}, 47)
    for invalid in ('auto', 'software', 'turnip', 'bad-mode'):
        run({'XLORIE_GL_MODE': invalid}, 47)
    run({'XLORIE_GL_MODE': 'system', 'KWIN_ANDROID_GL_MODE': 'llvmpipe'}, 47)
    result = run({'XLORIE_GL_MODE': 'system', 'TERMUX_ANDROID_ZINK': '1', 'GALLIUM_DRIVER': 'zink', 'VK_ICD_FILENAMES': '/stale/icd.json'}, 0)
    assert result['TERMUX_ANDROID_ZINK'] == '0'
    assert result['GALLIUM_DRIVER'] == '<unset>' and result['VK_ICD_FILENAMES'] == '<unset>'
    result = run({'XLORIE_GL_MODE': 'llvmpipe', 'VK_DRIVER_FILES': '/stale/icd.json', 'GALLIUM_DRIVER': 'zink'}, 0)
    assert result['TERMUX_ANDROID_ZINK'] == '1' and result['GALLIUM_DRIVER'] == 'llvmpipe'
    assert result['LIBGL_ALWAYS_SOFTWARE'] == '1' and result['VK_DRIVER_FILES'] == '<unset>'
    result = run({'XLORIE_GL_MODE': 'zink', 'VK_DRIVER_FILES': str(icd), 'VK_ICD_FILENAMES': str(icd)}, 0)
    assert result['TERMUX_ANDROID_ZINK'] == '1' and result['GALLIUM_DRIVER'] == 'zink'
    assert result['VK_DRIVER_FILES'] == str(icd) and result['LIBGL_ALWAYS_SOFTWARE'] == '<unset>'
    run({'XLORIE_GL_MODE': 'zink', 'VK_DRIVER_FILES': str(icd), 'VK_ICD_FILENAMES': '/other/icd.json'}, 48)
    run({'XLORIE_GL_MODE': 'zink', 'VK_DRIVER_FILES': '/missing/icd.json'}, 48)
print('PASS: explicit modes, missing/unknown/conflicting modes, provider/driver selection, ICD conflict/missing errors')
