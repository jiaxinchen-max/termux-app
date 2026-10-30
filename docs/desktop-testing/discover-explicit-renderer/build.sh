#!/bin/bash
set -euo pipefail
base=/home/builder/desktop-fixes-20261030/discover
"$base/run-arm64" /bin/sh -c '
 set -e
 cd /root/build/plasma-discover-6.7.4
 dpkg-checkbuilddeps
 dpkg-source --before-build .
 # dh binary with -nc can retain debhelper-build-stamp; rebuild explicitly.
 cmake --build obj-aarch64-linux-gnu -j12
 DEB_BUILD_OPTIONS=parallel=12 CMAKE_BUILD_PARALLEL_LEVEL=12 dpkg-buildpackage -nc -b -uc -us -j12
'
