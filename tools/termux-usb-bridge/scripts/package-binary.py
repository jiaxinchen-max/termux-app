#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Legacy local CMake packager. Release builds use packaging/build.sh.in and termux-packages."""
import argparse
import gzip
import io
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import tempfile

PREFIX = "/data/data/com.termux/files/usr"
root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("build", type=Path)
parser.add_argument("--architecture", choices=["aarch64", "arm", "i686", "x86_64"], default="aarch64")
parser.add_argument("--libusb", type=Path, help="optionally bundle the patched Android libusb-1.0.so")
parser.add_argument("--libusb-source", type=Path, help="matching upstream source tree, required for COPYING")
args = parser.parse_args()
build = args.build.resolve()
header = (build / "tusb-probe").read_bytes()[:20]
machine = {"aarch64": 183, "arm": 40, "i686": 3, "x86_64": 62}[args.architecture]
if header[:4] != b"\x7fELF" or header[5] != 1 or struct.unpack_from("<H", header, 18)[0] != machine:
    parser.error("tusb-probe is not a matching little-endian ELF executable")
if args.libusb:
    if not args.libusb_source or not (args.libusb_source / "COPYING").is_file():
        parser.error("--libusb requires --libusb-source containing upstream COPYING")
    header = args.libusb.read_bytes()[:20]
    if header[:4] != b"\x7fELF" or header[5] != 1 or struct.unpack_from("<H", header, 18)[0] != machine:
        parser.error("libusb is not a matching little-endian ELF shared library")
cache = (build / "CMakeCache.txt").read_text()
if f"CMAKE_INSTALL_PREFIX:PATH={PREFIX}\n" not in cache:
    parser.error(f"configure the Android build with -DCMAKE_INSTALL_PREFIX={PREFIX}")


def tar_gz(directory):
    raw = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as archive:
            for path in sorted(directory.rglob("*")):
                info = archive.gettarinfo(str(path), arcname="./" + str(path.relative_to(directory)))
                info.uid = info.gid = info.mtime = 0
                info.uname = info.gname = ""
                if info.isfile():
                    with path.open("rb") as content:
                        archive.addfile(info, content)
                else:
                    archive.addfile(info)
    return raw.getvalue()


with tempfile.TemporaryDirectory(prefix="tusb-deb-") as directory:
    stage = Path(directory) / "data"
    stage.mkdir()
    import os
    subprocess.run(["cmake", "--install", str(build)], env=dict(os.environ, DESTDIR=str(stage)), check=True)
    share = stage / PREFIX.lstrip("/") / "share"
    documentation = share / "doc/termux-usb-bridge"
    documentation.mkdir(parents=True)
    for name in ("README.md", "LICENSE"):
        shutil.copyfile(root / name, documentation / name)
    sdk = share / "termux-usb-bridge/sdk"
    sdk.mkdir()
    for name in ("include", "src", "libusb", "scripts", "third_party"):
        shutil.copytree(root / name, sdk / name, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    for name in ("CMakeLists.txt", "termux-usb-bridge.pc.in", "broker.py", "filesystem.py"):
        shutil.copyfile(root / name, sdk / name)
    if args.libusb:
        library = stage / PREFIX.lstrip("/") / "opt/termux-usb-bridge/lib"
        library.mkdir(parents=True)
        shutil.copyfile(args.libusb, library / "libusb-1.0.so")
        shutil.copyfile(args.libusb_source / "COPYING", documentation / "COPYING.libusb")
    size = sum(p.stat().st_size for p in stage.rglob("*") if p.is_file()) // 1024
    control = Path(directory) / "control"
    control.mkdir()
    dependencies="python, termux-api"
    if "TUSB_ADAPTERS:BOOL=ON" in cache:
        dependencies+=", libusb, libjpeg-turbo, pipewire, sdl2, sdl3"
    elif "TUSB_FILESYSTEM:BOOL=ON" in cache:
        dependencies+=", libusb"
    (control / "control").write_text(f"""Package: termux-usb-bridge
Version: 0.2.0-1
Architecture: {args.architecture}
Maintainer: termux-usb-bridge contributors
Installed-Size: {size}
Depends: {dependencies}
Description: Android-authorized USB FD broker and native client for Termux and PRoot
""")
    output = root / f"dist/termux-usb-bridge_0.2.0-1_{args.architecture}.deb"
    output.parent.mkdir(exist_ok=True)
    with output.open("wb") as package:
        package.write(b"!<arch>\n")
        for name, data in (("debian-binary", b"2.0\n"), ("control.tar.gz", tar_gz(control)),
                           ("data.tar.gz", tar_gz(stage))):
            fields = f"{name + '/':<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n"
            package.write(fields.encode("ascii"))
            package.write(data)
            if len(data) % 2:
                package.write(b"\n")
    print(output)
