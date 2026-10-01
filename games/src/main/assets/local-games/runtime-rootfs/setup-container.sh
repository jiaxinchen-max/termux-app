#!/bin/sh

set -eu
PS4='+ '
set -x

PROVISION_ROOT=/run/games-provision
SOURCE_ROOT="$PROVISION_ROOT/hangover-source"
MANIFEST="$PROVISION_ROOT/games-runtime.properties"
BOX64_DEB=/tmp/box64-android_0.4.5_arm64.deb
BOX64_URL='https://github.com/jiaxinchen-max/termux-app/releases/download/1.0.8/box64-android_0.4.5%2B20260908T103809.4e5f180-1_arm64.deb'
BOX64_SHA256='745cd5efc55d3f24d11ac2d9b958a03482b03f4df9d52764c1b6784435fc4e8e'

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
    curl fontconfig fonts-noto-cjk libvulkan1 locales mesa-vulkan-drivers pulseaudio-utils tar xz-utils zstd
sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' /etc/locale.gen
sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen
locale-gen
apt-get install -y --no-install-recommends "$SOURCE_ROOT"/*.deb
curl -fL --retry 3 --retry-delay 2 "$BOX64_URL" -o "$BOX64_DEB"
printf '%s  %s\n' "$BOX64_SHA256" "$BOX64_DEB" | sha256sum -c -
dpkg -i "$BOX64_DEB" || {
    apt-get -f install -y
    dpkg --configure -a
}
ln -sf /usr/local/bin/box64 /usr/bin/box64

mkdir -p /mnt/games/game /mnt/games/prefix
install -m 0644 "$MANIFEST" /etc/games-runtime.properties
rm -rf /var/lib/apt/lists/*

test -x /usr/bin/env
test -x /usr/local/bin/box64
test -x /usr/bin/wine
test -x /usr/bin/wineboot
