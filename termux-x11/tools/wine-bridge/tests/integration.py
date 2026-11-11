#!/usr/bin/env python3
"""Real Windows PE/Wine integration. Run under an isolated Xvfb DISPLAY and WINEPREFIX."""
import argparse
import contextlib
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time


def wait_for(condition, label, seconds=12):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(0.04)
    raise AssertionError(f"Timed out waiting for {label}")


def windows(path):
    return "Z:" + str(path.resolve()).replace("/", "\\")


def packet(code, body=b""):
    value = bytes([code]) + body
    assert len(value) <= 64
    return value.ljust(64, b"\0")


class Session:
    def __init__(self, base, mode="window", extra=(), options=()):
        self.directory = Path(tempfile.mkdtemp(prefix="wb-"))
        self.log = self.directory / "events"
        self.stop = self.directory / "stop"
        self.stderr = (self.directory / "bridge.log").open("wb")
        self.process = subprocess.Popen([base.wine, str(base.bridge), *options,
            "/dir", windows(self.directory), windows(base.probe), windows(self.log), windows(self.stop),
            mode, *extra], stdout=self.stderr, stderr=subprocess.STDOUT)

    def text(self):
        return self.log.read_text(encoding="utf-8") if self.log.exists() else ""

    def close(self):
        self.stop.touch()
        try:
            self.process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.stderr.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wine", required=True)
    parser.add_argument("--bridge", required=True, type=Path)
    parser.add_argument("--probe", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    base = parser.parse_args()
    base.bridge = base.bridge.resolve()
    # Exercise an actual executable path containing spaces and non-ASCII text,
    # in addition to the argument quoting assertions below.
    probe_directory = Path(tempfile.mkdtemp(prefix="wb probe 中文-"))
    probe = probe_directory / "probe 游戏.exe"
    shutil.copy2(base.probe.resolve(), probe)
    base.probe = probe
    results = []
    artifacts = []
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as android:
        android.bind(("127.0.0.1", 7947))
        android.settimeout(3)

        def send(value):
            android.sendto(value, ("127.0.0.1", 7946))

        def receive(code):
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                data, peer = android.recvfrom(4096)
                assert peer[1] == 7946 and len(data) == 64, (peer, len(data))
                if data[0] == code:
                    return data
            raise AssertionError(f"No reply for {code}")

        def processes():
            send(packet(4, struct.pack("<I", 0)))
            found = {}
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                data = receive(5)
                count, index, pid, memory, affinity, wow64 = struct.unpack_from("<HHIQIB", data, 5)
                name = data[26:58].split(b"\0", 1)[0].decode("utf-8")
                found[index] = dict(pid=pid, memory=memory, affinity=affinity, wow64=wow64, name=name)
                if len(found) == count:
                    return list(found.values())
            raise AssertionError("Incomplete process reply")

        def assert_gone(session):
            pid = next(line.split("=", 1)[1] for line in session.text().splitlines() if line.startswith("pid="))
            check = subprocess.run([base.wine, str(base.probe), windows(session.directory / "gone-check"),
                windows(session.stop), "check-gone", pid], stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=10)
            assert check.returncode == 0, f"Job process {pid} still alive: {check.stdout!r}"

        session = Session(base, extra=("hello world", 'quote"inside', "", "trailing\\", "中文参数"))
        artifacts.append(str(session.directory))
        try:
            receive(1)
            wait_for(lambda: "hwnd=" in session.text(), "probe window")
            text = session.text()
            for i, arg in enumerate(("window", "hello world", 'quote"inside', "", "trailing\\", "中文参数")):
                assert f"arg{i}={arg}\n" in text, text
            assert f"cwd={windows(session.directory)}\n" in text, text
            results.append("Executable path with spaces/Unicode, CLI quotes, empty arguments and working directory")

            root = next(p for p in processes() if p["name"] == base.probe.name)
            assert root["pid"] > 0 and root["memory"] > 0 and root["affinity"] > 0, root
            results.append("Java-compatible process list, PID, memory and affinity fields")

            conflict_log = session.directory / "conflict"
            conflict = subprocess.run([base.wine, str(base.bridge), windows(base.probe),
                windows(conflict_log), windows(session.stop), "window"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
            assert conflict.returncode == 3 and b"bind 127.0.0.1:7946 failed" in conflict.stdout, conflict.stdout
            assert not conflict_log.exists() and session.process.poll() is None
            results.append("Second bridge fails before target launch and does not steal active UDP port")

            # Malformed/truncated commands and unsupported controller commands do not execute/crash.
            send(b"\x02\xff")
            send(packet(2, struct.pack("<III", 208, 100, 100)))
            send(packet(8))
            send(bytes(1000))
            assert any(p["pid"] == root["pid"] for p in processes())
            results.append("Malformed, oversized and unknown datagrams rejected; server remains usable")

            mask = root["affinity"] & -root["affinity"]
            send(packet(6, struct.pack("<IIIB", 9, root["pid"], mask, 0)))
            wait_for(lambda: any(p["pid"] == root["pid"] and p["affinity"] == mask for p in processes()), "PID affinity")
            name = base.probe.name.encode()
            send(packet(6, struct.pack("<IIIB", 9 + len(name), 0, root["affinity"], len(name)) + name))
            wait_for(lambda: any(p["pid"] == root["pid"] and p["affinity"] == root["affinity"] for p in processes()), "name affinity")
            results.append("Set affinity by PID and process name")

            send(packet(12, struct.pack("<I", len(name)) + name + struct.pack("<Q", 0)))
            wait_for(lambda: "size=0\n" in session.text(), "restore window by name")
            hwnd = int(next(v.split("=", 1)[1] for v in text.splitlines() if v.startswith("hwnd=")))
            send(packet(12, struct.pack("<I", 0) + struct.pack("<Q", hwnd)))
            # A signed short can carry the unsigned absolute coordinate bit pattern.
            x, y = 200 * 65535 // 1024, 200 * 65535 // 768
            send(packet(7, struct.pack("<IIHHhB", 10, 0x8001, x, y, 0, 1)))
            cursor = receive(13)
            cx, cy = struct.unpack_from("<hh", cursor, 1)
            assert abs(cx - 200) <= 2 and abs(cy - 200) <= 2, (cx, cy)
            for flags in (2, 4):
                send(packet(7, struct.pack("<IIhhhB", 10, flags, 0, 0, 0, 0)))
            send(packet(7, struct.pack("<IIhhhB", 10, 0x0800, 0, 0, 120, 0)))
            send(packet(11, struct.pack("<BI", 65, 0)))
            send(packet(11, struct.pack("<BI", 65, 2)))
            wait_for(lambda: all(t in session.text() for t in ("mouse-down\n", "mouse-up\n", "wheel=120\n", "key-down=65\n", "key-up=65\n")), "real Win32 input events")
            results.append("Window restore/foreground by name and HWND; mouse, wheel, key and cursor feedback")

            x, y = 800 * 65535 // 1024, 600 * 65535 // 768
            send(packet(7, struct.pack("<IIHHhB", 10, 0x8001, x, y, 0, 1)))
            cx, cy = struct.unpack_from("<hh", receive(13), 1)
            assert abs(cx - 800) <= 2 and abs(cy - 600) <= 2, (cx, cy)
            results.append("Absolute mouse coordinates preserve unsigned 16-bit range")

            # Victim needs only enough arguments to enter a message loop; share the fixture stop file.
            # Use a short relative command plus short file names, within the existing 64-byte ABI.
            shutil.copy2(base.probe, session.directory / "v.exe")
            args = b"victim-log victim-stop child"
            send(packet(2, struct.pack("<III", 8 + 5 + len(args), 5, len(args)) + b"v.exe" + args))
            wait_for(lambda: (session.directory / "victim-log").exists(), "UDP execute")
            wait_for(lambda: any(p["name"] == "v.exe" for p in processes()), "executed process in list")
            send(packet(3, struct.pack("<I", 5) + b"v.exe"))
            wait_for(lambda: all(p["name"] != "v.exe" for p in processes()), "terminate executed process")
            results.append("UDP execute and force terminate by process name")

            session.stop.touch()
            assert session.process.wait(timeout=10) == 0
            assert_gone(session)
            results.append("Natural root completion and clean bridge exit")
        finally:
            session.close()

        # Actual inherited Job ownership, including two fast-exiting intermediate launchers.
        session = Session(base, mode="launcher", options=("/affinity", f"{mask:x}"))
        artifacts.append(str(session.directory))
        unrelated = None
        try:
            receive(1)
            wait_for(lambda: "arg0=child\n" in session.text() and "hwnd=" in session.text(), "grandchild launch")
            assert [int(line.split("=", 1)[1]) for line in session.text().splitlines()
                    if line.startswith("affinity=")] == [mask, mask, mask], session.text()
            assert session.process.poll() is None
            other = session.directory / "unrelated"
            other_stop = session.directory / "unrelated-stop"
            unrelated = subprocess.Popen([base.wine, str(base.probe), windows(other), windows(other_stop), "child"],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            wait_for(other.exists, "unrelated process")
            session.stop.touch()
            assert session.process.wait(timeout=10) == 0
            assert unrelated.poll() is None
            other_stop.touch()
            assert unrelated.wait(timeout=8) == 0
            results.append("Job survives fast launcher/relay exit, tracks grandchild, ignores unrelated process; CLI affinity inherited")
        finally:
            if unrelated and unrelated.poll() is None:
                (session.directory / "unrelated-stop").touch()
                unrelated.wait(timeout=8)
            session.close()

        session = Session(base)
        artifacts.append(str(session.directory))
        try:
            receive(1)
            wait_for(lambda: "hwnd=" in session.text(), "EXIT fixture")
            send(packet(0))
            assert session.process.wait(timeout=8) == 0
            # The stop file has NOT been touched: only Job shutdown can terminate this target.
            assert not session.stop.exists()
            assert_gone(session)
            results.append("EXIT stops bridge and owned Job")
        finally:
            session.close()

        for args, expected in (([], 2), (["Z:\\missing-wine-bridge-target.exe"], 4)):
            completed = subprocess.run([base.wine, str(base.bridge), *args], stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, timeout=10)
            assert completed.returncode == expected, completed.stdout.decode(errors="replace")
        results.append("Missing/invalid targets fail without alternate program or fallback")
        # The failed-start bridge already sent INIT; drain it before another case.
        android.settimeout(0.1)
        with contextlib.suppress(socket.timeout):
            while True:
                android.recvfrom(4096)
        android.settimeout(3)
        session = Session(base, mode="exit47")
        artifacts.append(str(session.directory))
        try:
            receive(1)
            assert session.process.wait(timeout=8) == 47
            results.append("Target exit code is propagated")
        finally:
            session.close()

    base.output.write_text(json.dumps(dict(passed=results, fixture_directories=artifacts,
                                           wine=base.wine), indent=2) + "\n")
    print(f"{len(results)} real Wine integration scenarios passed")


if __name__ == "__main__":
    main()
