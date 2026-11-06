#!/bin/sh

set -eu
PS4='+ '
set -x

SETUP_ROOT=/run/games-setup
COMPONENTS_ROOT="$SETUP_ROOT/components"
MANIFEST="$SETUP_ROOT/games-runtime.properties"

[ -f "$MANIFEST" ] || { printf '%s\n' games_runtime_manifest_missing >&2; exit 66; }
[ -d "$COMPONENTS_ROOT" ] || { printf '%s\n' games_components_missing >&2; exit 66; }

export DEBIAN_FRONTEND=noninteractive
rm -f /etc/apt/sources.list.d/debian.sources
printf '%s\n' \
    'deb [check-valid-until=no] http://snapshot.debian.org/archive/debian/20260830T000000Z trixie main' \
    > /etc/apt/sources.list
printf '%s\n' 'Acquire::Check-Valid-Until "false";' \
    > /etc/apt/apt.conf.d/99games-snapshot

apt-get update
dpkg --configure -a
# The libx*/libcups/libsdl2 row is for box64's "native library bridge": box64 prefers the rootfs's
# native ARM64 build of these X11 / cups / SDL2 client libs over translating the guest's x86_64 copy
# (less overhead, and it silences the `[BOX64] Error initializing native libX.../libSDL2-2.0.so.0
# cannot open shared object file` lines that otherwise flood the wineboot console). Without them
# box64 just falls back to translation -- harmless, only noisier/slightly slower -- so this is an
# optimisation, not required. (box64 asks for libSDL2-2.0.so.0 specifically, provided by
# libsdl2-2.0-0; it never requests SDL1.2/SDL3, so those are intentionally omitted.)
apt-get install -y --no-install-recommends \
    alsa-utils ca-certificates libegl1 libegl-mesa0 libgl1 libgl1-mesa-dri libglx-mesa0 \
    libxcomposite1 libxcursor1 libxi6 libxrandr2 libxinerama1 libcups2t64 libsdl2-2.0-0 \
    curl fontconfig fonts-noto-cjk libvulkan1 locales mesa-vulkan-drivers pulseaudio-utils tar xz-utils zstd
sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/' /etc/locale.gen
sed -i 's/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen
locale-gen

# v11 receives only the pinned Hangover source component. Optional portable runtimes
# are private immutable versions bound at preparation/launch, never copied into this base.
for component in "$COMPONENTS_ROOT"/*; do
    [ -d "$component" ] || continue
    [ "$(basename "$component")" = rootfs_hangover_11.16 ] || {
        printf '%s\n' unexpected_optional_base_component >&2
        exit 66
    }
done
find "$COMPONENTS_ROOT/rootfs_hangover_11.16" -name '*.deb' -type f | grep -q . || {
    printf '%s\n' hangover_packages_missing >&2
    exit 66
}
find "$COMPONENTS_ROOT/rootfs_hangover_11.16" -name '*.deb' -type f -exec \
    apt-get install -y --no-install-recommends {} +

# Fixed empty bind targets do not imply that a component has been prepared.
mkdir -p /opt/box64-wine /opt/box64-proton /opt/box64/rootfs_box64_0.4.5
for id in rootfs_dxvk_2.7 rootfs_dxvk_3.1 rootfs_dxvk_1.10.3; do
    mkdir -p "/opt/games-runtime/$id/system32" "/opt/games-runtime/$id/syswow64"
done

mkdir -p /mnt/games/game /mnt/games/prefix
install -m 0644 "$MANIFEST" /etc/games-runtime.properties
rm -rf /var/lib/apt/lists/*

test -x /usr/bin/env
test -x /usr/bin/wine
test -x /usr/bin/wineboot
