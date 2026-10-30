#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""USB hotplug coordinator for GIO/GVfs desktops. No kernel mounts or fake block devices."""
import argparse
import configparser
import errno
import fcntl
import hashlib
import json
import logging
import os
from pathlib import Path
import secrets
import signal
import socket
import socketserver
import stat
import subprocess
import sys
import threading
import time

import broker

LOG = logging.getLogger("tusb-desktop")


def default_runtime():
    return Path(os.environ.get("TERMUX_USB_DESKTOP_DIR", str(Path(os.environ.get("TMPDIR", "/tmp")) / "termux-usb-bridge/desktop")))


def identifier(path):
    return hashlib.sha256(path.encode()).hexdigest()[:16]


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != broker.kernel_uid() or info.st_mode & 0o077:
        raise PermissionError("desktop runtime must be a private 0700 directory")


def control(runtime, command, key=""):
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(120)
        connection.connect(str(runtime / "control.sock"))
        if broker.peer_uid(connection) != broker.kernel_uid():
            raise PermissionError("unexpected desktop daemon UID")
        connection.sendall((command + " " + key + "\n").encode())
        with connection.makefile("rb") as source:
            response = source.readline(65537).decode().strip()
        if not response.startswith("OK"):
            raise OSError(response[4:] if response.startswith("ERR ") else "invalid desktop response")
        return response[3:]


class Manager:
    def __init__(self, args):
        self.args = args
        self.runtime = args.runtime.absolute()
        self.records = {}
        self.lock = threading.RLock()
        self.stopping = threading.Event()
        self.broker_process = None
        self.primary_gamepad = None
        self.environment = dict(os.environ, TERMUX_USB_BRIDGE_SOCKET=args.broker_socket)

    def launch(self, command, name):
        # Logs contain diagnostics only; WebDAV tokens never appear in arguments or output.
        with (self.runtime / (name + ".log")).open("ab") as log:
            return subprocess.Popen(command, env=self.environment, stdin=subprocess.DEVNULL, stdout=log, stderr=log)

    def start_broker(self):
        if self.args.test_manifest:
            LOG.warning("test manifest: filesystem images and optional input playback; no USB ioctls")
            return
        if Path(self.args.broker_socket).exists():
            broker.request(self.args.broker_socket, 1)
            return
        self.broker_process = self.launch(["termux-usb-bridge", "--socket", self.args.broker_socket, "serve"], "broker")
        deadline = time.monotonic() + 5
        while not Path(self.args.broker_socket).exists():
            if self.broker_process.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("broker did not start; see broker.log")
            time.sleep(0.05)

    def sources(self):
        if self.args.test_manifest:
            values = json.loads(self.args.test_manifest.read_text())
            if not isinstance(values, dict): raise ValueError("manifest must be an object")
            result = {}
            for path, info in values.items():
                broker.validate_device(path)
                if not isinstance(info, dict) or not isinstance(info.get("image"), str):
                    raise ValueError("test entries require an image path")
                image = Path(info["image"])
                if not image.is_absolute(): image = self.args.test_manifest.parent / image
                result[path] = dict(info, image=str(image.resolve()))
            return result
        body, _ = broker.request(self.args.broker_socket, 1)
        return {broker.validate_device(path): {} for path in body.decode().splitlines()}

    def publish(self):
        configuration = configparser.ConfigParser(interpolation=None)
        configuration["desktop"] = {"pid": str(os.getpid()), "version": "1"}
        for key, record in self.records.items():
            if record["status"] == "ejected": continue
            caps = record.get("capabilities")
            if caps is not None and not caps.get("storage"): continue
            info = record.get("info", {})
            name = info.get("label") or record["source"].get("name") or (
                f"USB {info.get('type', 'storage')} ({record['path'].rsplit('/', 2)[-2]}/{record['path'].rsplit('/', 1)[-1]})"
                if caps else "USB device (authorization required)")
            # GKeyFile escaping differs from ConfigParser; labels must not inject keys or groups.
            name = name.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
            configuration["volume:" + key] = dict(
                id=key, name=name, device=record["path"], status=record["status"],
                uri=record.get("uri", ""), mounted="true" if record["status"] == "mounted" else "false",
                size=str(info.get("size", 0)), free=str(info.get("free", 0)),
                writable="true" if self.args.read_write else "false", type=info.get("type", ""))
        temporary = self.runtime / "volumes.tmp"
        with temporary.open("w") as output: configuration.write(output, space_around_delimiters=False)
        temporary.chmod(0o600)
        temporary.replace(self.runtime / "volumes.ini")

    def classify(self, record, authorize=False):
        if self.args.test_manifest:
            record["capabilities"] = {"storage": True}
        else:
            if authorize:
                subprocess.run(["termux-usb", "-r", record["path"]], env=self.environment,
                               check=True, timeout=90, stdout=subprocess.DEVNULL)
            result = subprocess.run(["tusb-inspect", record["path"]], env=self.environment,
                                    capture_output=True, text=True, timeout=30)
            if result.returncode:
                record["status"] = "permission-required"
                record["error"] = result.stderr.strip()[:512]
                return False
            record["capabilities"] = json.loads(result.stdout)
        record["status"] = "available"
        record.pop("error", None)
        self.start_inputs(record)
        return True

    def start_inputs(self, record):
        if self.args.test_manifest: return
        key, caps = record["id"], record["capabilities"]
        children = record.setdefault("inputs", {})
        if caps.get("camera") and not self.args.no_camera and "camera" not in children:
            children["camera"] = self.launch(["tusb-camera", "--device", record["path"], "--name", "tusb.camera." + key], key + "-camera")
        if caps.get("hid") and not self.args.no_gamepad and "gamepad" not in children:
            address = self.runtime / (key + "-gamepad.sock")
            if self.primary_gamepad is None:
                address = Path(os.environ.get("TMPDIR", "/tmp")) / "termux-usb-bridge/gamepad.sock"
                self.primary_gamepad = key
            record["gamepad_socket"] = str(address)
            children["gamepad"] = self.launch(["tusb-gamepadd", "--device", record["path"], "--socket", str(address)], key + "-gamepad")

    def mount(self, record):
        if record["status"] == "mounted": return
        if record["status"] == "ejected": raise OSError(errno.ENODEV, "device was ejected; reinsert it")
        if record.get("capabilities") is None:
            if not self.classify(record, authorize=True): raise PermissionError("USB permission was not granted")
        if not record["capabilities"].get("storage"):
            raise OSError(errno.ENOTSUP, "device has no supported USB BOT storage interface")
        key = record["id"]
        token, ready = self.runtime / (key + ".token"), self.runtime / (key + ".ready")
        token.write_text(secrets.token_urlsafe(24));token.chmod(0o600)
        ready.unlink(missing_ok=True)
        command = ["tusb-files", "--image", record["source"]["image"]] if self.args.test_manifest else ["tusb-files", "--device", record["path"]]
        if self.args.read_write: command += ["--read-write"]
        command += ["serve", "--port", "0", "--token-file", str(token), "--ready-file", str(ready)]
        record["status"] = "mounting";self.publish()
        process = self.launch(command, key + "-files")
        record["files"] = process
        try:
            deadline = time.monotonic() + 45
            while not ready.exists():
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("filesystem service failed; see " + key + "-files.log")
                time.sleep(0.03)
            info = json.loads(ready.read_text())
            record["info"] = info
            record["uri"] = f"dav://tusb@127.0.0.1:{info['port']}/"
            subprocess.run(["tusb-gio-mount", "mount", record["uri"], str(token)], env=self.environment, check=True, timeout=40)
            record["status"] = "mounted";record.pop("error", None)
        except BaseException:
            if record.get("uri"):
                subprocess.run(["tusb-gio-mount", "unmount", record["uri"]], env=self.environment,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=40)
            self.stop_process(process);record.pop("files", None)
            record["status"] = "error";record["uri"] = ""
            token.unlink(missing_ok=True);ready.unlink(missing_ok=True)
            raise
        finally:
            self.publish()

    @staticmethod
    def stop_process(process):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill();process.wait()
                raise RuntimeError("service required SIGKILL; clean flush is not guaranteed")
        return process.returncode

    def unmount(self, record, disconnected=False):
        if record.get("uri"):
            result = subprocess.run(["tusb-gio-mount", "unmount", record["uri"]], env=self.environment, capture_output=True, text=True, timeout=40)
            if result.returncode and not disconnected:
                raise OSError(errno.EBUSY, result.stderr.strip() or "GVfs unmount failed")
        process = record.pop("files", None)
        try:
            result = self.stop_process(process) if process else 0
        finally:
            record["uri"] = "";record["status"] = "ejected" if disconnected else "available"
            for suffix in (".token", ".ready"): (self.runtime / (record["id"] + suffix)).unlink(missing_ok=True)
            self.publish()
        if result and not disconnected: raise OSError(errno.EIO, "filesystem did not close cleanly; inspect its log")

    def remove(self, record):
        self.unmount(record, disconnected=True)
        for child in record.get("inputs", {}).values(): self.stop_process(child)
        if self.primary_gamepad == record["id"]: self.primary_gamepad = None

    def poll(self):
        sources = self.sources()  # A transient enumeration failure must never be interpreted as unplug.
        with self.lock:
            for key, record in list(self.records.items()):
                if record["path"] not in sources or record["source"] != sources[record["path"]]:
                    self.remove(record);del self.records[key]
            for path, source in sources.items():
                if self.stopping.is_set(): break
                key = identifier(path)
                if key in self.records: continue
                record = dict(id=key, path=path, source=source, status="permission-required", capabilities=None)
                self.records[key] = record
                try:
                    classified = self.classify(record)
                    if not classified and self.args.request_permission: classified = self.classify(record, authorize=True)
                    if classified and record["capabilities"].get("storage") and not self.args.no_automount:
                        self.mount(record)
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                    record["error"] = str(error);LOG.warning("%s: %s", path, error)
            for record in self.records.values():
                if record.get("files") and record["files"].poll() is not None:
                    self.unmount(record, disconnected=True);record["status"] = "error"
                    record["error"] = "filesystem service exited"
                for name, child in record.get("inputs", {}).items():
                    if child.poll() is not None:
                        record[name + "_error"] = "service exited; inspect device log"
                        if name == "gamepad" and self.primary_gamepad == record["id"]: self.primary_gamepad = None
            # A non-gamepad HID or a removed primary must not occupy the SDL default forever.
            if self.primary_gamepad is None:
                for record in self.records.values():
                    child = record.get("inputs", {}).get("gamepad")
                    if record["status"] != "ejected" and child and child.poll() is None:
                        self.stop_process(child)
                        del record["inputs"]["gamepad"]
                        self.start_inputs(record)
                        break
            self.publish()

    def action(self, command, key):
        with self.lock:
            if command == "status":
                return json.dumps([{k: v for k, v in record.items() if k not in ("files", "inputs")} for record in self.records.values()], ensure_ascii=False)
            if command == "detached":
                key, _, expected_uri = key.partition(" ")
                record = self.records.get(key)
                if not record or record["status"] != "mounted" or record.get("uri") != expected_uri:
                    return ""
                self.unmount(record)
                return ""
            record = self.records.get(key) or self.records.get(identifier(key))
            if record is None: raise FileNotFoundError("USB device is no longer present")
            try:
                if command == "mount": self.mount(record)
                elif command == "authorize":
                    if not self.classify(record, authorize=True): raise PermissionError("USB permission was not granted")
                    if record["capabilities"].get("storage") and not self.args.no_automount: self.mount(record)
                elif command in ("unmount", "eject"):
                    if record["status"] == "ejected": return ""
                    self.unmount(record)
                    if command == "eject":
                        for child in record.get("inputs", {}).values(): self.stop_process(child)
                        record["status"] = "ejected"
                        if self.primary_gamepad == record["id"]: self.primary_gamepad = None
                else: raise ValueError("unsupported desktop operation")
                return ""
            finally: self.publish()

    def cleanup(self):
        with self.lock:
            for record in list(self.records.values()):
                try: self.remove(record)
                except Exception: LOG.exception("device cleanup failed")
            self.records.clear();self.publish()
        if self.broker_process: self.stop_process(self.broker_process)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        self.connection.settimeout(120)
        if broker.peer_uid(self.connection) != broker.kernel_uid(): return
        try:
            request = self.rfile.readline(1025)
            if len(request) > 1024 or not request.endswith(b"\n"): raise ValueError("invalid desktop request")
            command, _, key = request.decode().strip().partition(" ")
            response = self.server.manager.action(command, key)
            self.wfile.write(("OK " + response + "\n").encode())
        except Exception as error:
            self.wfile.write(("ERR " + str(error).replace("\n", " ")[:1024] + "\n").encode())


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    request_queue_size = 16


def serve(args):
    private_directory(args.runtime)
    with (args.runtime / "daemon.lock").open("w") as lockfile:
        fcntl.flock(lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
        address = args.runtime / "control.sock"
        if address.exists(): address.unlink()  # Exclusive process lock proves no live daemon owns this socket.
        manager = Manager(args)
        with Server(str(address), Handler) as server:
            os.chmod(address, 0o600);server.manager = manager;server.timeout = 0.2
            def stop(_signum, _frame): manager.stopping.set()
            signal.signal(signal.SIGTERM, stop);signal.signal(signal.SIGINT, stop)
            def polling():
                while not manager.stopping.is_set():
                    try: manager.poll()
                    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error: LOG.warning("enumeration: %s", error)
                    manager.stopping.wait(args.interval)
            thread = None
            try:
                manager.start_broker();manager.publish()
                thread = threading.Thread(target=polling, daemon=True);thread.start()
                while not manager.stopping.is_set(): server.handle_request()
            finally:
                manager.stopping.set()
                if thread: thread.join(timeout=120)
                manager.cleanup()
                address.unlink(missing_ok=True)
                (args.runtime / "volumes.ini").unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=default_runtime())
    sub = parser.add_subparsers(dest="command", required=True)
    start = sub.add_parser("serve")
    start.add_argument("--broker-socket", default=os.environ.get("TERMUX_USB_BRIDGE_SOCKET", str(Path(os.environ.get("TMPDIR", "/tmp")) / "termux-usb-bridge/broker.sock")))
    start.add_argument("--request-permission", action="store_true", help="request Android permission once for each new USB address")
    start.add_argument("--no-automount", action="store_true")
    start.add_argument("--no-camera", action="store_true")
    start.add_argument("--no-gamepad", action="store_true")
    start.add_argument("--read-write", action="store_true", help="only after Android has unmounted the volume")
    start.add_argument("--interval", type=float, default=1.0)
    start.add_argument("--test-manifest", type=Path, help="isolated image hotplug tests; not USB emulation")
    sub.add_parser("status")
    for operation in ("mount", "unmount", "eject", "authorize"):
        sub.add_parser(operation).add_argument("device")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(name)s: %(message)s")
    if args.command == "serve":
        if args.interval < 0.1: parser.error("interval must be at least 0.1 seconds")
        serve(args)
    else:
        result = control(args.runtime, args.command, getattr(args, "device", ""))
        if result: print(result)


if __name__ == "__main__":
    try: main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error: sys.exit(str(error))
