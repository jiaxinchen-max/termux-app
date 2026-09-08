#!/usr/bin/env sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GUEST_SCRIPT="$PROJECT_ROOT/games/src/main/assets/local-games/runtime-rootfs/provision-container.sh"
MANIFEST="$PROJECT_ROOT/games/src/main/assets/local-games/runtime-rootfs/games-runtime.properties"

sh "$PROJECT_ROOT/games/scripts/test-provision-rootfs-runtime.sh"

grep -q 'apt-get update' "$GUEST_SCRIPT"
grep -q 'apt-get install -y --no-install-recommends' "$GUEST_SCRIPT"
grep -q 'libegl1 libegl-mesa0' "$GUEST_SCRIPT"
grep -q 'install -m 0644.*games-runtime.properties' "$GUEST_SCRIPT"
grep -q 'test -x /usr/bin/wineboot' "$GUEST_SCRIPT"
grep -qx 'schemaVersion=2' "$MANIFEST"
grep -qx 'runtimeBackend=rootfs_proot' "$MANIFEST"
grep -qx 'architecture=aarch64' "$MANIFEST"
grep -qx 'baseImage=debian:trixie-20260824' "$MANIFEST"
grep -qx 'debianSnapshot=20260830T000000Z' "$MANIFEST"
grep -qx 'runtimePackages=hangover-11.9' "$MANIFEST"
grep -qx 'graphicsDrivers=rootfs-virgl-mesa,rootfs-llvmpipe' "$MANIFEST"
grep -qx 'dxWrappers=rootfs-wined3d' "$MANIFEST"
grep -qx 'audioDrivers=pulseaudio' "$MANIFEST"

printf '%s\n' 'RootFS on-device container provision contract test passed.'
