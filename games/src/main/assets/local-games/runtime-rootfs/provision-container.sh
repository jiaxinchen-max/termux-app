#!/bin/sh

set -eu
PS4='+ '
set -x

PROVISION_ROOT=/run/games-provision
SOURCE_ROOT="$PROVISION_ROOT/hangover-source"
MANIFEST="$PROVISION_ROOT/games-runtime.properties"

[ -f "$MANIFEST" ] || { printf '%s\n' games_runtime_manifest_missing >&2; exit 66; }
find "$SOURCE_ROOT" -name '*.deb' -type f | grep -q . || {
    printf '%s\n' hangover_source_packages_missing >&2
    exit 66
}

export DEBIAN_FRONTEND=noninteractive
rm -f /etc/apt/sources.list.d/debian.sources
printf '%s\n' \
    'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20260830T000000Z trixie main' \
    > /etc/apt/sources.list
printf '%s\n' 'Acquire::Check-Valid-Until "false";' \
    > /etc/apt/apt.conf.d/99games-snapshot

apt-get update
if ! dpkg --configure -a; then
    apt-get -f install -y
    dpkg --configure -a
fi
apt-get install -y --no-install-recommends \
    alsa-utils ca-certificates libegl1 libegl-mesa0 libgl1 libgl1-mesa-dri libglx-mesa0 \
    fontconfig fonts-noto-cjk libvulkan1 locales mesa-vulkan-drivers pulseaudio-utils tar xz-utils zstd
sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' /etc/locale.gen
sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen
locale-gen
apt-get install -y --no-install-recommends "$SOURCE_ROOT"/*.deb

mkdir -p /mnt/games/game /mnt/games/prefix
install -m 0644 "$MANIFEST" /etc/games-runtime.properties
rm -rf /var/lib/apt/lists/*

test -x /usr/bin/env
test -x /usr/bin/wine
test -x /usr/bin/wineboot
