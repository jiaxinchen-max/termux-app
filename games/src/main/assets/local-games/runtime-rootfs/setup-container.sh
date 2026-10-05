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

# All base components arrive pre-verified and pre-extracted (Java component framework) under
# components/<id>/. Install them by content, not by hardcoded id/URL:
#   - any *.deb (Hangover source bundle, Box64 .deb) -> apt-get install (resolves deps from the repo)
#   - a DXVK WoW64 tree (x64/ + x32/|x86/ at any depth) -> copy DLLs into
#     /opt/games-runtime/<id>/{system32,syswow64} for start_rootfs_game.sh to pick per game.
#   - a portable Wine build (bin/wine + bin/wineboot at any depth) -> copy the whole tree to
#     /opt/box64-wine for the standalone Box64 translator path (rootfs_prefix_warmup.sh's "box64"
#     case), as an alternative to Hangover's bundled wine+translator. Never apt-installed or
#     registered with dpkg -- stays a self-contained, relocatable directory.

# 1) Install every Debian package across all components in one dependency-resolving pass.
if find "$COMPONENTS_ROOT" -name '*.deb' -type f | grep -q .; then
    find "$COMPONENTS_ROOT" -name '*.deb' -type f -exec \
        apt-get install -y --no-install-recommends {} + || {
        apt-get -f install -y
        dpkg --configure -a
    }
fi
# Box64's .deb installs to /usr/local/bin; make sure it is on PATH for the launcher.
command -v box64 >/dev/null 2>&1 || ln -sf /usr/local/bin/box64 /usr/bin/box64

# 2) Lay out any DXVK components by their WoW64 split.
for compdir in "$COMPONENTS_ROOT"/*; do
    [ -d "$compdir" ] || continue
    x64dir=$(find "$compdir" -type d -name x64 | head -n 1)
    [ -n "$x64dir" ] || continue
    cid=$(basename "$compdir")
    dest="/opt/games-runtime/$cid"
    mkdir -p "$dest/system32" "$dest/syswow64"
    find "$x64dir" -maxdepth 1 -name '*.dll' -type f -exec cp {} "$dest/system32/" \;
    for name in x32 x86; do
        x32dir=$(find "$compdir" -type d -name "$name" | head -n 1)
        if [ -n "$x32dir" ]; then
            find "$x32dir" -maxdepth 1 -name '*.dll' -type f -exec cp {} "$dest/syswow64/" \;
            break
        fi
    done
    find "$dest/system32" "$dest/syswow64" -name '*.dll' -type f | grep -q . || {
        printf '%s\n' "dxvk_install_produced_no_dlls:$cid" >&2
        exit 70
    }
done

# 3) Lay out a portable Wine build (if any component bundles one) for the standalone Box64
# translator path. Detected by content (bin/wine + bin/wineboot), not by component id, matching
# the DXVK loop's approach above.
for compdir in "$COMPONENTS_ROOT"/*; do
    [ -d "$compdir" ] || continue
    winebin=$(find "$compdir" \( -type f -o -type l \) -name wine -path '*/bin/*' | head -n 1)
    [ -n "$winebin" ] || continue
    winebootbin=$(find "$compdir" \( -type f -o -type l \) -name wineboot -path '*/bin/*' | head -n 1)
    [ -n "$winebootbin" ] || {
        printf '%s\n' "box64_wine_missing_wineboot:$(basename "$compdir")" >&2
        exit 70
    }
    srcroot=$(dirname "$(dirname "$winebin")")
    mkdir -p /opt/box64-wine
    cp -a "$srcroot"/. /opt/box64-wine/
    chmod +x /opt/box64-wine/bin/wine /opt/box64-wine/bin/wineboot
done

mkdir -p /mnt/games/game /mnt/games/prefix
install -m 0644 "$MANIFEST" /etc/games-runtime.properties
rm -rf /var/lib/apt/lists/*

test -x /usr/bin/env
command -v box64 >/dev/null 2>&1 || { printf '%s\n' box64_missing >&2; exit 70; }
test -x /usr/bin/wine
test -x /usr/bin/wineboot
test -x /opt/box64-wine/bin/wine
test -x /opt/box64-wine/bin/wineboot
