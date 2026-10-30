#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""USB FD broker. The mock backend supplies ordinary files, NOT USB ioctls."""
import argparse
import array
import contextlib
import errno
import json
import logging
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import socket
import socketserver
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time

HEADER = struct.Struct("!4sBBHI")
LIMIT = 65536
LOG = logging.getLogger("termux-usb-bridge")
stopping = False


def validate_device(path):
    match = re.fullmatch(r"/dev/bus/usb/(\d{3})/(\d{3})", path)
    if not match or not 1 <= int(match[1]) <= 255 or not 1 <= int(match[2]) <= 127:
        raise OSError(errno.EINVAL, "invalid USB device path")
    return path


def peer_uid(sock):
    if hasattr(socket, "SO_PEERCRED"):
        return struct.unpack("3i", sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1]
    if hasattr(sock, "getpeereid"):
        return sock.getpeereid()[0]
    if sys.platform == "darwin" and hasattr(socket, "LOCAL_PEERCRED"):
        # Darwin xucred starts with uint32 version, uid_t uid.
        credentials = sock.getsockopt(0, socket.LOCAL_PEERCRED, 128)
        version, uid = struct.unpack_from("=II", credentials)
        if version == 0:
            return uid
    raise OSError(errno.ENOTSUP, "peer credential checking unavailable")


def read_exact(sock, length):
    result = bytearray()
    while len(result) < length:
        part = sock.recv(length - len(result))
        if not part:
            raise OSError(errno.EPROTO, "truncated message")
        result.extend(part)
    return bytes(result)


def kernel_uid():
    # PRoot may spoof getuid(); credentials on a socketpair come from the kernel.
    left, right = socket.socketpair()
    try:
        return peer_uid(left)
    finally:
        left.close()
        right.close()


def send_packet(sock, op, body=b"", status=0, fd=None):
    if len(body) > LIMIT:
        raise OSError(errno.EMSGSIZE, "message too large")
    packet = HEADER.pack(b"TUSB", 1, op, status, len(body)) + body
    ancillary = [] if fd is None else [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]))]
    sent = sock.sendmsg([packet], ancillary)
    if sent == 0:
        raise OSError(errno.EPIPE, "socket closed")
    # A full sendmsg needs no second send. Android may report EPIPE for an
    # empty send after the peer has already replied and closed the socket.
    if sent < len(packet):
        sock.sendall(packet[sent:])


def receive_packet(sock):
    first, ancillary, flags, _ = sock.recvmsg(1, socket.CMSG_SPACE(16 * array.array("i").itemsize))
    fds = []
    try:
        for level, kind, data in ancillary:
            if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                values = array.array("i")
                values.frombytes(data[:len(data) - len(data) % values.itemsize])
                fds.extend(values)
        if flags & socket.MSG_CTRUNC or len(fds) > 1 or not first:
            raise OSError(errno.EPROTO, "invalid FD response")
        magic, version, op, status, length = HEADER.unpack(first + read_exact(sock, HEADER.size - 1))
        if magic != b"TUSB" or version != 1 or length > LIMIT:
            raise OSError(errno.EPROTO, "invalid response header")
        body = read_exact(sock, length)
        if status:
            raise OSError(status, body.decode("utf-8", "replace"))
        fd = fds[0] if fds else None
        if fd is not None:
            os.set_inheritable(fd, False)
            fds.pop()
        return op, body, fd
    finally:
        for fd in fds:
            os.close(fd)


def request(address, op, body=b""):
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(15)
        sock.connect(address)
        if peer_uid(sock) != kernel_uid():
            raise OSError(errno.EACCES, "unexpected broker UID")
        send_packet(sock, op, body)
        returned, payload, fd = receive_packet(sock)
        if returned != op or (op == 1 and fd is not None) or (op == 2 and (fd is None or payload)):
            if fd is not None:
                os.close(fd)
            raise OSError(errno.EPROTO, "unexpected response")
        return payload, fd


class MockBackend:
    def __init__(self, manifest):
        self.manifest = Path(manifest).resolve()

    def entries(self):
        data = json.loads(self.manifest.read_text())
        if not isinstance(data, dict):
            raise OSError(errno.EINVAL, "mock manifest must map USB paths to files")
        return {validate_device(k): v for k, v in data.items()}

    def list_devices(self):
        return sorted(self.entries())

    def open_device(self, device):
        value = self.entries().get(device)
        if value is None:
            raise OSError(errno.ENODEV, "device disconnected")
        if not isinstance(value, str):
            raise OSError(errno.EINVAL, "mock file path must be a string")
        path = Path(value)
        if not path.is_absolute():
            path = self.manifest.parent / path
        return os.open(path, os.O_RDONLY | os.O_CLOEXEC)


class TermuxBackend:
    def __init__(self, runtime_dir, command="termux-usb"):
        self.runtime_dir = runtime_dir
        self.command = command
        self.lock = threading.Lock()
        self.cached = None
        self.cached_at = 0

    def list_devices(self):
        with self.lock:
            if self.cached is not None and time.monotonic() - self.cached_at < 0.5:
                return self.cached
            result = subprocess.run([self.command, "-l"], capture_output=True, timeout=10)
            if result.returncode:
                raise OSError(errno.EIO, result.stderr.decode("utf-8", "replace")[:512] or "termux-usb -l failed")
            if not result.stdout.strip():
                raise OSError(errno.EACCES, "termux-usb returned no data; allow unrestricted battery/background execution for Termux:API in Android settings")
            paths = json.loads(result.stdout)
            if not isinstance(paths, list):
                raise OSError(errno.EPROTO, "invalid termux-usb device list")
            self.cached = sorted({validate_device(path) for path in paths})
            self.cached_at = time.monotonic()
            return self.cached

    def open_device(self, device):
        # Deliberately omit -r: opening/enumeration must never trigger permission dialogs.
        with tempfile.TemporaryDirectory(prefix="fd-", dir=self.runtime_dir) as directory:
            address = str(Path(directory) / "s")
            token = secrets.token_hex(16)
            callback = shlex.join([sys.executable, str(Path(__file__).resolve()), "handoff", address, token])
            with socket.socket(socket.AF_UNIX) as listener, tempfile.TemporaryFile() as output:
                listener.bind(address)
                listener.listen(1)
                listener.settimeout(0.1)
                process = subprocess.Popen([self.command, "-e", callback, device], stdout=output, stderr=output)
                fd = None
                try:
                    deadline = time.monotonic() + 10
                    while True:
                        try:
                            connection, _ = listener.accept()
                            break
                        except socket.timeout:
                            if process.poll() is not None:
                                raise OSError(errno.EACCES, "USB open failed; run authorize DEVICE first")
                            if time.monotonic() >= deadline:
                                raise OSError(errno.ETIMEDOUT, "termux-usb callback timed out")
                    with connection:
                        connection.settimeout(2)
                        if peer_uid(connection) != os.getuid():
                            raise OSError(errno.EACCES, "unexpected callback UID")
                        op, body, fd = receive_packet(connection)
                        if op != 3 or body != token.encode() or fd is None:
                            raise OSError(errno.EPROTO, "invalid USB callback")
                    process.wait(timeout=2)
                    if process.returncode:
                        raise OSError(errno.EIO, "termux-usb callback failed")
                    # Android may already have consumed the descriptor stream.
                    os.lseek(fd, 0, os.SEEK_SET)
                    result, fd = fd, None
                    return result
                finally:
                    if fd is not None:
                        os.close(fd)
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=1)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()


class UsbfsBackend:
    """Linux VM testing backend; relies on existing OS permissions."""
    def list_devices(self):
        return sorted(str(p) for p in Path("/dev/bus/usb").glob("[0-9][0-9][0-9]/[0-9][0-9][0-9]"))

    def open_device(self, device):
        return os.open(device, os.O_RDWR | os.O_CLOEXEC)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        op = 0
        try:
            self.request.settimeout(12)
            if peer_uid(self.request) != os.getuid():
                raise OSError(errno.EACCES, "client UID differs from broker")
            magic, version, op, status, length = HEADER.unpack(read_exact(self.request, HEADER.size))
            if magic != b"TUSB" or version != 1 or status or length > LIMIT:
                raise OSError(errno.EPROTO, "invalid request header")
            body = read_exact(self.request, length)
            if op == 1 and not body:
                payload = "".join(path + "\n" for path in self.server.backend.list_devices()).encode()
                send_packet(self.request, op, payload)
            elif op == 2:
                path = validate_device(body.decode("ascii"))
                if path not in self.server.backend.list_devices():
                    raise OSError(errno.ENODEV, "device disconnected")
                fd = self.server.backend.open_device(path)
                try:
                    send_packet(self.request, op, fd=fd)
                finally:
                    os.close(fd)
            else:
                raise OSError(errno.ENOSYS, "unknown request")
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            status = error.errno if isinstance(error, OSError) and error.errno else errno.EIO
            with contextlib.suppress(OSError):
                send_packet(self.request, op, str(error).encode()[:1024], status=status)
            LOG.debug("request failed: %s", error)


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 64
    # Bound work even if a same-UID client floods sockets.
    def __init__(self, address, backend):
        self.backend = backend
        self.workers = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.workers.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.workers.release()


def serve(args):
    address = Path(args.socket).absolute()
    address.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = address.parent.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise OSError(errno.EACCES, "socket directory must belong to this UID and have mode 0700")
    # Never unlink an existing socket: another live broker may own it.
    if args.mock_json:
        backend = MockBackend(args.mock_json)
    elif args.usbfs:
        if not sys.platform.startswith("linux"):
            raise OSError(errno.ENOTSUP, "usbfs backend requires Linux")
        backend = UsbfsBackend()
    else:
        backend = TermuxBackend(str(address.parent), args.termux_usb)
    if args.mock_json:
        LOG.warning("mock backend: ordinary file FDs only; USB ioctls are not emulated")
    server = Server(str(address), backend)
    try:
        os.chmod(address, 0o600)
        LOG.info("listening at %s", address)
        server.timeout = 0.2
        while not stopping:
            server.handle_request()
    finally:
        server.server_close()
        address.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default = str(Path(os.environ.get("TMPDIR", "/tmp")) / "termux-usb-bridge" / "broker.sock")
    parser.add_argument("--socket", default=os.environ.get("TERMUX_USB_BRIDGE_SOCKET", default))
    parser.add_argument("--verbose", action="store_true")
    commands = parser.add_subparsers(dest="action", required=True)
    server = commands.add_parser("serve")
    backends = server.add_mutually_exclusive_group()
    backends.add_argument("--mock-json", help="map /dev/bus/usb/BBB/DDD to ordinary files; no USB ioctl support")
    backends.add_argument("--usbfs", action="store_true", help="Linux VM only: open existing usbfs nodes directly")
    server.add_argument("--termux-usb", default="termux-usb")
    commands.add_parser("list")
    authorize = commands.add_parser("authorize")
    authorize.add_argument("device", type=validate_device)
    run = commands.add_parser("exec", help="execute a program with TERMUX_USB_FD and an inherited USB FD")
    run.add_argument("device", type=validate_device)
    run.add_argument("command", nargs=argparse.REMAINDER)
    handoff = commands.add_parser("handoff", help=argparse.SUPPRESS)
    handoff.add_argument("address")
    handoff.add_argument("token")
    handoff.add_argument("fd", type=int)
    args = parser.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(name)s: %(message)s")
    try:
        if args.action == "serve":
            def stop(_signum, _frame):
                global stopping
                stopping = True
            signal.signal(signal.SIGTERM, stop)
            serve(args)
        elif args.action == "list":
            body, _ = request(args.socket, 1)
            sys.stdout.write(body.decode())
        elif args.action == "authorize":
            return subprocess.call(["termux-usb", "-r", args.device])
        elif args.action == "exec":
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            if not command:
                parser.error("exec requires a command")
            _, fd = request(args.socket, 2, args.device.encode())
            try:
                os.set_inheritable(fd, True)
                os.environ["TERMUX_USB_FD"] = str(fd)
                os.execvp(command[0], command)
            finally:
                os.close(fd)
        elif args.action == "handoff":
            with socket.socket(socket.AF_UNIX) as sock:
                sock.settimeout(2)
                sock.connect(args.address)
                if peer_uid(sock) != os.getuid():
                    raise OSError(errno.EACCES, "unexpected broker UID")
                send_packet(sock, 3, args.token.encode(), fd=args.fd)
        return 0
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        LOG.error("%s", error)
        return 1


if __name__ == "__main__":
    sys.exit(main())
