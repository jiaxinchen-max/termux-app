#!/usr/bin/env python3
"""Compile and exercise the real readback helper with a deterministic GL mock."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path)
parser.add_argument('--cc', default='cc')
args = parser.parse_args()
source = (args.source / 'glamor/glamor_transfer.c').read_text()
start = source.index('static void\nglamor_read_pixels(')
end = source.index('\n/*\n * Read stuff', start)
helper = source[start:end]
harness = r'''
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>
#include <stdio.h>
#define GL_PACK_ROW_LENGTH 0x0d02
typedef int GLint;
#define FatalError(...) abort()
void glGetIntegerv(unsigned name, int *value) { (void)name; *value=0; }
void glPixelStorei(unsigned name, int value) { (void)name; (void)value; }
#define GL_RGBA 0x1908
#define GL_BGRA 0x80e1
#define GL_UNSIGNED_BYTE 0x1401
struct glamor_format { unsigned format, type; int rgba_readback, depth; };
static int pack_stride;
static unsigned requested_format;
static void glReadPixels(int x, int y, int width, int height,
                         unsigned format, unsigned type, uint8_t *bits)
{
    (void)x; (void)y;
    assert(type == GL_UNSIGNED_BYTE);
    requested_format = format;
    for (int row=0; row<height; ++row)
        for (int col=0; col<width; ++col) {
            uint8_t *p=bits+row*pack_stride+col*4;
            p[0]=0x11; p[1]=0x22; p[2]=0x33; p[3]=0x44;
        }
}
'''
harness += helper
harness += r'''
int main(void)
{
    uint8_t bits[96];
    struct glamor_format f={GL_BGRA,GL_UNSIGNED_BYTE,1,24};
    /* Two disjoint boxes share rows with untouched neighbours and padding. */
    memset(bits,0xcd,sizeof(bits));
    pack_stride=32;
    glamor_read_pixels(&f,7,3,2,2,bits+4,32);
    glamor_read_pixels(&f,10,3,1,2,bits+20,32);
    assert(requested_format==GL_RGBA);
    for (int i=0; i<96; ++i) {
        int row=i/32, col=i%32;
        if (row<2 && ((col>=4 && col<12) || (col>=20 && col<24))) {
            const uint8_t expected[]={0x33,0x22,0x11,0x44};
            assert(bits[i]==expected[col%4]);
        } else assert(bits[i]==0xcd);
    }
    /* The path without PACK_ROW_LENGTH calls the helper one row at a time. */
    memset(bits,0xcd,sizeof(bits));
    pack_stride=8;
    glamor_read_pixels(&f,0,0,2,1,bits+8,32);
    glamor_read_pixels(&f,0,1,2,1,bits+40,32);
    assert(bits[8]==0x33 && bits[40]==0x33 && bits[16]==0xcd && bits[48]==0xcd);
    /* Formats already accepted by the driver are passed through unchanged. */
    f.rgba_readback=0;
    glamor_read_pixels(&f,0,0,1,1,bits,4);
    assert(requested_format==GL_BGRA && bits[0]==0x11 && bits[2]==0x33);
    puts("PASS: RGBA/BGRA conversion, strided disjoint boxes, row path, native pass-through");
}
'''
with tempfile.TemporaryDirectory(prefix='xserver-readback-test-') as temp:
    test = Path(temp) / 'test.c'
    binary = Path(temp) / 'test'
    test.write_text(harness)
    subprocess.run([args.cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined', str(test), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
