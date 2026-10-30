#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Produce a deterministic local-source termux-packages recipe and archive."""
import gzip
import hashlib
import re
from pathlib import Path
import tarfile

root = Path(__file__).resolve().parent.parent
destination = root / "dist/termux-usb-bridge"
destination.mkdir(parents=True, exist_ok=True)
version = re.search(r'^TERMUX_PKG_VERSION=(\S+)', (root / 'packaging/build.sh.in').read_text(), re.M)[1]
archive = destination / f"termux-usb-bridge-{version}.tar.gz"
with archive.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
    with tarfile.open(fileobj=compressed, mode="w") as output:
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root)
            if any(part.startswith("build") or part in ("dist", "__pycache__", ".git") for part in relative.parts[:-1]):
                continue
            if not path.is_file() or path.suffix == ".pyc":
                continue
            info = output.gettarinfo(str(path), arcname=str(Path(f"termux-usb-bridge-{version}") / relative))
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ""
            info.mode = 0o644
            with path.open("rb") as source:
                output.addfile(info, source)
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
recipe = (root / "packaging/build.sh.in").read_text().replace("@SOURCE_SHA256@", digest)
(destination / "build.sh").write_text(recipe)
print(destination)
print(f"SHA256 {digest}")
