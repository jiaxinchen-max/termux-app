#!/usr/bin/env python3
"""Host protocol tests; these do not claim Android USB or UI validation."""
import array
import errno
import importlib.util
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

SOURCE = Path(__file__).resolve().parents[1] / "src/main/assets/termux-usb/termux-usb-app.py"
spec = importlib.util.spec_from_file_location("usb_client", SOURCE)
usb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usb)


class PacketSocket:
    def __init__(self, payload, fds=(), flags=0):
        self.payload = bytearray(payload)
        self.fds = list(fds)
        self.flags = flags
        self.sent = None

    def sendall(self, data):
        self.sent = data

    def recvmsg(self, size, ancillary_size):
        data = bytes(self.payload[:size])
        del self.payload[:size]
        ancillary = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", self.fds).tobytes())] if self.fds else []
        self.fds.clear()
        return data, ancillary, self.flags, None


class ClientTests(unittest.TestCase):
    def request(self, action="list"):
        return {"version": 1, "action": action, "device": None if action == "list" else "/dev/bus/usb/001/002", "request": False}

    def packet(self, result, **changes):
        response = {"version": 1, "errno": 0, "result": result}
        response.update(changes)
        return (json.dumps(response) + "\n").encode()

    def exchange(self, peer, action="list"):
        with patch.object(usb, "peer_uid", return_value=502):
            return usb.exchange(peer, self.request(action), 502)

    def test_kernel_uid_is_kernel_credentials(self):
        left, right = socket.socketpair()
        try:
            self.assertEqual(usb.kernel_uid(), usb.peer_uid(left))
        finally:
            left.close()
            right.close()

    def test_list_round_trip(self):
        left, right = socket.socketpair()
        observed = []
        def server():
            with right:
                line = bytearray()
                while not line.endswith(b"\n"):
                    line.extend(right.recv(1))
                observed.append(json.loads(line))
                right.sendall(self.packet(["/dev/bus/usb/001/002"]))
        worker = threading.Thread(target=server)
        worker.start()
        with left:
            result, fd = usb.exchange(left, self.request(), usb.kernel_uid())
        worker.join(2)
        self.assertEqual(observed, [self.request()])
        self.assertEqual(result, ["/dev/bus/usb/001/002"])
        self.assertIsNone(fd)

    def test_open_real_scm_rights_fd(self):
        left, right = socket.socketpair()
        with tempfile.TemporaryFile() as source:
            source.write(b"USB-FD")
            source.seek(0)
            def server():
                with right:
                    while not right.recv(4096).endswith(b"\n"):
                        pass
                    right.sendmsg([self.packet(True)], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [source.fileno()]))])
            worker = threading.Thread(target=server)
            worker.start()
            with left:
                result, fd = usb.exchange(left, self.request("open"), usb.kernel_uid())
            worker.join(2)
            try:
                self.assertIs(result, True)
                self.assertFalse(os.get_inheritable(fd))
                self.assertEqual(os.read(fd, 6), b"USB-FD")
            finally:
                os.close(fd)

    def test_peer_uid_checked_before_request(self):
        peer = PacketSocket(self.packet([]))
        with patch.object(usb, "peer_uid", return_value=503), self.assertRaises(OSError) as failure:
            usb.exchange(peer, self.request(), 502)
        self.assertEqual(failure.exception.errno, errno.EACCES)
        self.assertIsNone(peer.sent)

    def test_bad_version(self):
        for version in [True, 1.0, "1", 2]:
            with self.subTest(version=version), self.assertRaises(OSError):
                self.exchange(PacketSocket(self.packet([], version=version)))

    def test_unicode_digit_paths_are_rejected(self):
        self.assertFalse(usb.valid_device("/dev/bus/usb/٠٠١/٠٠٢"))

    def test_bad_errno_type(self):
        with self.assertRaises(OSError):
            self.exchange(PacketSocket(self.packet([], errno=False)))

    def test_explicit_permission_failure(self):
        with self.assertRaises(OSError) as failure:
            self.exchange(PacketSocket(self.packet(None, errno=13, error="Keep the App visible")), "permission")
        self.assertEqual(failure.exception.errno, 13)
        self.assertIn("Keep the App visible", str(failure.exception))

    def test_truncated_response(self):
        with self.assertRaises(OSError) as failure:
            self.exchange(PacketSocket(b'{"version":1'))
        self.assertEqual(failure.exception.errno, errno.EPIPE)

    def test_oversized_response(self):
        with self.assertRaises(OSError) as failure:
            self.exchange(PacketSocket(b"x" * (usb.LIMIT + 1)))
        self.assertEqual(failure.exception.errno, errno.EMSGSIZE)

    def test_invalid_device_in_response(self):
        with self.assertRaises(OSError):
            self.exchange(PacketSocket(self.packet(["/etc/passwd"])))

    def test_open_requires_fd(self):
        with self.assertRaises(OSError):
            self.exchange(PacketSocket(self.packet(True)), "open")

    def test_unexpected_fds_are_closed(self):
        for count, flags in [(1, 0), (2, 0), (1, socket.MSG_CTRUNC)]:
            with self.subTest(count=count, flags=flags), tempfile.TemporaryFile() as source:
                descriptors = [os.dup(source.fileno()) for _ in range(count)]
                with self.assertRaises(OSError):
                    self.exchange(PacketSocket(self.packet([]), descriptors, flags))
                for fd in descriptors:
                    with self.assertRaises(OSError):
                        os.fstat(fd)

    def test_error_response_closes_fd(self):
        with tempfile.TemporaryFile() as source:
            fd = os.dup(source.fileno())
            with self.assertRaises(OSError):
                self.exchange(PacketSocket(self.packet(None, errno=13), [fd]), "open")
            with self.assertRaises(OSError):
                os.fstat(fd)

    def test_non_open_operation_cannot_have_fd(self):
        with tempfile.TemporaryFile() as source:
            fd = os.dup(source.fileno())
            with self.assertRaises(OSError):
                self.exchange(PacketSocket(self.packet(True), [fd]), "permission")

    def test_no_backend_retry(self):
        with patch.object(usb, "transact", side_effect=OSError(errno.ECONNREFUSED, "App not running")) as call:
            with self.assertRaises(OSError):
                usb.main(["-l"])
            self.assertEqual(call.call_count, 1)

    def test_argument_conflicts_never_contact_broker(self):
        with patch.object(usb, "transact") as call:
            for arguments in [["-l", "-r"], ["-E", "/dev/bus/usb/001/002"], ["/dev/bus/usb/000/002"]]:
                with self.subTest(arguments=arguments), self.assertRaises(SystemExit):
                    usb.main(arguments)
            call.assert_not_called()

    def callback_probe(self, as_environment):
        with tempfile.TemporaryDirectory() as directory:
            device = Path(directory) / "device"
            device.write_bytes(b"CALLBACK-FD")
            child = "import os,sys; fd=int(os.environ['TERMUX_USB_FD'] if len(sys.argv)==1 else sys.argv[1]); print(os.read(fd,11).decode())"
            command = shlex.join([sys.executable, "-c", child])
            arguments = ["-e", command, "/dev/bus/usb/001/002"]
            if as_environment:
                arguments.insert(0, "-E")
            loader = ("import importlib.util,os; s=importlib.util.spec_from_file_location('usb'," + repr(str(SOURCE))
                + ");m=importlib.util.module_from_spec(s);s.loader.exec_module(m);"
                + "m.transact=lambda request:(True,os.open(" + repr(str(device)) + ",os.O_RDONLY));m.main(" + repr(arguments) + ")")
            result = subprocess.run([sys.executable, "-c", loader], capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "CALLBACK-FD")

    def test_callback_fd_argument_survives_exec(self):
        self.callback_probe(False)

    def test_callback_environment_fd_survives_exec(self):
        self.callback_probe(True)


if __name__ == "__main__":
    unittest.main()
