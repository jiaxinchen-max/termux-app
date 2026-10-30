#!/usr/bin/env python3
"""Run the production session preflight against explicit process/socket fixtures."""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit("usage: test-start-conflicts.py /path/to/start-plasma")
source = Path(sys.argv[1]).read_text()
marker = 'PLASMA_LOG="${PLASMA_LOG:-$KWIN_ANDROID_RUNTIME_ROOT/start-plasma.log}"'
assert source.count(marker) == 1
# Stop after the real entry checks, before dependencies or compositor launch.
entry = source[:source.index(marker)] + '\nprintf "ENTRY_PASSED\\n"\n'
cases = [
    ("existing-no-socket", [True], False, 73),
    ("existing-with-socket", [True], True, 73),
    ("process-appears-at-socket-check", [False, True], True, 73),
    ("stale-socket", [False, False], True, 0),
    ("new-session", [False], False, 0),
]
failures = []
for name, process_states, has_socket, expected_status in cases:
    with tempfile.TemporaryDirectory(prefix="kwin-entry-") as temporary:
        root = Path(temporary)
        binary = root / "bin"
        binary.mkdir()
        runtime = root / "runtime"
        runtime.mkdir()
        pgrep = binary / "pgrep"
        pgrep.write_text(
            "#!" + sys.executable + "\n"
            "import pathlib,sys\n"
            "root=pathlib.Path(" + repr(str(root)) + ")\n"
            "count=root/'pgrep-count'\n"
            "index=int(count.read_text()) if count.exists() else 0\n"
            "count.write_text(str(index+1))\n"
            "states=" + repr(process_states) + "\n"
            "assert sys.argv[1:]==['-x','kwin_wayland'],sys.argv\n"
            "assert index<len(states),index\n"
            "if states[index]: print('424242');sys.exit(0)\n"
            "sys.exit(1)\n"
        )
        pgrep.chmod(0o755)
        script = root / "entry.sh"
        script.write_text(entry)
        wayland = runtime / "wayland-test"
        with socket.socket(socket.AF_UNIX) as sock:
            if has_socket:
                sock.bind(str(wayland))
            env = os.environ.copy()
            env.update(
                PATH=str(binary) + ":" + os.defpath,
                PREFIX=str(root / "prefix"),
                HOME=str(root / "home"),
                KWIN_ANDROID_RUNTIME_ROOT=str(root / "session"),
                KWIN_ANDROID_TMPDIR=str(root / "tmp"),
                KWIN_ANDROID_XDG_RUNTIME_DIR=str(runtime),
                KWIN_WAYLAND_SOCKET="wayland-test",
                KWIN_ANDROID_GL_MODE="system",
            )
            result = subprocess.run(["bash", "-x", str(script)], env=env,
                                    capture_output=True, text=True)
            try:
                assert result.returncode == expected_status, (result.returncode, expected_status)
                if expected_status:
                    assert "refusing to reuse or stop it" in result.stderr
                    assert "ENTRY_PASSED" not in result.stdout
                    assert wayland.exists() == has_socket
                    assert "\n+ kill " not in result.stderr
                else:
                    assert "ENTRY_PASSED" in result.stdout
                    assert not wayland.exists()
                assert not (runtime / "start-plasma.lock").exists()
            except AssertionError as error:
                failures.append(name)
                print("FAIL", name, error, result.stdout, result.stderr)
            else:
                print("PASS", name)
if failures:
    raise SystemExit(1)
print("PASS: 5 production entry conflict contracts")
