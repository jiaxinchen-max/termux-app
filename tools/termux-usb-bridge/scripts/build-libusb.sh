#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -euo pipefail
if [ "$#" -lt 2 ]; then
    echo "Usage: bash build-libusb.sh LIBUSB_SOURCE PRIVATE_PREFIX [configure options...]" >&2
    exit 2
fi
source_dir=$(cd "$1" && pwd)
install_prefix=$2
shift 2
case "$install_prefix" in /*) ;; *) echo "PRIVATE_PREFIX must be absolute" >&2; exit 2 ;; esac
bridge_root=$(cd "$(dirname "$0")/.." && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/tusb-build.XXXXXXXX")
trap 'rm -rf "$work_dir"' EXIT
cp -R "$source_dir" "$work_dir/source"
python3 "$bridge_root/scripts/apply-libusb.py" "$work_dir/source"
mkdir "$work_dir/build"
cd "$work_dir/build"
"$work_dir/source/configure" --disable-udev --disable-static --prefix="$install_prefix" "$@"
make -j "${JOBS:-2}"
make install
