#!@PYTHON@
"""Same-UID client for the embedded Android USB module. No external API backend."""
import argparse
import array
import errno
import json
import os
import re
import shlex
import socket
import struct
import sys

LIMIT = 65536


def peer_uid(connection):
    if hasattr(socket, "SO_PEERCRED"):
        return struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))[1]
    if hasattr(connection, "getpeereid"):
        return connection.getpeereid()[0]
    if sys.platform == "darwin" and hasattr(socket, "LOCAL_PEERCRED"):
        version, uid = struct.unpack_from("=II", connection.getsockopt(0, socket.LOCAL_PEERCRED, 128))
        if version == 0:
            return uid
    raise OSError(errno.ENOTSUP, "kernel peer credentials unavailable")


def kernel_uid():
    # PRoot may spoof getuid(), but not credentials supplied by the kernel.
    left, right = socket.socketpair()
    try:
        return peer_uid(left)
    finally:
        left.close()
        right.close()


def transact(request):
    uid = kernel_uid()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(40 if request.get("request") else 10)
        connection.connect("\0com.termux.usb.v1." + str(uid))
        return exchange(connection, request, uid)


def exchange(connection, request, uid):
    if peer_uid(connection) != uid:
        raise OSError(errno.EACCES, "unexpected embedded USB broker UID")
    connection.sendall((json.dumps(request, separators=(",", ":")) + "\n").encode())
    fds = []
    try:
        payload = bytearray()
        while not payload.endswith(b"\n"):
            part, ancillary, flags, _ = connection.recvmsg(4096, socket.CMSG_SPACE(16 * array.array("i").itemsize))
            for level, kind, raw in ancillary:
                if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                    values = array.array("i")
                    values.frombytes(raw[:len(raw) - len(raw) % values.itemsize])
                    fds.extend(values)
            if flags & socket.MSG_CTRUNC or len(fds) > 1:
                raise OSError(errno.EPROTO, "invalid embedded USB file descriptors")
            if not part:
                raise OSError(errno.EPIPE, "embedded USB broker closed without a complete response")
            payload.extend(part)
            if len(payload) > LIMIT:
                raise OSError(errno.EMSGSIZE, "embedded USB response too large")
        response = json.loads(payload)
        if (not isinstance(response, dict) or type(response.get("version")) is not int or response["version"] != 1
                or type(response.get("errno")) is not int or not 0 <= response["errno"] <= 4095):
            raise OSError(errno.EPROTO, "invalid embedded USB response")
        if response["errno"]:
            raise OSError(response["errno"], str(response.get("error", "embedded USB operation failed")))
        expected_fd = request["action"] == "open"
        if len(fds) != int(expected_fd):
            raise OSError(errno.EPROTO, "unexpected embedded USB FD response")
        result = response.get("result")
        if request["action"] == "list":
            if not isinstance(result, list) or any(not valid_device(path) for path in result):
                raise OSError(errno.EPROTO, "invalid embedded USB device list")
        elif result is not True:
            raise OSError(errno.EPROTO, "invalid embedded USB operation result")
        fd = fds.pop() if fds else None
        if fd is not None:
            try:
                os.set_inheritable(fd, False)
            except BaseException:
                os.close(fd)
                raise
        return result, fd
    finally:
        for fd in fds:
            os.close(fd)


def valid_device(path):
    match = re.fullmatch(r"/dev/bus/usb/([0-9]{3})/([0-9]{3})", path) if isinstance(path, str) else None
    return bool(match and 1 <= int(match[1]) <= 255 and 1 <= int(match[2]) <= 127)


def main(arguments=None):
    parser = argparse.ArgumentParser(description="Use the App's embedded Android USB API")
    parser.add_argument("-l", action="store_true", help="list USB devices")
    parser.add_argument("-r", action="store_true", help="request permission while the App is visible")
    parser.add_argument("-e", metavar="COMMAND", help="execute argv with the USB FD appended")
    parser.add_argument("-E", action="store_true", help="pass the FD through TERMUX_USB_FD instead")
    parser.add_argument("device", nargs="?")
    args = parser.parse_args(arguments)
    if args.l:
        if args.device or args.r or args.e or args.E:
            parser.error("-l cannot be combined with a device, permission request, or callback")
    elif not valid_device(args.device):
        parser.error("a /dev/bus/usb/BBB/DDD device path is required")
    if args.E and not args.e:
        parser.error("-E requires -e")
    command = shlex.split(args.e) if args.e is not None else None
    if command == []:
        parser.error("callback command is empty")
    request = {"version": 1, "action": "list" if args.l else "open" if command else "permission",
               "device": args.device, "request": args.r}
    result, fd = transact(request)
    if args.l:
        print(json.dumps(result))
    elif command:
        try:
            environment = os.environ.copy()
            if args.E:
                environment["TERMUX_USB_FD"] = str(fd)
            else:
                command.append(str(fd))
            os.set_inheritable(fd, True)
            os.execvpe(command[0], command, environment)
        finally:
            os.close(fd)
    else:
        print("Permission granted.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError) as failure:
        print("termux-usb-app: " + str(failure), file=sys.stderr)
        sys.exit(1)
