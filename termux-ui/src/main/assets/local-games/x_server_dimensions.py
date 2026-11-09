#!/usr/bin/env python3
"""Read the current X root geometry without loading Bionic/GLIBC libraries."""
import socket
import struct
import sys


def read_exact(connection, count):
    result = bytearray()
    while len(result) < count:
        part = connection.recv(count - len(result))
        if not part:
            raise ValueError('truncated_reply')
        result.extend(part)
    return bytes(result)


def root_dimensions(path):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(1.5)
        connection.connect(path)
        # Little-endian X11 setup, protocol 11.0, no authentication credentials.
        connection.sendall(struct.pack('<BBHHHHH', ord('l'), 0, 11, 0, 0, 0, 0))
        header = read_exact(connection, 8)
        if header[0] != 1:
            raise ValueError('setup_rejected')
        setup = read_exact(connection, struct.unpack_from('<H', header, 6)[0] * 4)
        if len(setup) < 32 or setup[20] == 0:
            raise ValueError('screen_missing')
        vendor_length = struct.unpack_from('<H', setup, 16)[0]
        screen_offset = 32 + ((vendor_length + 3) & ~3) + setup[21] * 8
        if screen_offset + 40 > len(setup):
            raise ValueError('screen_invalid')
        root = struct.unpack_from('<I', setup, screen_offset)[0]
        # GetGeometry obtains the current dimensions, rather than assuming defaults.
        connection.sendall(struct.pack('<BBHI', 14, 0, 2, root))
        reply = read_exact(connection, 32)
        if reply[0] != 1 or struct.unpack_from('<H', reply, 2)[0] != 1:
            raise ValueError('geometry_rejected')
        width, height = struct.unpack_from('<HH', reply, 16)
        if width == 0 or height == 0:
            raise ValueError('geometry_invalid')
        return width, height


def main():
    try:
        width, height = root_dimensions(sys.argv[1])
        actual = f'{width}x{height}'
        print(f'X11 dimensions: root={actual} target={sys.argv[2]} match={str(actual == sys.argv[2]).lower()}')
    except (OSError, ValueError, struct.error) as error:
        print(f'X11 dimensions: diagnostic unavailable ({type(error).__name__}: {error})')


if __name__ == '__main__':
    main()
