#!/usr/bin/env python3
"""X11 protocol fixtures; no Android device, Wine or X libraries required."""
import importlib.util
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest

source = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/x_server_dimensions.py'
spec = importlib.util.spec_from_file_location('dimensions', source)
dimensions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dimensions)


class RootDimensionsTest(unittest.TestCase):
    def query(self, response):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'X0')
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
                server.bind(path)
                server.listen(1)
                errors = []

                def serve():
                    try:
                        with server.accept()[0] as connection:
                            connection.settimeout(2)
                            self.assertEqual(dimensions.read_exact(connection, 12),
                                             struct.pack('<BBHHHHH', ord('l'), 0, 11, 0, 0, 0, 0))
                            response(connection)
                    except Exception as error:
                        errors.append(error)

                thread = threading.Thread(target=serve)
                thread.start()
                try:
                    return dimensions.root_dimensions(path)
                finally:
                    thread.join(3)
                    self.assertFalse(thread.is_alive())
                    if errors:
                        raise errors[0]

    def setup_reply(self, connection, screens=1):
        # Odd vendor length plus two pixmap formats exercise wire padding/offsets.
        setup = bytearray(32 + 4 + 16 + 40)
        struct.pack_into('<H', setup, 16, 3)
        setup[20], setup[21] = screens, 2
        setup[32:35] = b'abc'
        struct.pack_into('<I', setup, 52, 42)
        struct.pack_into('<HH', setup, 72, 800, 600)
        header = struct.pack('<BBHHH', 1, 0, 11, 0, len(setup) // 4)
        for fragment in (header[:3], header[3:], setup[:17], setup[17:]):
            connection.sendall(fragment)

    def test_reads_current_root_instead_of_setup_default(self):
        def response(connection):
            self.setup_reply(connection)
            self.assertEqual(dimensions.read_exact(connection, 8), struct.pack('<BBHI', 14, 0, 2, 42))
            reply = bytearray(32)
            struct.pack_into('<BBHI', reply, 0, 1, 24, 1, 0)
            struct.pack_into('<HH', reply, 16, 1280, 720)
            connection.sendall(reply[:7])
            connection.sendall(reply[7:])
        self.assertEqual((1280, 720), self.query(response))

    def test_authentication_rejection_is_explicit(self):
        with self.assertRaisesRegex(ValueError, 'setup_rejected'):
            self.query(lambda c: c.sendall(bytes(8)))

    def test_truncated_reply_is_explicit(self):
        with self.assertRaisesRegex(ValueError, 'truncated_reply'):
            self.query(lambda c: c.sendall(b'\x01\x00'))

    def test_screenless_setup_is_explicit(self):
        with self.assertRaisesRegex(ValueError, 'screen_missing'):
            self.query(lambda c: self.setup_reply(c, screens=0))


if __name__ == '__main__':
    unittest.main()
