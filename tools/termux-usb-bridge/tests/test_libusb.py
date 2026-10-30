# SPDX-License-Identifier: MIT
"""Smoke-test patched libusb against an empty broker, including under PRoot."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

root = Path(__file__).resolve().parent.parent
executable = str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory(prefix="tusb-lib-", dir=os.environ.get("TMPDIR", "/tmp")) as directory:
    manifest = Path(directory) / "devices.json"
    manifest.write_text("{}")
    address = str(Path(directory) / "s")
    environment = dict(os.environ, TERMUX_USB_BRIDGE_SOCKET=address)
    process = subprocess.Popen([sys.executable, str(root / "broker.py"), "--socket", address,
                                "serve", "--mock-json", str(manifest)])
    try:
        deadline = time.monotonic() + 5
        while not Path(address).exists():
            if process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("broker failed to start")
            time.sleep(0.02)
        for command in ([executable],
                        *([[shutil.which("proot"), "-0", "-r", "/", executable]] if shutil.which("proot") else [])):
            result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=15)
            if result.returncode or result.stdout.count("0 devices") != 2:
                raise RuntimeError(f"{command}: {result.returncode}\n{result.stdout}\n{result.stderr}")
            print(f"PASS {'PRoot' if len(command) > 1 else 'native'}: {result.stdout.strip()}")
    finally:
        process.terminate()
        process.wait(timeout=5)
