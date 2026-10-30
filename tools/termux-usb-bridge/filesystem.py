#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""FAT/exFAT CLI and authenticated loopback WebDAV over the native USB filesystem."""
import argparse
import base64
import ctypes as C
import ctypes.util
import datetime
import errno
import html
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import mimetypes
import os
from pathlib import Path
import re
import secrets
import signal
import sys
import threading
import time
from urllib.parse import quote, unquote, urlsplit
import xml.etree.ElementTree as ET


class Entry(C.Structure):
    _fields_ = [("size", C.c_uint64), ("attributes", C.c_uint32),
                ("fat_time", C.c_uint32), ("name", C.c_char * 1024)]


class VolumeInfo(C.Structure):
    _fields_ = [("size", C.c_uint64), ("free", C.c_uint64),
                ("serial", C.c_uint32), ("writable", C.c_uint32),
                ("type", C.c_char * 16), ("label", C.c_char * 1024)]


Visitor = C.CFUNCTYPE(C.c_int, C.POINTER(Entry), C.c_void_p)


class Filesystem:
    def __init__(self, device=None, image=None, writable=False, lun=0, library=None):
        name = library or os.environ.get("TUSB_FS_LIBRARY") or ctypes.util.find_library("termux-usb-fs") or "libtermux-usb-fs.so"
        self.lib = C.CDLL(name, use_errno=True)
        self.lib.tusb_fs_mount_usb.argtypes = [C.c_char_p, C.c_int, C.c_uint]
        self.lib.tusb_fs_mount_image.argtypes = [C.c_char_p, C.c_int]
        self.lib.tusb_fs_stat.argtypes = [C.c_char_p, C.POINTER(Entry)]
        self.lib.tusb_fs_list.argtypes = [C.c_char_p, Visitor, C.c_void_p]
        for operation in ("read", "write"):
            function = getattr(self.lib, "tusb_fs_" + operation)
            function.argtypes = [C.c_char_p, C.c_void_p, C.c_size_t, C.c_uint64] + ([C.c_int] if operation == "write" else [])
            function.restype = C.c_ssize_t
        self.lib.tusb_fs_mkdir.argtypes = self.lib.tusb_fs_unlink.argtypes = [C.c_char_p]
        self.lib.tusb_fs_rename.argtypes = [C.c_char_p, C.c_char_p]
        self.lib.tusb_fs_create_image.argtypes = [C.c_char_p, C.c_uint64, C.c_int]
        self.lib.tusb_fs_volume_info.argtypes = [C.POINTER(VolumeInfo)]
        self.writable = writable
        self.lock = threading.RLock()
        if device:
            self.check(self.lib.tusb_fs_mount_usb(os.fsencode(device), writable, lun))
        elif image:
            self.check(self.lib.tusb_fs_mount_image(os.fsencode(image), writable))

    @staticmethod
    def check(result):
        if result < 0:
            code = C.get_errno()
            raise OSError(code, os.strerror(code))
        return result

    @staticmethod
    def info(entry):
        value = entry.fat_time
        try:
            stamp = datetime.datetime((value >> 25) + 1980, (value >> 21) & 15, (value >> 16) & 31,
                                      (value >> 11) & 31, (value >> 5) & 63, (value & 31) * 2,
                                      tzinfo=datetime.timezone.utc).isoformat().replace("+00:00", "Z")
        except ValueError:
            stamp = "1980-01-01T00:00:00Z"
        return dict(name=entry.name.decode("utf-8"), size=entry.size,
                    directory=bool(entry.attributes & 0x10), modified=stamp)

    def stat(self, path):
        entry = Entry()
        self.check(self.lib.tusb_fs_stat(path.encode(), C.byref(entry)))
        return self.info(entry)

    def list(self, path):
        values = []
        @Visitor
        def visit(entry, _):
            values.append(self.info(entry.contents))
            return 0
        self.check(self.lib.tusb_fs_list(path.encode(), visit, None))
        return values

    def read(self, path, count, offset=0):
        buffer = C.create_string_buffer(count)
        actual = self.check(self.lib.tusb_fs_read(path.encode(), buffer, count, offset))
        return buffer.raw[:actual]

    def write(self, path, data, offset=0, truncate=False):
        return self.check(self.lib.tusb_fs_write(path.encode(), data, len(data), offset, truncate))

    def mkdir(self, path): self.check(self.lib.tusb_fs_mkdir(path.encode()))
    def unlink(self, path): self.check(self.lib.tusb_fs_unlink(path.encode()))
    def rename(self, source, destination): self.check(self.lib.tusb_fs_rename(source.encode(), destination.encode()))
    def close(self): self.check(self.lib.tusb_fs_unmount())

    def volume_info(self):
        info = VolumeInfo()
        self.check(self.lib.tusb_fs_volume_info(C.byref(info)))
        return dict(size=info.size, free=info.free, serial=info.serial,
                    writable=bool(info.writable), type=info.type.decode(), label=info.label.decode())

    def remove_tree(self, path):
        if self.stat(path)["directory"]:
            for entry in self.list(path):
                self.remove_tree(path.rstrip("/") + "/" + entry["name"])
        self.unlink(path)


def clean_path(url):
    value = unquote(urlsplit(url).path, errors="strict")
    if not value.startswith("/") or "\0" in value or "\\" in value or ":" in value:
        raise ValueError("invalid filesystem path")
    if any(part in (".", "..") for part in value.split("/")):
        raise ValueError("relative components are not permitted")
    return "/" + "/".join(part for part in value.split("/") if part)


class WebDAV(BaseHTTPRequestHandler):
    server_version = "TermuxUSBWebDAV/0.2"
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def reply(self, code, body=b"", content_type="text/plain; charset=utf-8", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for key, value in (headers or {}).items(): self.send_header(key, value)
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD": self.wfile.write(body)

    def authenticated(self):
        expected = "Basic " + base64.b64encode(("tusb:" + self.server.password).encode()).decode()
        return secrets.compare_digest(self.headers.get("Authorization", ""), expected)

    def handle_expect_100(self):
        if not self.authenticated():
            self.reply(401, headers={"WWW-Authenticate": 'Basic realm="Termux USB"'})
            return False
        return super().handle_expect_100()

    def discard_unauthenticated_body(self):
        # Closing TCP with an unread upload can reset the connection and lose
        # the 401 response. Older KIO then cannot retry with credentials.
        # Bound memory and total time; never pass rejected data to the FS.
        if self.headers.get("Transfer-Encoding"):
            return
        try:
            remaining = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return
        previous_timeout = self.connection.gettimeout()
        deadline = time.monotonic() + 10
        try:
            while remaining > 0:
                timeout = deadline - time.monotonic()
                if timeout <= 0:
                    break
                self.connection.settimeout(timeout)
                data = self.rfile.read(min(remaining, 65536))
                if not data:
                    break
                remaining -= len(data)
        except OSError:
            pass
        finally:
            self.connection.settimeout(previous_timeout)

    def execute(self):
        if not self.authenticated():
            self.discard_unauthenticated_body()
            self.reply(401, headers={"WWW-Authenticate": 'Basic realm="Termux USB"'})
            return
        try:
            path = clean_path(self.path)
            with self.server.filesystem.lock:
                self.dispatch(path)
        except (ValueError, UnicodeError, ET.ParseError):
            self.reply(400, b"Invalid request")
        except OSError as error:
            status = {errno.ENOENT: 404, errno.EEXIST: 405, errno.EROFS: 403,
                      errno.EACCES: 403, errno.ENOSPC: 507, errno.ENOTEMPTY: 409,
                      errno.EINVAL: 400}.get(error.errno, 500)
            self.reply(status, str(error).encode())

    def destination(self):
        value = self.headers.get("Destination", "")
        parsed = urlsplit(value)
        host = urlsplit("//" + self.headers.get("Host", ""))
        # KIO includes URL credentials in Destination. Userinfo is not part of
        # the server address; request authentication is checked independently.
        target_port = parsed.port if parsed.port is not None else 80
        host_port = host.port if host.port is not None else 80
        if not value or (parsed.netloc and
                         (parsed.hostname, target_port) != (host.hostname, host_port)):
            raise ValueError("Destination must use this server")
        path = clean_path(value)
        if path == "/": raise ValueError("cannot replace root")
        return path

    def replace(self, source, target):
        fs = self.server.filesystem
        backup = None
        try:
            fs.stat(target)
        except FileNotFoundError:
            pass
        else:
            if self.headers.get("Overwrite", "T") != "T":
                self.reply(412)
                return False
            backup = target + ".tusb-backup-" + secrets.token_hex(4)
            fs.rename(target, backup)
        try:
            fs.rename(source, target)
        except OSError:
            if backup: fs.rename(backup, target)
            raise
        if backup: fs.remove_tree(backup)
        self.reply(204 if backup else 201)
        return True

    def dispatch(self, path):
        fs = self.server.filesystem
        if self.command == "OPTIONS":
            methods = "OPTIONS, GET, HEAD, PROPFIND"
            if fs.writable: methods += ", PUT, MKCOL, DELETE, MOVE, COPY"
            self.reply(200, headers={"DAV": "1", "Allow": methods})
        elif self.command == "PROPFIND":
            depth = self.headers.get("Depth", "1")
            if depth not in ("0", "1"):
                self.reply(403, b"Only Depth 0 and 1 supported"); return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > 65536: raise ValueError("PROPFIND too large")
            if length:
                document = self.rfile.read(length)
                if b"<!DOCTYPE" in document.upper(): raise ValueError("DTD not permitted")
                ET.fromstring(document)
            info = fs.stat(path)
            volume_info = fs.volume_info()
            entries = [(path, info)]
            if depth == "1" and info["directory"]:
                entries += [(path.rstrip("/") + "/" + e["name"], e) for e in fs.list(path)]
            root = ET.Element("{DAV:}multistatus")
            for name, item in entries:
                response = ET.SubElement(root, "{DAV:}response")
                ET.SubElement(response, "{DAV:}href").text = quote(name, safe="/") + ("/" if item["directory"] and name != "/" else "")
                propstat = ET.SubElement(response, "{DAV:}propstat")
                prop = ET.SubElement(propstat, "{DAV:}prop")
                ET.SubElement(prop, "{DAV:}displayname").text = item["name"] or "/"
                resource = ET.SubElement(prop, "{DAV:}resourcetype")
                if item["directory"]: ET.SubElement(resource, "{DAV:}collection")
                ET.SubElement(prop, "{DAV:}getcontentlength").text = str(item["size"])
                ET.SubElement(prop, "{DAV:}getcontenttype").text = "httpd/unix-directory" if item["directory"] else "application/octet-stream"
                ET.SubElement(prop, "{DAV:}quota-available-bytes").text = str(volume_info["free"])
                ET.SubElement(prop, "{DAV:}quota-used-bytes").text = str(volume_info["size"] - volume_info["free"])
                privileges = ET.SubElement(prop, "{DAV:}current-user-privilege-set")
                for permission in (["read", "write"] if fs.writable else ["read"]):
                    ET.SubElement(ET.SubElement(privileges, "{DAV:}privilege"), "{DAV:}" + permission)
                ET.SubElement(prop, "{DAV:}creationdate").text = item["modified"]
                stamp = datetime.datetime.fromisoformat(item["modified"].replace("Z", "+00:00"))
                ET.SubElement(prop, "{DAV:}getlastmodified").text = stamp.strftime("%a, %d %b %Y %H:%M:%S GMT")
                ET.SubElement(propstat, "{DAV:}status").text = "HTTP/1.1 200 OK"
            self.reply(207, ET.tostring(root, encoding="utf-8", xml_declaration=True), "application/xml; charset=utf-8")
        elif self.command in ("GET", "HEAD"):
            info = fs.stat(path)
            if info["directory"]:
                body = "<ul>" + "".join('<li><a href="' + quote(path.rstrip("/") + "/" + e["name"], safe="/") + '">' + html.escape(e["name"]) + "</a></li>" for e in fs.list(path)) + "</ul>"
                self.reply(200, body.encode(), "text/html; charset=utf-8"); return
            start, end, status = 0, info["size"] - 1, 200
            requested = self.headers.get("Range")
            if requested:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", requested)
                if not match or not any(match.groups()): self.reply(416); return
                if not match[1]: start = max(0, info["size"] - int(match[2]))
                else:
                    start = int(match[1])
                    if match[2]: end = min(end, int(match[2]))
                if start > end or start >= info["size"]: self.reply(416, headers={"Content-Range": f"bytes */{info['size']}"}); return
                status = 206
            length = max(0, end - start + 1)
            self.send_response(status)
            self.send_header("Content-Length", str(length))
            self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "close")
            if status == 206: self.send_header("Content-Range", f"bytes {start}-{end}/{info['size']}")
            self.end_headers(); self.close_connection = True
            if self.command == "GET":
                while length:
                    data = fs.read(path, min(length, 65536), start)
                    if not data: raise OSError(errno.EIO, "short filesystem read")
                    self.wfile.write(data);start += len(data);length -= len(data)
        elif self.command == "PUT":
            if not fs.writable: raise OSError(errno.EROFS, "read-only volume")
            if path == "/" or "Content-Length" not in self.headers: raise ValueError("PUT requires file and Content-Length")
            length = int(self.headers["Content-Length"])
            if length < 0 or self.headers.get("Transfer-Encoding"): raise ValueError("unsupported body encoding")
            temporary = path + ".tusb-upload-" + secrets.token_hex(4)
            try:
                fs.write(temporary, b"", truncate=True)
                offset = 0
                while length:
                    data = self.rfile.read(min(length, 65536))
                    if not data: raise ValueError("short upload")
                    fs.write(temporary, data, offset);offset += len(data);length -= len(data)
                self.replace(temporary, path)
            finally:
                try: fs.unlink(temporary)
                except OSError: pass
        elif self.command == "MKCOL":
            if int(self.headers.get("Content-Length", "0")): self.reply(415); return
            fs.mkdir(path);self.reply(201)
        elif self.command == "DELETE":
            if path == "/": raise ValueError("cannot remove root")
            fs.remove_tree(path);self.reply(204)
        elif self.command == "MOVE":
            if path == "/": raise ValueError("cannot move root")
            destination = self.destination()
            if destination == path: self.reply(204); return
            if destination.startswith(path + "/"): raise ValueError("cannot move into itself")
            self.replace(path, destination)
        elif self.command == "COPY":
            destination = self.destination()
            if destination == path or destination.startswith(path.rstrip("/") + "/"):
                raise ValueError("cannot copy into itself")
            temporary = destination + ".tusb-copy-" + secrets.token_hex(4)
            def copy(source, target, depth=0):
                if depth > 64: raise ValueError("directory nesting too deep")
                entry = fs.stat(source)
                if entry["directory"]:
                    fs.mkdir(target)
                    for child in fs.list(source): copy(source.rstrip("/") + "/" + child["name"], target + "/" + child["name"], depth + 1)
                else:
                    fs.write(target, b"", truncate=True)
                    for offset in range(0, entry["size"], 65536): fs.write(target, fs.read(source, 65536, offset), offset)
            try:
                copy(path, temporary);self.replace(temporary, destination)
            finally:
                try: fs.remove_tree(temporary)
                except OSError: pass

    do_OPTIONS = do_GET = do_HEAD = do_PROPFIND = do_PUT = do_MKCOL = do_DELETE = do_MOVE = do_COPY = execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    origin = parser.add_mutually_exclusive_group()
    origin.add_argument("--device")
    origin.add_argument("--image")
    parser.add_argument("--read-write", action="store_true")
    parser.add_argument("--lun", type=int, default=0)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("ls", "stat", "cat", "mkdir", "rm"):
        sub.add_parser(name).add_argument("path")
    rename = sub.add_parser("mv");rename.add_argument("source");rename.add_argument("destination")
    put = sub.add_parser("put");put.add_argument("local");put.add_argument("destination")
    create = sub.add_parser("create-image");create.add_argument("path");create.add_argument("--mib", type=int, default=64);create.add_argument("--exfat", action="store_true")
    serve = sub.add_parser("serve");serve.add_argument("--port", type=int, default=18080);serve.add_argument("--token-file", type=Path)
    serve.add_argument("--ready-file", type=Path, help="publish endpoint and volume metadata, without credentials")
    sub.add_parser("volume-info")
    args = parser.parse_args()
    if args.command != "create-image" and not (args.device or args.image): parser.error("--device or --image is required")
    fs = Filesystem(args.device, args.image, args.read_write, args.lun)
    try:
        if args.command == "create-image": fs.check(fs.lib.tusb_fs_create_image(os.fsencode(args.path), args.mib * 1024 * 1024, args.exfat))
        elif args.command == "ls": print(json.dumps(fs.list(args.path), ensure_ascii=False))
        elif args.command == "stat": print(json.dumps(fs.stat(args.path), ensure_ascii=False))
        elif args.command == "volume-info": print(json.dumps(fs.volume_info(), ensure_ascii=False))
        elif args.command == "cat":
            offset = 0
            while True:
                data = fs.read(args.path, 65536, offset)
                if not data: break
                sys.stdout.buffer.write(data);offset += len(data)
        elif args.command == "mkdir": fs.mkdir(args.path)
        elif args.command == "rm": fs.remove_tree(args.path)
        elif args.command == "mv": fs.rename(args.source, args.destination)
        elif args.command == "put":
            fs.write(args.destination, b"", truncate=True)
            with open(args.local, "rb") as source:
                offset = 0
                while data := source.read(65536): fs.write(args.destination, data, offset);offset += len(data)
        elif args.command == "serve":
            token = args.token_file
            if token:
                if token.stat().st_mode & 0o077: parser.error("token file must have mode 0600")
                password = token.read_text().strip()
            else:
                password = secrets.token_urlsafe(24)
            if not password: parser.error("empty password")
            server = ThreadingHTTPServer(("127.0.0.1", args.port), WebDAV)
            server.filesystem, server.password = fs, password
            if args.ready_file:
                info = dict(fs.volume_info(), port=server.server_port)
                temporary = args.ready_file.with_suffix(".tmp")
                temporary.write_text(json.dumps(info, ensure_ascii=False))
                temporary.chmod(0o600)
                temporary.replace(args.ready_file)
            else:
                print(f"WebDAV http://127.0.0.1:{server.server_port}/ user=tusb password={password}", flush=True)
            stopping = False
            def stop(_signum, _frame):
                nonlocal stopping
                stopping = True
            signal.signal(signal.SIGTERM, stop)
            signal.signal(signal.SIGINT, stop)
            server.timeout = 0.2
            server.daemon_threads = False
            try:
                while not stopping: server.handle_request()
            finally: server.server_close()
    finally:
        fs.close()


if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt: pass
    except (OSError, ValueError) as error: sys.exit(str(error))
