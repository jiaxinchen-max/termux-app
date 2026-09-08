#!/usr/bin/env sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
SCRIPT="$PROJECT_ROOT/games/src/main/assets/local-games/provision_rootfs_runtime.sh"
GUEST_SCRIPT="$PROJECT_ROOT/games/src/main/assets/local-games/runtime-rootfs/provision-container.sh"
MANIFEST="$PROJECT_ROOT/games/src/main/assets/local-games/runtime-rootfs/games-runtime.properties"
INSTALLER="$PROJECT_ROOT/games/src/main/java/com/termux/localgames/service/RootfsProvisionAssetInstaller.java"

sh -n "$SCRIPT"
sh -n "$GUEST_SCRIPT"
grep -q 'pkg.*install -y proot-distro' "$SCRIPT"
grep -q 'install --architecture aarch64 --name' "$SCRIPT"
grep -q 'login.*--isolated' "$SCRIPT"
grep -q -- '--bind.*:/run/games-provision' "$SCRIPT"
grep -q '/bin/sh /run/games-provision/provision-container.sh' "$SCRIPT"
grep -q 'base_container_ready' "$SCRIPT"
grep -q 'dpkg --configure -a' "$GUEST_SCRIPT"
grep -q 'copyAsset("local-games/provision_rootfs_runtime.sh", script, digest)' "$INSTALLER"
grep -q 'apt-get install.*"$SOURCE_ROOT"/\*.deb' "$GUEST_SCRIPT"
grep -q 'snapshot.debian.org/archive/debian/20260830T000000Z' "$GUEST_SCRIPT"
grep -qx 'baseImage=debian:trixie-20260824' "$MANIFEST"
if grep -q 'proot-distro.*build\|pip install\|Dockerfile' "$SCRIPT"; then
    printf '%s\n' 'device provisioning must install and configure a container directly' >&2
    exit 1
fi

printf '%s\n' 'RootFS on-device provision contract test passed.'
