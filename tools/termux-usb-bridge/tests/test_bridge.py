# SPDX-License-Identifier: MIT
"""Real Unix sockets, subprocesses and SCM_RIGHTS; mock files are not USB devices."""
import concurrent.futures
import ctypes
import errno
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import socket
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent.parent
LIBRARY, PROBE = sys.argv[1:3]
del sys.argv[1:3]
spec = importlib.util.spec_from_file_location("broker", ROOT / "broker.py")
broker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(broker)
DEVICE = "/dev/bus/usb/001/002"
DESCRIPTOR = bytes.fromhex("12 01 00 02 00 00 00 40 34 12 78 56 00 01 00 00 00 01")


class DeviceList(ctypes.Structure):
    _fields_ = [("count", ctypes.c_size_t), ("paths", ctypes.POINTER(ctypes.c_char_p))]


class BridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lib = ctypes.CDLL(LIBRARY, use_errno=True)
        cls.lib.tusb_list.argtypes = [ctypes.c_char_p, ctypes.POINTER(DeviceList)]
        cls.lib.tusb_list.restype = ctypes.c_int
        cls.lib.tusb_free_list.argtypes = [ctypes.POINTER(DeviceList)]
        cls.lib.tusb_open.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        cls.lib.tusb_open.restype = ctypes.c_int

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tusb-", dir=os.environ.get("TMPDIR", "/tmp"))
        self.directory = Path(self.temp.name)
        self.address = str(self.directory / "broker.sock")
        self.manifest = self.directory / "devices.json"
        self.manifest.write_text(json.dumps({DEVICE: "descriptor.bin"}))
        (self.directory / "descriptor.bin").write_bytes(DESCRIPTOR)
        self.environment = dict(os.environ, TERMUX_USB_BRIDGE_SOCKET=self.address)
        self.processes = []
        self.start_server("--mock-json", str(self.manifest))

    def start_server(self, *backend):
        process = subprocess.Popen([sys.executable, str(ROOT / "broker.py"), "--socket", self.address,
                                    "serve", *backend], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if process.poll() is not None:
                self.fail(process.communicate()[1].decode())
            if Path(self.address).exists():
                return process
            time.sleep(0.02)
        self.fail("broker did not start")

    def stop_servers(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=5)
        self.processes.clear()

    def tearDown(self):
        self.stop_servers()
        self.temp.cleanup()

    def test_c_list_and_open_fd_survives_socket_close(self):
        devices = DeviceList()
        self.assertEqual(self.lib.tusb_list(self.address.encode(), ctypes.byref(devices)), 0)
        try:
            self.assertEqual(devices.count, 1)
            self.assertEqual(devices.paths[0].decode(), DEVICE)
        finally:
            self.lib.tusb_free_list(ctypes.byref(devices))
        fd = self.lib.tusb_open(self.address.encode(), DEVICE.encode())
        self.assertGreaterEqual(fd, 0)
        try:
            self.assertTrue(fcntl.fcntl(fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC)
            self.assertEqual(os.read(fd, 18), DESCRIPTOR)
        finally:
            os.close(fd)

    def test_device_removed_and_reinserted(self):
        self.manifest.write_text("{}")
        body, _ = broker.request(self.address, 1)
        self.assertEqual(body, b"")
        self.assertEqual(self.lib.tusb_open(self.address.encode(), DEVICE.encode()), -1)
        self.assertEqual(ctypes.get_errno(), errno.ENODEV)
        self.manifest.write_text(json.dumps({DEVICE: "descriptor.bin"}))
        body, _ = broker.request(self.address, 1)
        self.assertEqual(body.decode(), DEVICE + "\n")

    def test_no_path_traversal_or_arbitrary_file_access(self):
        for path in ("/etc/passwd", "/dev/bus/usb/001/../../etc/passwd", "/dev/bus/usb/001/999"):
            self.assertEqual(self.lib.tusb_open(self.address.encode(), path.encode()), -1)
            self.assertEqual(ctypes.get_errno(), errno.EINVAL)

    def test_unknown_device(self):
        self.assertEqual(self.lib.tusb_open(self.address.encode(), b"/dev/bus/usb/001/003"), -1)
        self.assertEqual(ctypes.get_errno(), errno.ENODEV)

    def test_empty_android_api_output_is_not_an_empty_device_list(self):
        self.stop_servers()
        marker = self.directory / "allowed"
        fake = self.directory / "background-limited-termux-usb"
        fake.write_text(f'''#!{sys.executable}
from pathlib import Path
import sys
assert sys.argv[1:] == ['-l']
sys.stdout.write('[]' if Path({str(marker)!r}).exists() else '')
''')
        fake.chmod(0o700)
        self.start_server("--termux-usb", str(fake))
        with self.assertRaises(OSError) as error:
            broker.request(self.address, 1)
        self.assertEqual(error.exception.errno, errno.EACCES)
        self.assertIn("background", str(error.exception))
        marker.write_text("allowed")
        self.assertEqual(broker.request(self.address, 1)[0], b"")

    def test_fragmented_request(self):
        with socket.socket(socket.AF_UNIX) as sock:
            sock.settimeout(2)
            sock.connect(self.address)
            data = broker.HEADER.pack(b"TUSB", 1, 1, 0, 0)
            for byte in data:
                sock.sendall(bytes([byte]))
            op, body, fd = broker.receive_packet(sock)
            self.assertEqual((op, body, fd), (1, (DEVICE + "\n").encode(), None))

    def test_rejects_bad_protocol_and_recovers(self):
        for magic, version, length in ((b"FAIL", 1, 0), (b"TUSB", 2, 0), (b"TUSB", 1, broker.LIMIT + 1)):
            with socket.socket(socket.AF_UNIX) as sock:
                sock.settimeout(2)
                sock.connect(self.address)
                sock.sendall(broker.HEADER.pack(magic, version, 1, 0, length))
                with self.assertRaises(OSError) as error:
                    broker.receive_packet(sock)
                self.assertEqual(error.exception.errno, errno.EPROTO)
        self.assertTrue(broker.request(self.address, 1)[0])

    def test_probe_and_fd_inheritance_through_exec(self):
        result = subprocess.run([PROBE], env=self.environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, DEVICE + "\n")
        code = "import os; print(os.read(int(os.environ['TERMUX_USB_FD']),18).hex())"
        result = subprocess.run([sys.executable, str(ROOT / "broker.py"), "exec", DEVICE, "--",
                                 sys.executable, "-c", code], env=self.environment,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), DESCRIPTOR.hex())

    def test_concurrent_opens_have_independent_offsets(self):
        def read_device(_):
            _, fd = broker.request(self.address, 2, DEVICE.encode())
            try:
                return os.read(fd, 18)
            finally:
                os.close(fd)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(list(pool.map(read_device, range(24))), [DESCRIPTOR] * 24)

    def test_existing_socket_is_not_unlinked(self):
        result = subprocess.run([sys.executable, str(ROOT / "broker.py"), "--socket", self.address,
                                 "serve", "--mock-json", str(self.manifest)],
                                capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(broker.request(self.address, 1)[0])

    def test_termux_callback_with_quoted_paths_and_rewind(self):
        self.stop_servers()
        fake = self.directory / "fake termux usb"
        # Exercise the real TermuxBackend callback/FD path, not MockBackend.
        fake.write_text(f'''#!{sys.executable}
import json, os, shlex, subprocess, sys
if sys.argv[1:] == ['-l']:
    print(json.dumps([{DEVICE!r}]))
elif sys.argv[1] == '-e':
    assert '-r' not in sys.argv
    fd = os.open({str(self.directory / 'descriptor.bin')!r}, os.O_RDONLY)
    os.read(fd, 18)
    try:
        sys.exit(subprocess.call(shlex.split(sys.argv[2]) + [str(fd)], pass_fds=[fd]))
    finally:
        os.close(fd)
else:
    sys.exit(2)
''')
        fake.chmod(0o700)
        self.start_server("--termux-usb", str(fake))
        for _ in range(3):
            _, fd = broker.request(self.address, 2, DEVICE.encode())
            try:
                self.assertEqual(os.read(fd, 18), DESCRIPTOR)
            finally:
                os.close(fd)

    def test_permission_denial_does_not_prompt(self):
        self.stop_servers()
        fake = self.directory / "denied"
        fake.write_text(f'''#!{sys.executable}
import json, sys
assert '-r' not in sys.argv
if sys.argv[1:] == ['-l']:
    print(json.dumps([{DEVICE!r}]))
else:
    print('Permission denied.')
    sys.exit(1)
''')
        fake.chmod(0o700)
        self.start_server("--termux-usb", str(fake))
        with self.assertRaises(OSError) as error:
            broker.request(self.address, 2, DEVICE.encode())
        self.assertEqual(error.exception.errno, errno.EACCES)

    def test_multiple_fds_rejected_and_closed(self):
        left, right = socket.socketpair()
        import array
        fd = os.open(self.directory / "descriptor.bin", os.O_RDONLY)
        try:
            left.sendmsg([broker.HEADER.pack(b"TUSB", 1, 2, 0, 0)],
                         [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd, fd]))])
            with self.assertRaises(OSError) as error:
                broker.receive_packet(right)
            self.assertEqual(error.exception.errno, errno.EPROTO)
        finally:
            os.close(fd)
            left.close()
            right.close()

    @unittest.skipUnless(shutil.which("proot"), "PRoot is not installed")
    def test_proot_fake_root_client(self):
        environment = dict(self.environment, TERMUX_USB_BRIDGE_SOCKET="/tusb-test/broker.sock")
        code = ("import ctypes as C,os;lib=C.CDLL(" + repr(str(Path(LIBRARY).resolve())) + ",use_errno=True);"
                "lib.tusb_open.argtypes=[C.c_char_p,C.c_char_p];"
                "fd=lib.tusb_open(None," + repr(DEVICE.encode()) + ");assert fd>=0,C.get_errno();"
                "print(os.read(fd,18).hex());os.close(fd)")
        result = subprocess.run([shutil.which("proot"), "-0", "-r", "/",
                                 "-b", f"{self.directory}:/tusb-test", "-w", "/tusb-test",
                                 sys.executable, "-c", code], env=environment,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(DESCRIPTOR.hex(), result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
