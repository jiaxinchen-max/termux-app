#!/usr/bin/env python3
"""Wrap media-smoke.c's encoded 64x64 WMV2 frame in a one-frame AVI."""
import argparse
from pathlib import Path
import struct


def chunk(tag, data):
    return tag + struct.pack('<I', len(data)) + data + (b'\0' if len(data) % 2 else b'')


def listing(tag, data):
    return chunk(b'LIST', tag + data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--packet', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.packet.read_bytes()
    extra, size = struct.unpack('<II', raw[:8])
    if not (extra <= 4096 and 0 < size <= 1048576 and len(raw) == 8 + extra + size):
        raise ValueError('unexpected_wmv2_packet')
    avih = struct.pack('<14I', 40000, 0, 0, 16, 1, 0, 1, size, 64, 64, 0, 0, 0, 0)
    strh = struct.pack('<4s4sIHH8I4h', b'vids', b'WMV2', 0, 0, 0, 0,
                       1, 25, 0, 1, size, 0xffffffff, 0, 0, 0, 64, 64)
    strf = struct.pack('<IiiHH4sIiiII', 40, 64, 64, 1, 24, b'WMV2',
                       64 * 64 * 3, 0, 0, 0, 0) + raw[8:8 + extra]
    headers = listing(b'hdrl', chunk(b'avih', avih) + listing(b'strl',
                      chunk(b'strh', strh) + chunk(b'strf', strf)))
    data = listing(b'movi', chunk(b'00dc', raw[8 + extra:]))
    index = chunk(b'idx1', struct.pack('<4sIII', b'00dc', 16, 4, size))
    with args.output.open('xb') as stream:
        stream.write(chunk(b'RIFF', b'AVI ' + headers + data + index))


if __name__ == '__main__':
    main()
