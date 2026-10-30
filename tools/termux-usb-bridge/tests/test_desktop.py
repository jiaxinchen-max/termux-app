# SPDX-License-Identifier: MIT
"""Run in a private dbus-run-session against installed binaries and real GVfs DAV."""
import configparser
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

probe = str(Path(sys.argv[1]).resolve())
root = Path(__file__).resolve().parent.parent


def run(*command, env=None):
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=115)
    if result.returncode:
        raise RuntimeError(f"{command}: {result.returncode}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def wait_for(predicate, description, timeout=50):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value: return value
        time.sleep(0.05)
    raise RuntimeError("Timed out: " + description)


with tempfile.TemporaryDirectory(prefix="tusb-desktop-test-", dir=os.environ.get("TMPDIR", "/tmp")) as temporary:
    directory = Path(temporary)
    runtime = directory / "d";runtime.mkdir(mode=0o700)
    manifest = directory / "devices.json";manifest.write_text("{}")
    environment = dict(os.environ, TERMUX_USB_DESKTOP_DIR=str(runtime))
    image = directory / "disk.img"
    run("tusb-files", "create-image", str(image), "--mib", "64", "--exfat")
    path = "/dev/bus/usb/001/002"
    key = hashlib.sha256(path.encode()).hexdigest()[:16];uuid = "tusb-" + key
    values = {path: {"image": str(image), "name": "USB 测试盘"}}
    def change(values):
        next_file = directory / "next.json";next_file.write_text(json.dumps(values));next_file.replace(manifest)
    def status():
        file = runtime / "volumes.ini"
        if not file.exists(): return None
        configuration = configparser.ConfigParser(interpolation=None);configuration.read(file)
        return configuration["volume:" + key] if configuration.has_section("volume:" + key) else None
    def mounted():
        value = status()
        return value if value and value["mounted"] == "true" else None
    watcher_log = directory / "watch.log"
    with (directory / "daemon.log").open("w+") as log, watcher_log.open("w+") as watch_log:
        daemon = subprocess.Popen(["tusb-desktop", "--runtime", str(runtime), "serve", "--test-manifest", str(manifest),
                                   "--read-write", "--interval", "0.1"], env=environment, stdout=log, stderr=log)
        watcher = subprocess.Popen([probe, "watch"], env=environment, stdout=watch_log, stderr=watch_log)
        try:
            wait_for(lambda: "WATCH_READY" in watcher_log.read_text(), "monitor starts before device insertion")
            change(values)
            current = wait_for(mounted, "automatic GVfs mount")
            wait_for(lambda: "VOLUME_ADDED" in watcher_log.read_text() and "MOUNT_ADDED" in watcher_log.read_text(), "GIO hotplug notifications")
            listing = run(probe, "list", env=environment)
            assert listing.count(uuid) == 1, listing
            info = run(probe, "info", uuid, env=environment)
            assert "mounted=1" in info and "size=" in info, info
            print("PASS insert -> GVolume/GMount -> GVfs capacity:", info.strip(), flush=True)
            uri = current["uri"]
            visibility = run(probe, "visible", env=environment)
            related = [line for line in visibility.splitlines() if f"127.0.0.1:{current['uri'].split(':')[-1].rstrip('/')}" in line]
            assert sum("shadow=0" in line for line in related) == 1, visibility
            assert any("shadow=1" in line and "TusbMount" not in line for line in related), visibility
            print("PASS canonical GVfs mount shadowed: one visible removable-volume row", flush=True)
            payload = bytes(range(256)) * 512 + b"Android GVfs UTF-8"
            local = directory / "payload.bin";local.write_bytes(payload)
            run("gio", "mkdir", uri + "%E7%9B%AE%E5%BD%95", env=environment)
            remote = uri + "%E7%9B%AE%E5%BD%95/payload.bin"
            run("gio", "copy", str(local), remote, env=environment)
            back = directory / "readback.bin"
            run("gio", "copy", remote, str(back), env=environment)
            assert back.read_bytes() == payload
            run("gio", "move", remote, uri + "renamed.bin", env=environment)
            run("gio", "remove", uri + "%E7%9B%AE%E5%BD%95", env=environment)
            print("PASS genuine GVfs Unicode directory + binary copy + rename + delete", flush=True)
            run(probe, "unmount", uuid, env=environment)
            assert status()["mounted"] == "false"
            assert "mounted=0" in run(probe, "info", uuid, env=environment)
            assert not (runtime / (key + ".token")).exists()
            print("PASS GMount unmount -> GVfs detach -> filesystem flush/release", flush=True)
            run(probe, "mount", uuid, env=environment)
            current = wait_for(mounted, "manual remount")
            back.unlink()
            run("gio", "copy", current["uri"] + "renamed.bin", str(back), env=environment)
            assert back.read_bytes() == payload
            run(probe, "eject", uuid, env=environment)
            wait_for(lambda: uuid not in run(probe, "list", env=environment), "ejected volume disappears")
            time.sleep(0.4)
            assert status() is None, "ejected device auto-remounted without reinsert"
            print("PASS GVolume eject + suppression until reinsert + durable readback", flush=True)
            change({})
            time.sleep(0.4)
            change(values)
            wait_for(mounted, "reinsert")
            change({})
            wait_for(lambda: status() is None, "unplug removes volume")
            wait_for(lambda: "VOLUME_REMOVED" in watcher_log.read_text() and "MOUNT_REMOVED" in watcher_log.read_text(), "GIO unplug notifications")
            print("PASS unplug/reinsert + GIO volume/mount removed signals", flush=True)
            daemon.terminate();daemon.wait(timeout=100)
            # Default mode must preserve the underlying filesystem even through real GVfs.
            daemon = subprocess.Popen(["tusb-desktop", "--runtime", str(runtime), "serve", "--test-manifest", str(manifest),
                                       "--interval", "0.1"], env=environment, stdout=log, stderr=log)
            wait_for(lambda: (runtime / "control.sock").exists(), "read-only daemon restart")
            change(values)
            current = wait_for(mounted, "default read-only mount")
            denied = subprocess.run(["gio", "copy", str(local), current["uri"] + "denied.bin"], env=environment,
                                    capture_output=True, text=True, timeout=30)
            assert denied.returncode != 0, "read-only GVfs write succeeded"
            print("PASS default read-only mode rejects genuine GVfs writes", flush=True)
            run(probe, "visible", env=environment)
            run("gio", "mount", "-u", current["uri"], env=environment)
            wait_for(lambda: status() is not None and status()["mounted"] == "false", "external GVfs unmount releases service")
            assert not (runtime / (key + ".token")).exists()
            print("PASS external GVfs unmount reconciles service and token cleanup", flush=True)
            change({});wait_for(lambda: status() is None, "remove read-only device")
            change({path: {"image": str(directory / "missing.img")}})
            wait_for(lambda: status() is not None and status()["status"] == "error", "failed mount reports error")
            assert not (runtime / (key + ".token")).exists()
            assert not (runtime / (key + ".ready")).exists()
            print("PASS failed filesystem mount leaves no credentials or ready endpoint", flush=True)
        except BaseException:
            print("DAEMON LOG:\n" + (directory / "daemon.log").read_text(), file=sys.stderr)
            print("WATCHER LOG:\n" + watcher_log.read_text(), file=sys.stderr)
            for item in runtime.glob("*.log"):
                print(item.name + ":\n" + item.read_text(), file=sys.stderr)
            raise
        finally:
            for process in (daemon, watcher):
                if process.poll() is None: process.terminate()
                process.wait(timeout=100)
    print("TUSB_DESKTOP_GVFS_PASS", flush=True)
