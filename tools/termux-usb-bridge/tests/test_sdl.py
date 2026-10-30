# SPDX-License-Identifier: MIT
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
build = Path(sys.argv[1]).resolve()
# A generic HID gamepad descriptor: 8 buttons, X/Y axes and one hat.
descriptor = bytes.fromhex("05 01 09 05 a1 01 05 09 19 01 29 08 15 00 25 01 75 01 95 08 81 02 05 01 09 30 09 31 15 81 25 7f 75 08 95 02 81 02 09 39 15 00 25 07 75 04 95 01 81 42 75 04 95 01 81 03 c0")
with tempfile.TemporaryDirectory(prefix="tusb-sdl-", dir=os.environ.get("TMPDIR", "/tmp")) as directory:
    path = Path(directory) / "hid.bin";path.write_bytes(descriptor)
    address = str(Path(directory) / "g.sock")
    process = subprocess.Popen([str(build / "tusb-gamepadd"), "--socket", address,
                                "--descriptor", str(path), "--report", "03 81 7f 02"])
    try:
        deadline = time.monotonic() + 5
        while not Path(address).exists():
            if process.poll() is not None or time.monotonic()>deadline: raise RuntimeError("gamepad daemon failed")
            time.sleep(0.02)
        for version in (2,3):
            env = dict(os.environ, TERMUX_USB_GAMEPAD_SOCKET=address,
                       SDL_VIDEODRIVER="dummy", SDL_AUDIODRIVER="dummy",
                       LD_PRELOAD=str(build / f"libtermux-usb-sdl{version}.so"))
            result = subprocess.run([str(build / f"tusb-sdl{version}-probe")], env=env, capture_output=True, text=True, timeout=10)
            if result.returncode: raise RuntimeError(f"SDL{version}: {result.stdout}\n{result.stderr}")
            print(f"PASS SDL{version}: {result.stdout.strip()}")
    finally:
        process.terminate();process.wait(timeout=5)
