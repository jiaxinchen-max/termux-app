#!/bin/sh
# Installs the KWin hardware-accelerated Plasma packages (and, for proot mode, proot-distro plus
# the container). Runs in a visible Termux terminal session.

set -u
# debconf and dpkg conffile decisions are separate; -y alone cannot answer either.
export DEBIAN_FRONTEND=noninteractive

SPEC_PATH="${1:-}"
[ -n "$SPEC_PATH" ] && [ -f "$SPEC_PATH" ] || { echo 'Usage: desktop_install.sh spec --operation=install|host-prerequisites [--task-id=id --result-file=path]' >&2; exit 64; }
shift
OPERATION=''; TASK_ID=''; RESULT_FILE=''
for argument in "$@"; do
    case "$argument" in
        --operation=*) [ -z "$OPERATION" ] || exit 64; OPERATION="${argument#*=}" ;;
        --task-id=*) [ -z "$TASK_ID" ] || exit 64; TASK_ID="${argument#*=}" ;;
        --result-file=*) [ -z "$RESULT_FILE" ] || exit 64; RESULT_FILE="${argument#*=}" ;;
        *) echo "Unknown desktop install argument: $argument" >&2; exit 64 ;;
    esac
done
case "$OPERATION" in install|host-prerequisites) ;; *) echo 'Explicit --operation=install|host-prerequisites required' >&2; exit 64 ;; esac
if [ "$OPERATION" = host-prerequisites ]; then
    case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) echo 'Invalid prerequisite task id' >&2; exit 64 ;; esac
    [ "${#TASK_ID}" -le 128 ] || exit 64
    case "$RESULT_FILE" in /*) ;; *) echo 'Absolute task result file required' >&2; exit 64 ;; esac
    [ -d "$(dirname "$RESULT_FILE")" ] && [ ! -e "$RESULT_FILE" ] && [ ! -L "$RESULT_FILE" ] || exit 64
elif [ -n "$TASK_ID$RESULT_FILE" ]; then
    echo 'Task result flags are only supported for host-prerequisites' >&2; exit 64
fi
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

INSTALL_DONE=0
finish_operation() {
    code=$?
    trap - EXIT
    [ "$INSTALL_DONE" -eq 1 ] || { [ "$code" -ne 0 ] || code=1; }
    if [ "$OPERATION" = install ]; then
        if [ "$INSTALL_DONE" -ne 1 ] && [ "${DESKTOP_MODE:-}:${DESKTOP_DE:-}" = native:ubuntu_gnome ]; then
            rm -f "$PREFIX/share/termux-native-ubuntu/.installed"
        fi
        printf 'exit:%s\n' "$code" > "$DESKTOP_STATUS_FILE" || code=74
    else
        outcome=failed
        [ "$code" -ne 0 ] || outcome=succeeded
        temporary="$RESULT_FILE.tmp.$$"
        if (set -C; printf 'schemaVersion=1\ntaskId=%s\noperation=host-prerequisites\nstate=%s\nexitCode=%s\n' "$TASK_ID" "$outcome" "$code" > "$temporary") && mv "$temporary" "$RESULT_FILE"; then :
        else
            log 'Cannot publish host prerequisite task result'
            rm -f "$temporary"
            [ "$code" -ne 0 ] || code=74
        fi
    fi
    exit "$code"
}
trap finish_operation EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
[ "$OPERATION" != install ] || printf 'installing\n' > "$DESKTOP_STATUS_FILE" || exit 74
. "$(dirname "$0")/desktop_rendering.sh"
desktop_resolve_rendering || { log 'Unsupported host/container renderer pair; choose compatible renderers explicitly.'; exit 64; }
case "${DESKTOP_MODE:-}" in native|proot) ;; *) log 'Explicit desktop run mode required'; exit 64 ;; esac
[ "$OPERATION" != host-prerequisites ] || [ "$DESKTOP_MODE" = proot ] || { log 'Host prerequisites require PROOT mode'; exit 64; }
case "${DESKTOP_DE:-}" in xfce4|kwin_plasma|gnome|ubuntu_gnome|hyprland) ;; *) log 'Unsupported desktop environment'; exit 64 ;; esac
case "${DESKTOP_COMPONENT_RENDERER:-}" in system|zink|llvmpipe) ;; *) log 'Explicit system, zink or llvmpipe renderer required'; exit 64 ;; esac
if [ "$DESKTOP_DE" = hyprland ]; then
    [ "$OPERATION" = install ] && [ "${DESKTOP_USB_STORAGE:-}:${DESKTOP_HYPRLAND_BACKEND:-}:${DESKTOP_HYPRLAND_HOST_GL_MODE:-system}" = off:android:system ] || {
        log 'Hyprland requires its pinned Android/System EGL host and USB off; archives are unsupported.'; exit 64
    }
    . "$(dirname "$0")/hyprland-components.sh"
    hyprland_resolve_components || { log 'Unknown or incompatible Hyprland compositor/theme selection'; exit 64; }
    case "$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER:${DESKTOP_HYPRLAND_CLIENT_GL_MODE:-default}" in
        native:system:default|proot:system:default|proot:zink:zink) ;;
        *) log 'Hyprland supports default clients, or explicit experimental Zink clients in PRoot only.'; exit 64 ;;
    esac
fi
if [ "$DESKTOP_DE:$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER" = kwin_plasma:proot:system ]; then
    log 'Container Plasma requires explicit Zink or llvmpipe'; exit 64
fi
if [ "$OPERATION" = host-prerequisites ] && [ "${DESKTOP_USB_STORAGE:-off}" != off ]; then
    log 'Container archive prerequisites require USB storage off'; exit 64
fi
if [ "$OPERATION" = host-prerequisites ] && { [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; }; then
    log 'GNOME container archives are not supported'; exit 64
fi
case "${DESKTOP_USB_STORAGE:-}" in off|read-only) ;; *) log 'Explicit USB storage mode off|read-only required'; exit 64 ;; esac
if [ "$DESKTOP_USB_STORAGE" = read-only ] && [ "$DESKTOP_DE" = kwin_plasma ]; then
    log 'USB GIO volumes are supported by GNOME/Files and XFCE/Thunar; Dolphin/Solid is not supported.'
    exit 64
fi
if [ "$DESKTOP_DE" = gnome ]; then
    case "$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER:${DESKTOP_GNOME_BACKEND:-}:${DESKTOP_GNOME_RENDERER:-}" in
        native:system:android:system)
            [ "$DESKTOP_REFRESH_RATE" = 60 ] || { log 'Native GNOME requires 60Hz'; exit 64; }
            [ -z "${DESKTOP_GNOME_DISPLAY_GL_MODE:-}${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" ] || { log 'Native GNOME does not use X11 display parameters'; exit 64; } ;;
        proot:llvmpipe:x11:llvmpipe)
            [ "${DESKTOP_CONTAINER_CODENAME:-}" = trixie ] || exit 64
            [ "${DESKTOP_GNOME_DISPLAY_GL_MODE:-}:${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" = system:native ] || { log 'Container GNOME llvmpipe requires explicit host system/native'; exit 64; } ;;
        proot:zink:x11:zink)
            [ "${DESKTOP_CONTAINER_CODENAME:-}" = trixie ] || exit 64
            [ "${DESKTOP_GNOME_DISPLAY_GL_MODE:-}:${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" = zink:dmabuf ] || { log 'Container GNOME Zink requires explicit host zink/dmabuf'; exit 64; } ;;
        *) log 'Unsupported explicit GNOME backend/renderer combination'; exit 64 ;;
    esac
fi

# Invalidate the previous completion before initialization; failure must leave no success marker.
if [ "$OPERATION" = install ] && [ "${DESKTOP_MODE:-}" = proot ] && [ "${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" != 1 ]; then
    rm -f "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE" \
        "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE-zink" || exit 74
fi

# An unsuccessful retry must also invalidate the exact pair's old initialization receipt.
if [ "$OPERATION:$DESKTOP_MODE:$DESKTOP_DE" = install:proot:kwin_plasma ]; then
    rm -f "$PREFIX/var/lib/termux-desktop/rendering/$DESKTOP_PROOT_DISTRO.$DESKTOP_HOST_GL_MODE.$DESKTOP_GUEST_GL_MODE.env" || exit 74
fi

PLASMA_EMULATOR=0
if [ "$DESKTOP_MODE:$DESKTOP_DE" = proot:kwin_plasma ] &&
    [ "$(/system/bin/getprop ro.kernel.qemu 2>/dev/null)" = 1 ]; then
    PLASMA_EMULATOR=1
fi

BASE_URL="https://github.com/jiaxinchen-max/kwin/releases/download/$DESKTOP_RELEASE_TAG"
# sha256 of the pinned release assets (GitHub release digests of termux-hwaccel-6.6-r28).
if [ "$DESKTOP_RELEASE_TAG" != "termux-hwaccel-6.6-r28" ]; then
    log "Unsupported desktop release $DESKTOP_RELEASE_TAG"
    exit 65
fi
case "$DESKTOP_DE" in
    xfce4) DESKTOP_LABEL="XFCE4" ;;
    kwin_plasma) DESKTOP_LABEL="KWin Plasma" ;;
    gnome) DESKTOP_LABEL="GNOME" ;;
    ubuntu_gnome) DESKTOP_LABEL="Ubuntu GNOME" ;;
    *) DESKTOP_LABEL="$DESKTOP_DE" ;;
esac

# Xlorie (independent X server) packages, pinned to the xserver release that carries the
# termux-render fixes; needed by every XFCE4 desktop and shared with the KWin ones.
XLORIE_RELEASE_TAG="termux-xlorie-21.1.16-r9"
XLORIE_BASE_URL="https://github.com/jiaxinchen-max/xserver/releases/download/$XLORIE_RELEASE_TAG"
XLORIE_DEBS="
libandroid-stub_30-3_aarch64.deb 88c7f4ba78a5eb57eafc2f1102ceea562a156fe7346528ca35c771eb0927fefc
xorg-server_21.1.16-9_aarch64.deb fa06b6bcc1dcc19b4b78d7497fc33acce43cc72320c62b7a9679a9113aeceaac
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
libxshmfence_1.3.3-2_aarch64.deb be95caf500b002fed8b5c0142ad360efbdec4ff1334be5565e9b9628f8663dc2
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
"

DEBS="
libandroid-stub_30-3_aarch64.deb 88c7f4ba78a5eb57eafc2f1102ceea562a156fe7346528ca35c771eb0927fefc
gl4es_1.1.7.20260927_aarch64.deb eb0ba70d084c87b84b144ef2ce6ad415ca3810dcb356d77e4e8cefe962157913
kwin_6.6.0-28_aarch64.deb d6b97467522ce0736d0f142baddd333072da76208c1bca985685b4a2b2d82cdf
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
qt6-qtbase-cross-tools_6.12.0-1+termuxkwin1_all.deb 07b9a9ae857225a814e85c74fdc613cc0720c328d3b2f4f5270a47b67169bf5f
qt6-qtbase-gtk-platformtheme_6.12.0-1+termuxkwin1_aarch64.deb 88174bce8ca6f5f5034c6a9b14360e1d1bc7f8e5af014cfbcd2440840897c622
qt6-qtbase_6.12.0-1+termuxkwin1_aarch64.deb ac6a509819084af9601db572d88d02e384a034cbc27923f8c9b0780dd6a29cfb
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
xwayland_24.1.11-1_aarch64.deb 3cc2999ae0ba90495abad71445f794723efc06a97c001b383467665e3038e8a4
"

# GNOME/USB release asset identities and publication digests.
GNOME_USB_RELEASE_TAG="gnome-usb-20261030-r1"
GNOME_USB_BASE_URL="https://github.com/jiaxinchen-max/termux-app-packages/releases/download/$GNOME_USB_RELEASE_TAG"
GNOME_DEBS="
accountsservice_23.13.9_aarch64.deb 4d1dfa7ae196beba68378d04913a0ab2eb09b105b4e305da27bd97a355fe1f4e
gexiv2_0.14.7_aarch64.deb 2eea495d80f59b7285f8225df2526cd8a7c06ce03f9abd1ca2d65b9427772221
gjs_1.82.3_aarch64.deb 6eedb4cb279672a535f473012a0864d12bd1bf1895ad290ae3028b924fa93a66
gnome-autoar_0.4.5_aarch64.deb 3d98ef33d6e48a21af97bc1d03440303106172969ae5fd67bd77ded9348b03f6
gnome-session_48.0_aarch64.deb 8fd68bd0c9a720e734d7058730e9563c6411147ce17f76d5d61dee34e031bda7
gnome-settings-daemon-schemas_48.1_all.deb 03ed7b7a1f8d184353906f511e66bbf767f32622fb26c280ea896cb3789f9215
gnome-shell_48.7-1_aarch64.deb 285cdb99e61c761405d154f608520cc3bfdc8b7c9ea91b3619d467c9d7f4d900
libgdm_48.0-1_aarch64.deb fb9d03f4665fd540af125d91784d0d284122b5ee81b53f279705eafdacb63231
localsearch-schemas_3.9.0_all.deb 13a0f641887be3fb7c92034ae485a67bf9a8726a73bed04d8f6f77d3e6f1f2b6
mutter_48.1-25_aarch64.deb ef6631c3a21eeb11bbafd50906bd7e77fd3c65387f734696250a60ffa72f6549
nautilus_48.3-3_aarch64.deb ee53e9a2961294df5516ba0d7e4a3e138e57634ec4b132f37f1ea41c97d26506
pipewire-dbus_1.4.9_aarch64.deb f42dd82ff03801d5c1e14203f78c3092cfdbf0a57add3133c37331afd4b8ac8b
polkit_126_aarch64.deb 1eddba206bc72c6b423557ff749416971efdec25be03fca45ad5498b33e98b67
spidermonkey_128.10.0-3_aarch64.deb d5a3437b0d0543e58cb01e07cad277334ee849b663c06aef71f5d4c4e8973308
"
NATIVE_GNOME_APPS_TAG="native-gnome-apps-48-r2"
NATIVE_GNOME_APPS_URL="https://github.com/jiaxinchen-max/termux-app-packages/releases/download/$NATIVE_GNOME_APPS_TAG"
NATIVE_GNOME_APPS_DEBS="
gnome-control-center_48.5_aarch64.deb fe400c3c0366bdd81226d2e15d626178052a89d30ec32d9ff14f162d42a58308
tecla_48.0.2_aarch64.deb 3d427cee412bfca29755e4926d4be063bb66d7bddd0c743982cd70c4eff23875
gnome-clocks_51.0-1_aarch64.deb b4128a755e3b8a76ff1e07d13067ace817015b0490d90c5c8e71989bf769937c
"
USB_DEBS="
termux-usb-bridge_0.4.1_aarch64.deb d00381ecca8285e502bcd00f3491032d25f14d499454bccbb1cb1dab3964770c
"
USB_GLIBC_ARTIFACT="termux-usb-gio-trixie-aarch64-0.4.1.tar.gz 0238798338273d9e4d983407c6399f85a0abd0a94f7b4daa09b747833ddeaaec"

run() {
    status_file="$DESKTOP_LOG_FILE.status.$$"
    command_log="$DESKTOP_LOG_FILE.command.$$"
    rm -f "$status_file" "$command_log"
    (
        "$@"
        printf '%s\n' "$?" > "$status_file"
    ) 2>&1 | tee "$command_log" | tee -a "$DESKTOP_LOG_FILE"
    status=$(cat "$status_file" 2>/dev/null || printf '1')
    if [ "$status" -ne 0 ]; then
        log "Command failed (exit $status): $*"
        # Repeat actionable apt/dpkg diagnostics next to the final error shown in the terminal.
        details=$(sed -n '/^E:/p; /^dpkg: error/p; /[Uu]nmet dependencies/p; /[Dd]epends: /p; /[Bb]roken packages/p' "$command_log" | tail -n 12)
        [ -z "$details" ] || log "$details"
    fi
    rm -f "$status_file" "$command_log"
    return "$status"
}

fail() {
    failure_status=$?
    [ "$failure_status" -ne 0 ] || failure_status=1
    log "Desktop install failed: $*"
    exit "$failure_status"
}

if [ "$(uname -m)" != "aarch64" ]; then
    fail "$DESKTOP_LABEL desktop packages are only built for aarch64"
fi

fetch_debs() { # tag base_url deb_list -> prints paths
    tag="$1"; base_url="$2"; list="$3"
    deb_dir="${TMPDIR:-$PREFIX/tmp}/desktop-debs-$tag"
    mkdir -p "$deb_dir" || fail "cannot create $deb_dir"
    : > "$deb_dir/manifest"
    printf '%s\n' "$list" | while read -r name digest; do
        [ -n "$name" ] || continue
        printf '%s %s\n' "$name" "$digest"
    done > "$deb_dir/manifest"
    paths=""
    while read -r name digest; do
        target="$deb_dir/$name"
        asset_url="$base_url/$name"
        case "$name" in
            libandroid-stub_30-3_aarch64.deb) asset_url="https://packages-cf.termux.org/apt/termux-main/pool/main/liba/libandroid-stub/$name" ;;
        esac
        if [ -f "$target" ]; then
            # stdout of this function is the deb path list; logs go to the file only.
            printf '%s\n' "Cached $name" >> "$DESKTOP_LOG_FILE"
        else
            { curl -fL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 30 -o "$target" "$asset_url" || fail "download $name"; } \
                >> "$DESKTOP_LOG_FILE" 2>&1
        fi
        paths="$paths $target"
    done < "$deb_dir/manifest"
    printf '%s' "$paths"
}

# Native app assets use a fixed release and verify bytes before apt sees them.
fetch_verified_debs() (
    verified_tag="$1"; verified_url="$2"; verified_list="$3"
    verified_cache="${TMPDIR:-$PREFIX/tmp}/desktop-debs-$verified_tag"
    # Discard a damaged cache before downloading, so reinstall repairs it.
    printf '%s\n' "$verified_list" | while read -r name digest; do
        [ -n "$name" ] || continue
        target="$verified_cache/$name"
        if [ -f "$target" ] && ! printf '%s  %s\n' "$digest" "$target" | sha256sum -c - >/dev/null 2>&1; then
            rm -f "$target" || exit 1
        fi
    done || exit 65
    verified_paths=$(fetch_debs "$verified_tag" "$verified_url" "$verified_list") || exit "$?"
    printf '%s\n' "$verified_list" | while read -r name digest; do
        [ -n "$name" ] || continue
        target="$verified_cache/$name"
        if ! printf '%s  %s\n' "$digest" "$target" | sha256sum -c - >/dev/null 2>&1; then
            rm -f "$target"
            printf 'Native GNOME app SHA256 mismatch: %s\n' "$name" >&2
            exit 65
        fi
    done || exit 65
    printf '%s' "$verified_paths"
)

# Fixed data-only component, outside the APK. Only verified downloads become cache entries.
fetch_native_ubuntu_profile() {
    (
        profile_name=native-ubuntu-gnome-48-r2.bin
        profile_digest=5a767f2ef030ea401015e9c4afad6cfc582c203c6f4eb30e079ae6935474ff03
        profile_url="https://github.com/jiaxinchen-max/termux-app-packages/releases/download/native-ubuntu-gnome-48-r2/$profile_name"
        profile_cache="${TMPDIR:-$PREFIX/tmp}/desktop-native-ubuntu-gnome-48-r2"
        mkdir -p "$profile_cache" || exit 1
        profile_target="$profile_cache/$profile_name"
        if [ -f "$profile_target" ] && printf '%s  %s\n' "$profile_digest" "$profile_target" | sha256sum -c - >/dev/null 2>&1; then
            printf '%s\n' "$profile_target"
            exit 0
        fi
        profile_partial=$(mktemp "$profile_cache/.download.XXXXXXXX") || exit 1
        trap 'rm -f "$profile_partial"' EXIT
        trap 'exit 1' HUP INT TERM
        if ! curl -fL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 30 \
            -o "$profile_partial" "$profile_url" >> "$DESKTOP_LOG_FILE" 2>&1; then
            printf '%s\n' 'Native Ubuntu appearance download failed; retry desktop installation.' >> "$DESKTOP_LOG_FILE"
            exit 1
        fi
        if ! printf '%s  %s\n' "$profile_digest" "$profile_partial" | sha256sum -c - >> "$DESKTOP_LOG_FILE" 2>&1; then
            printf '%s\n' 'Native Ubuntu appearance SHA256 mismatch; refusing installation.' >> "$DESKTOP_LOG_FILE"
            exit 1
        fi
        mv -f "$profile_partial" "$profile_target" || exit 1
        printf '%s\n' "$profile_target"
    )
}

# Creates the container instance for this desktop. proot-distro caches image layers locally and
# reuses them on later installs, so only the very first creation downloads; -n gives every
# instance its own alias so instances coexist without touching each other.
ensure_container_instance() { # alias source
    alias_name="$1"; source="$2"
    for root in containers installed-rootfs; do
        [ -d "$PREFIX/var/lib/proot-distro/$root/$alias_name" ] && return 0
    done
    if proot-distro install 2>&1 | grep -q -- '-n, --name'; then
        run proot-distro install -n "$alias_name" "$source" ||
            fail "proot-distro install $source as $alias_name"
    else
        fail "proot-distro too old: multiple container instances need proot-distro 5.10+ (-n)"
    fi
}

# The Turnip Vulkan ICD (Adreno driver for the container's Qt Vulkan RHI) is not an apt package:
# Debian's Turnip has no KGSL backend. The container session aborts without it, so unpack the
# matching mesa-for-android-container tarball (lfdevs/mesa-for-android-container) into the container rootfs. This runs
# on the host, where curl honours ~/.curlrc and https_proxy.
TURNIP_REPO="lfdevs/mesa-for-android-container"
TURNIP_RELEASE_TAG="mesa-27.0.0-devel-20261025"
GNOME_TURNIP_ARTIFACT="mesa-for-android-container_27.0.0-devel-20261025_debian_trixie_arm64.tar.gz b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687"
ensure_trixie_turnip_icd() { # alias [backup directory]; never infer a driver from file presence
    mesa_backup_dir="${2:-}"
    rootfs="$PREFIX/var/lib/proot-distro/containers/$1/rootfs"
    archive=$(fetch_debs "$TURNIP_RELEASE_TAG" "https://github.com/$TURNIP_REPO/releases/download/$TURNIP_RELEASE_TAG" "$GNOME_TURNIP_ARTIFACT") || fail 'downloading verified GNOME Zink Mesa/Turnip'
    set -- $archive
    [ "$#" -eq 1 ] || fail 'expected one verified Mesa artifact'
    if [ -n "$mesa_backup_dir" ]; then
        backup_dir="$mesa_backup_dir"
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland_gpu.py" backup-mesa \
            --archive "$1" --rootfs "$rootfs" --destination "$backup_dir" || fail 'backing up container Mesa files'
        [ ! -L "$backup_dir/package-holds-before.txt" ] || fail 'unsafe package holds backup'
        if [ ! -f "$backup_dir/package-holds-before.txt" ]; then
            holds_temporary="$backup_dir/package-holds-before.tmp.$$"
            (set -C; run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- apt-mark showhold > "$holds_temporary") || fail 'recording package holds'
            mv "$holds_temporary" "$backup_dir/package-holds-before.txt" || fail 'saving package holds'
        fi
    fi
    run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_install_mesa.py" \
        --archive "$1" --rootfs "$rootfs" --codename trixie --gl-mode zink || fail 'installing verified trixie Zink Mesa/Turnip'
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh -eu -c '
        for library in /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so /usr/lib/aarch64-linux-gnu/libgallium-27.0.0-devel.so /usr/lib/aarch64-linux-gnu/libGLX_mesa.so.0; do
            dependencies=$(ldd "$library")
            printf "%s\n" "$dependencies"
            case "$dependencies" in *"not found"*) exit 1 ;; esac
        done' || fail 'resolving container Mesa/Turnip libraries'
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- apt-mark hold \
        mesa-vulkan-drivers libgbm1 libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2 \
        || fail 'holding the explicit container Mesa/Turnip build'
}

ensure_gnome_turnip_icd() {
    ensure_trixie_turnip_icd "$1"
}

ensure_turnip_icd() { # alias
    rootfs="$PREFIX/var/lib/proot-distro/containers/$1/rootfs"
    icd="usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
    if [ -r "$rootfs/$icd" ] && [ -e "$rootfs/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so" ]; then
        log "Turnip Vulkan ICD already present in $1; skipping"
    else
        id=$(sed -n 's/^ID=//p' "$rootfs/etc/os-release" 2>/dev/null | tr -d '"' | head -n1)
        codename=$(sed -n 's/^VERSION_CODENAME=//p' "$rootfs/etc/os-release" 2>/dev/null | tr -d '"' | head -n1)
        [ -n "$id" ] && [ -n "$codename" ] || fail "cannot tell which distro $1 is (no os-release)"
        # Mesa tarballs are published per stable release; forky/sid use the newest Debian one. The full
        # Mesa build is needed, not just turnip_*: with the turnip-only tarball the Wayland WSI crashes
        # (SIGBUS in wsi_create_buffer_blit_context) because it mismatches the container's libgbm/EGL.
        case "$id:$codename" in debian:forky|debian:sid|debian:testing) codename=trixie ;; esac
        tag="$TURNIP_RELEASE_TAG"
        url="https://github.com/$TURNIP_REPO/releases/download/$tag/mesa-for-android-container_${tag#mesa-}_${id}_${codename}_arm64.tar.gz"
        log "Installing the Turnip Vulkan ICD into $1: $url"
        tarball="${TMPDIR:-$PREFIX/tmp}/turnip.$$.tar.gz"
        run curl -fL -m 300 -o "$tarball" "$url" || { rm -f "$tarball"; fail "download of the Turnip tarball failed"; }
        run tar -xzf "$tarball" -C "$rootfs" || { rm -f "$tarball"; fail "unpacking the Turnip tarball failed"; }
        rm -f "$tarball"
        [ -r "$rootfs/$icd" ] || fail "the Turnip tarball did not provide /$icd"
    fi
    # Debian's mesa-vulkan-drivers ships a Turnip without the KGSL backend under the same file names;
    # keep a later apt upgrade from overwriting the one just unpacked.
    run proot-distro login "$1" --shared-tmp -- apt-mark hold mesa-vulkan-drivers libgbm1 libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2 \
        || fail "holding the container Turnip packages"
}

# Required runtime files, with release digests retained as publication metadata.
# Installation checks package status, version and file presence without digest verification.
HOST_FILES='
mutter lib/libmutter-16.so.0.0.0 dd001f7aeb271f7b915fadf80dd5cf74c3966322bfa89e3061a3cba128562094
gjs bin/gjs-console 942cdbf442a9d45c395703ca358664195300179e6a220234d9cda099b8e43316
gnome-session bin/gnome-session 3171e8d06aac98e0de4b0450e2d6224254c82b4f24ebee881c319c6fea360f90
gnome-session bin/gnome-session-inhibit 1450c4049801adb16c94f7b03d996caba1fa6e53057349e899378c57344244fe
gnome-session bin/gnome-session-quit 2b3f6ef86b0ab270886f6de62f4f89be9b78fab3539040806ecff1e5c2fa4580
gnome-shell bin/gnome-extensions b9b1e1acf86eccc621a5726aadef1d8f0ea91241bdafa7df882c170000b4b7aa
gnome-shell bin/gnome-shell 9885aa7e0760687b2c6b25945d44e7c86046de764eb5ff6157283e878302dd81
gnome-shell bin/gnome-shell-extension-tool beb1a2cba645dd835d8c61d75c93e2b16b168e16bc64b1fdcba71d6490a05228
gnome-shell bin/gnome-shell-test-tool 7d55200a6d91d4aa4d2511de2b8a2aed06ce4eb11a2fef9b20f76e49f5e93b80
libandroid-stub lib/libandroid.so 0a9ca6b42e5f786e931b5f97f83c57f671ac59946919cfdb47e94f2ec2d4eee8
mutter bin/gdctl eef4c975f759f59a6958b1050248b2bf39aa903663520cf58b3b6223ae1ac406
mutter bin/mutter bb1869e3c5be0cdfdbc34cc36e28517a288cc6cf526bc80286f5232992d8c644
mutter lib/mutter-16/Clutter-16.gir 830a221057aa5a3cd8a486bda4e3cf42f80c7d983b84bd7831716be4b4c2c238
mutter lib/mutter-16/Clutter-16.typelib e9d64c29e2b898378d184f8ffd91bb8cea2ac7e69929dc9c24e781943037c014
mutter lib/mutter-16/Cogl-16.gir 850fec59ece1de9bfe866a696aa482eff3c6265cc0a47ff7a072f321daa918f3
mutter lib/mutter-16/Cogl-16.typelib 292934aa13a783b0a4538625efb115f6cbdf8f5e2dd1ac978305310bfeca3674
mutter lib/mutter-16/Meta-16.gir ebbe4c7a31acbb8a75a41a0986e5ad3e1ea4750fe4c8a51d5c2294cabb8ef6d6
mutter lib/mutter-16/Meta-16.typelib 1c725beb318397280b409f98f63ffd0b834ea713d5aeeb4e6d81936eba940406
mutter lib/mutter-16/Mtk-16.gir eaf0be07e578647c06cd0a95c80691639dd192f600f126f16a9f30ed4472b9b7
mutter lib/mutter-16/Mtk-16.typelib c75f184c754b45c3c0cf77502658a17022c3e4ebebe17c4f4c3b30b51ac6c5c4
mutter lib/mutter-16/libmutter-android-system-egl.so dab9592e1f62d6bc44815b7434c3fdf060b671b9b6d2a682c75bcd3a4382c4be
mutter lib/mutter-16/libmutter-clutter-16.so.0.0.0 f43ba0340e71af6f034d1ad9f56f4962702e733ec5b7cfccd3086477c8c5cc7d
mutter lib/mutter-16/libmutter-cogl-16.so.0.0.0 028ea30c9407ab9fe936e262454957a311c3ffd70c9bdd106a9dce0dce2510cd
mutter lib/mutter-16/libmutter-mtk-16.so.0.0.0 b7d0ea2fffccd9d1da1847cc5384b6e2b4187f449dd531a343d660f918150a46
mutter lib/mutter-16/libvndksupport.so fd7ffba8dd4bf9e7976b5e6cfb4cb92f75620afc7b48d21a90f874569d551ca1
mutter lib/mutter-16/plugins/libdefault.so eefd8b16c45cc1b0f2db43b538f2a0c2af802efdea6d1e12e96bd9643ebe8c31
nautilus bin/nautilus 8ddf9a4fbc6b19df2fdb70476674a13198470c05bee70e4652edee9119c9bee5
nautilus bin/nautilus-autorun-software e338f23ab98b50d401bf75b43a667286bc526cbfb8e404d836622c4d6520bd0e
spidermonkey bin/js128 01a782d01c41bae795baf4cefdc80ee0bdc4075dee8614d6658f7d9791bb40f6
spidermonkey bin/js128-config be73a7e0beeca4137ac3b204249c9a343332c4b218fb1dd172ceab02cd189436
termux-usb-bridge bin/termux-usb-bridge 4ad142204aee87a8fc5d9c802aeb222598b2ab61adf1946e6cbbadeb38e090af
termux-usb-bridge bin/termux-usb-run 6a7550c3e0a0d97aa1ac3029b3f783d57395e943becee0665de561e31f5b6a97
termux-usb-bridge bin/tusb-camera 42c0a754de42b34564906f78c61cb8f9a03a0f4df12ea5f87fbb48901854776c
termux-usb-bridge bin/tusb-camera-run f2e9ab4e44d085e099268e11f3691294dbb4f5e685342b495e183ef1bce0c9c6
termux-usb-bridge bin/tusb-camera2 2f047af7d952e435efe6890e249478c85b2d80ed8b7c4b822b86bd2270358756
termux-usb-bridge bin/tusb-desktop 52f79b798642e8fb626228e783b2f57f488d386afafbd2a56a1463d190f3ca4d
termux-usb-bridge bin/tusb-files 78f97eee0ddea66f8b02442f5c085351e27b3b47535cfb7829fb8ceca11915fb
termux-usb-bridge bin/tusb-gamepadd f0f057661f0ff1042a16761621cc59b0c188168f081d3c98abbcc085d52481e9
termux-usb-bridge bin/tusb-gio-mount 37c543ce7b6f8c320da8bfa1072b44536d2a5952a0bbff39e8f994d178499abd
termux-usb-bridge bin/tusb-inspect 0d825caa532832ae213b77c1bb66d6e6e30e853e82842867aab72ac1ccc6cc27
termux-usb-bridge bin/tusb-probe d7f9b370f6bda6e3b3c40640d2595e97a593e107df1f0452fa2f1e4b2dda4e74
termux-usb-bridge bin/tusb-sdl-run f1b2cf02842f1fa589b221e911bc77d154def5f774f1ac43594f16c782412403
termux-usb-bridge lib/gio/modules/libtusb-volume-monitor.so 743c300a01bde0b291e591fa8b062eaa26625d3f82deedd00436471fcecbdf88
termux-usb-bridge libexec/termux-usb-bridge/tusb-camera2-capture 562daa4998c90002545c0ffe0636d69b870f00f2367b162067b9f158664c195f
termux-usb-bridge libexec/termux-usb-bridge/tusb-camera2-source 099eaf0ca7a4493e1c26aca15b898fb112160c1b0b5dbd1862d3e14ddfa7a611
termux-usb-bridge share/termux-usb-bridge/broker.py efcfd2e16860af6b545255e45a6306b998d140c185600762d6d55bb8d6d9190d
termux-usb-bridge share/termux-usb-bridge/camera2.py d9f41ba9efad0d6f08e5d4c8ad054a0f356365b66dd56b63807e0d297c24bb6e
termux-usb-bridge share/termux-usb-bridge/desktop.py 1e739c2b1c196dcb7b69e176472a3fb1835fa31ca2bdd4f1ad3db3144c2f8542
termux-usb-bridge share/termux-usb-bridge/filesystem.py 9271333b47c1801e5dee6e6a53293a6540acf46e30afcc32bcf5890c708379a0
kwin bin/kwin_wayland 8ccb4e3bd40ca9dfd9426e49056fcd8167dca54900ac01c54fa33775844bbd36
kwin bin/start-plasma 366c1300a6b3c255e401b13d358778b2536300a4a92fe96117887ed9431e6de0
kwin bin/kwin-android-rendering-env c00cef0311e4829e8229059837cb8ee177751345856c159294b15494572c42da
kwin bin/kwin-qt-client-renderer-probe 2f56beabb6caeea4e0d44f9300227bf02d14c9137326868f7c7daabc16d3c3bb
kwin lib/libkwin.so.6.6.7 eee1bfc582e045ec44d8a9216ff787e435a8409a3ce4afbb0cd2114f131c4c87
kwin lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so ad6533bb6a2ddbb7bc1333d60cfb262261454b6650ee105c04a0f31eddccdf69
kwin lib/qt6/termux-system-egl/libEGL.so.1 d5e14819eff485aef4cb3373a713ff6c2eb6da8de9fe976ae7874990295b77d1
kwin lib/qt6/termux-system-egl/libEGL.so d5e14819eff485aef4cb3373a713ff6c2eb6da8de9fe976ae7874990295b77d1
qt6-qtbase lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so 73e1b5cd7181cc3aa05424f54efaa09c6830ec3f8282027cae6935d22f8f9985
qt6-qtbase-gtk-platformtheme lib/qt6/plugins/platformthemes/libqgtk3.so af3ae4b58f51c13ccc74c510860cc2f6aa288a35fd49974755bbf9be74fd7fe8
qt6-qtbase-cross-tools opt/qt6/cross/lib/qt6/moc 7bdd400ea3e8eb2d888774f92505894502fa45828ba6531cd4ba898a1dc555e5
gl4es lib/gl4es/libGL.so.1 4cef5efd53f4ba96447bcefdc755a1fc466d9c3031df69536c59287c83ff5813
libandroid-stub lib/libtermux-platform-ns.so 6c39c6a3318accd5e34f4f2c9e5c9e14eed113fb3a92e8c0be86006dc9256e34
libepoxy lib/libepoxy.so aa2b42ea59a8e9fd7769f29d5480cee0808aa14d543c5110116149affcf331b2
termux-render lib/libtermux-render.so e233a5479d8f353eb83181d98e0eb82fbcd9bb5ec7ec873167c96d998cc53ddc
xwayland bin/Xwayland 7475e64912162047442e6de9f96213a21bfc38ca2ce902b06a609869a28a4476
xorg-server bin/Xlorie a709d798dd347c72166a0572ece320f5d2c47d2a29dedb2f5f871297208be0a8
libxshmfence lib/libxshmfence.so 1ccd07bab7c2d89a8ae37bc97254a2022e8a49fe131aede93c80ecf6c6173c3d
'
package_version() {
    package_status=$(dpkg-query -W -f='${Status}' "$1" 2>/dev/null) || return 1
    case "$package_status" in 'install ok installed'|'hold ok installed') ;; *) return 1 ;; esac
    dpkg-query -W -f='${Version}' "$1" 2>/dev/null
}

pinned_package_valid() { # package version
    current=$(package_version "$1") || return 1
    [ "$current" = "$2" ] || return 1
    printf '%s\n' "$HOST_FILES" | while read -r package relative digest; do
        [ "$package" = "$1" ] || continue
        [ -f "$PREFIX/$relative" ] || exit 1
    done
}
proot_valid() {
    current=$(package_version proot) || return 1
    dpkg --compare-versions "$current" ge 5.1.107 || return 1
    [ -x "$PREFIX/bin/proot" ]
}
proot_distro_valid() {
    current=$(package_version proot-distro) || return 1
    # The archive manager uses the 5.10 containers/<alias>/rootfs contract only.
    # Unknown future layout contracts are rejected, rather than guessed.
    dpkg --compare-versions "$current" ge 5.10.3 || return 1
    dpkg --compare-versions "$current" lt 6 || return 1
    [ -x "$PREFIX/bin/proot-distro" ] || return 1
    [ -x "$PREFIX/bin/python3" ] || return 1
    # 5.10.3 has no --version flag. Even CLI help can install missing runtime packages,
    # so inspect its pure metadata/constants/parser modules without entering the CLI.
    PREFIX="$PREFIX" "$PREFIX/bin/python3" -I -B -c '
import importlib.metadata
import os
import sys
try:
    from proot_distro.constants import PROGRAM_VERSION, IS_TERMUX, CONTAINERS_DIR
    from proot_distro.parser import build_parser
    expected = sys.argv[1]
    parsed, unknown = build_parser().parse_known_args([
        "install", "-n", "desktop-archive-probe", "debian:trixie"])
    valid = (
        importlib.metadata.version("proot-distro") == PROGRAM_VERSION == expected
        and IS_TERMUX is True
        and os.path.realpath(CONTAINERS_DIR) == os.path.realpath(
            os.path.join(os.environ["PREFIX"], "var/lib/proot-distro/containers"))
        and parsed.command == "install"
        and parsed.custom_container_name == "desktop-archive-probe"
        and parsed.image_ref == "debian:trixie"
        and unknown == [])
except (Exception, SystemExit):
    sys.exit(1)
sys.exit(0 if valid else 1)
' "${current%%-*}"
}
check_host_prerequisites() {
    missing_debs=''
    manifest="$DEBS"
    release="$DESKTOP_RELEASE_TAG"
    url="$BASE_URL"
    if [ "$DESKTOP_DE" = xfce4 ]; then
        manifest="$XLORIE_DEBS"; release="$XLORIE_RELEASE_TAG"; url="$XLORIE_BASE_URL"
    fi
    host_manifest="$DESKTOP_LOG_FILE.host-manifest.$$"
    printf '%s\n' "$manifest" > "$host_manifest" || fail 'writing host manifest'
    while read -r name digest; do
        [ -n "$name" ] || continue
        package="${name%%_*}"
        version="${name#*_}"; version="${version%_*}"
        if pinned_package_valid "$package" "$version"; then
            log "Host prerequisite verified: $package $version"
        else
            log "Host prerequisite missing or mismatched: $package $version"
            missing_debs="$missing_debs
$name $digest"
        fi
    done < "$host_manifest"
    standard_packages=''
    if [ ! -x "$PREFIX/bin/pulseaudio" ] || [ ! -x "$PREFIX/bin/pactl" ]; then
        standard_packages="$standard_packages pulseaudio"
    fi
    proot_valid || standard_packages="$standard_packages proot"
    proot_distro_valid || standard_packages="$standard_packages proot-distro"
    if [ -n "$missing_debs" ] || [ -n "$standard_packages" ]; then
        log "Installing explicitly requested host prerequisites only ($DESKTOP_DE/$DESKTOP_COMPONENT_RENDERER)"
        run pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold x11-repo || fail 'x11-repo'
        run pkg update -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold || fail 'pkg update'
        host_paths=''
        if [ -n "$missing_debs" ]; then
            host_paths=$(fetch_debs "$release" "$url" "$missing_debs") || fail 'downloading host prerequisites'
            [ -n "$host_paths" ] || fail 'no host prerequisite packages downloaded'
        fi
        # All paths are app-private, conservative spec paths.
        # --reinstall repairs explicitly requested missing or mismatched prerequisites.
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $host_paths $standard_packages \
            || fail 'host prerequisite apt install'
    else
        log 'Host prerequisites already satisfied; no network or package update required.'
    fi
    while read -r name digest; do
        [ -n "$name" ] || continue
        package="${name%%_*}"
        version="${name#*_}"; version="${version%_*}"
        pinned_package_valid "$package" "$version" || fail "host prerequisite verification failed: $package $version"
    done < "$host_manifest"
    rm -f "$host_manifest"
    proot_valid || fail 'proot verification failed'
    proot_distro_valid || fail 'proot-distro new containers layout verification failed (requires 5.10.3 <= version < 6 and --name)'
    [ -x "$PREFIX/bin/pulseaudio" ] && [ -x "$PREFIX/bin/pactl" ] || fail 'host PulseAudio support is missing'
    audit=$(dpkg --audit 2>&1) || fail 'dpkg --audit failed'
    [ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
    if [ "$DESKTOP_DE" = xfce4 ]; then
        run apt-mark hold xorg-server || fail 'holding xorg-server'
    fi
    if [ "$DESKTOP_DE" = kwin_plasma ]; then
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_initialize.py" prepare-plasma \
            || fail 'preparing Plasma host launcher for restored desktop'
    fi
    log 'Host prerequisites finished; no container was created or modified.'
}

initialize_desktop() {
    # Reapply the APK-matched package after apt dependencies, before validating initialization.
    [ -f "$PREFIX/share/embedded-api/termux-api.deb" ] || fail 'Android API companion package missing; retry runtime initialization'
    run dpkg -i "$PREFIX/share/embedded-api/termux-api.deb" || fail 'Android API companion package installation'
    if dpkg-query -W -f='${Status}' upower 2>/dev/null | grep -q 'install ok installed'; then
        [ -f "$PREFIX/share/embedded-api/upower.deb" ] || fail 'asynchronous UPower component missing'
        run dpkg -i "$PREFIX/share/embedded-api/upower.deb" || fail 'asynchronous UPower installation'
    fi
    # EGL diagnostics are initialization-only and never invoked by the session entrypoints.
    run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold python mesa-demos || fail 'initialization tools'
    set -- --de "$DESKTOP_DE" --run-mode "$DESKTOP_MODE" \
        --host-gl-mode "$DESKTOP_HOST_GL_MODE" --guest-gl-mode "$DESKTOP_GUEST_GL_MODE" \
        --usb-storage "$DESKTOP_USB_STORAGE"
    [ "$DESKTOP_MODE" != proot ] || set -- "$@" --alias "$DESKTOP_PROOT_DISTRO"
    [ "$DESKTOP_DE" != hyprland ] || set -- "$@" --runtime-id "$DESKTOP_HYPRLAND_RUNTIME_ID" --theme-id "$DESKTOP_HYPRLAND_THEME_ID"
    run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_initialize.py" initialize "$@" || fail 'desktop initialization verification'
    initialization_audit=$(dpkg --audit 2>&1) || fail 'initialization dpkg audit'
    [ -z "$initialization_audit" ] || fail "$initialization_audit"
}

if [ "$DESKTOP_DE" = hyprland ]; then
    log "Installing Hyprland Android candidate ($DESKTOP_MODE); device validation pending"
    run pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold x11-repo || fail 'x11-repo'
    run pkg update -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold || fail 'pkg update'
    support="$HYPRLAND_SUPPORT"
    if [ "$DESKTOP_MODE" = native ]; then support="$support thunar"; else support="$support proot proot-distro"; fi
    # shellcheck disable=SC2086
    run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold $support || fail 'Hyprland runtime/client dependencies'
    if run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" check-runtime; then
        log 'Selected Hyprland compositor already verified; preserving its files'
    else
        artifact=$(fetch_debs "$HYPRLAND_RELEASE" "$HYPRLAND_BASE_URL" "$HYPRLAND_ASSET $HYPRLAND_ARCHIVE_SHA256") || fail 'downloading Hyprland component'
        artifact="${artifact# }"
        if ! run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" install "$artifact"; then
            rm -f "$artifact"
            fail 'Hyprland runtime verification/install failed; retry will download the component again'
        fi
    fi
    run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland_components.py" install \
        --runtime "$DESKTOP_HYPRLAND_RUNTIME_ID" --theme "$DESKTOP_HYPRLAND_THEME_ID" || fail 'Hyprland theme verification/install'
    if [ "${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" = 1 ]; then
        # A theme transaction must preserve the previous container and completion receipts.
        # Initialization validates the candidate's clients/renderers without installing a guest.
        initialize_desktop
        log 'Hyprland theme candidate verified; App will commit the selection'
        INSTALL_DONE=1
        exit 0
    fi
    if [ "$DESKTOP_MODE" = proot ]; then
        ensure_container_instance "$DESKTOP_PROOT_DISTRO" debian:trixie
        rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
        [ -f "$rootfs/etc/os-release" ] || fail 'Hyprland container missing'
        grep -q '^VERSION_CODENAME=trixie$' "$rootfs/etc/os-release" || fail 'Hyprland clients require Debian trixie'
        de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-hyprland"
        rm -f "$de_marker" "$de_marker-zink" || fail 'resetting Hyprland client install marker'
        for helper in desktop_container_plasma_apt.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "staging client helper $helper"
        done
        zink_packages=''
        if [ "$DESKTOP_COMPONENT_RENDERER" = zink ]; then
            zink_packages='libvulkan1 vulkan-tools libllvm19 libdrm2 libgbm1 libegl1 libgles2 libgl1-mesa-dri libegl-mesa0 libglx-mesa0 mesa-vulkan-drivers python3 python3-gi gir1.2-gtk-3.0 libepoxy0 mesa-utils-bin'
        fi
        # shellcheck disable=SC2086
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --no-install-recommends xfce4-terminal thunar dbus-x11 adwaita-icon-theme $zink_packages || fail 'Hyprland container clients'
        if [ "$DESKTOP_COMPONENT_RENDERER" = zink ]; then
            ensure_trixie_turnip_icd "$DESKTOP_PROOT_DISTRO" "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.hyprland-mesa-backup"
            run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- python3 -I -c \
                "import gi; gi.require_version('Gtk', '3.0'); from gi.repository import Gtk" || fail 'container GPU test dependencies'
        fi
        run proot-distro login "$DESKTOP_PROOT_DISTRO" -- test -x /usr/bin/xfce4-terminal || fail 'missing container terminal'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" -- test -x /usr/bin/thunar || fail 'missing container file manager'
    else
        [ -x "$PREFIX/bin/xfce4-terminal" ] && [ -x "$PREFIX/bin/thunar" ] || fail 'missing native clients'
    fi
    initialize_desktop
    audit=$(dpkg --audit 2>&1) || fail 'dpkg audit'
    [ -z "$audit" ] || fail "$audit"
    [ "$DESKTOP_MODE" != proot ] || : > "$de_marker" || fail 'writing client installation marker'
    if [ "$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER" = proot:zink ]; then
        printf '%s\n' b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687 > "$de_marker-zink" || fail 'writing experimental Zink identity'
    fi
    log 'Hyprland candidate installed. Runtime and input await manual device validation.'
    INSTALL_DONE=1
    exit 0
fi

if [ "$OPERATION" = host-prerequisites ]; then
    check_host_prerequisites
    INSTALL_DONE=1
    exit 0
fi

log "Installing $DESKTOP_LABEL desktop packages ($DESKTOP_MODE mode)"
run pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold x11-repo || fail "x11-repo"
run pkg update -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold || fail "pkg update"

# fetch_debs runs in a command substitution, so the exit of its fail() only ends that subshell: check
# its status here, or a failed download goes on with an empty package list and "installs" nothing.
DEB_PATHS=""
if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
    DEB_PATHS=$(fetch_debs "$XLORIE_RELEASE_TAG" "$XLORIE_BASE_URL" "$XLORIE_DEBS") || fail "downloading GNOME display packages"
    if [ "$DESKTOP_MODE" = native ]; then
        GNOME_PATHS=$(fetch_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$GNOME_DEBS") || fail "downloading GNOME packages"
        DEB_PATHS="$DEB_PATHS $GNOME_PATHS"
    fi
elif [ "$DESKTOP_DE" = "xfce4" ]; then
    DEB_PATHS=$(fetch_debs "$XLORIE_RELEASE_TAG" "$XLORIE_BASE_URL" "$XLORIE_DEBS") \
        || fail "downloading the $DESKTOP_LABEL packages"
else
    DEB_PATHS=$(fetch_debs "$DESKTOP_RELEASE_TAG" "$BASE_URL" "$DEBS") \
        || fail "downloading the $DESKTOP_LABEL packages"
fi
[ -n "$DEB_PATHS" ] || fail "no $DESKTOP_LABEL packages were downloaded"
if [ "$DESKTOP_MODE:$DESKTOP_DE" = native:ubuntu_gnome ]; then
    native_app_paths=$(fetch_verified_debs "$NATIVE_GNOME_APPS_TAG" "$NATIVE_GNOME_APPS_URL" "$NATIVE_GNOME_APPS_DEBS") || fail 'downloading verified native GNOME applications'
    DEB_PATHS="$DEB_PATHS $native_app_paths"
fi
if [ "$DESKTOP_USB_STORAGE" = read-only ]; then
    USB_PATHS=$(fetch_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$USB_DEBS") || fail 'downloading USB storage bridge'
    DEB_PATHS="$DEB_PATHS $USB_PATHS"
fi
if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
    destination="$PREFIX/libexec/termux-gnome"
    mkdir -p "$destination" || fail 'creating GNOME launcher directory'
    launcher_assets="desktop_gnome_launch.py desktop_usb_storage.py"
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        if [ "$DESKTOP_MODE" = native ]; then
            launcher_assets="$launcher_assets desktop_native_ubuntu.py"
        else
            launcher_assets="$launcher_assets desktop_ubuntu_gnome.py"
        fi
    fi
    for item in $launcher_assets; do
        case "$item" in desktop_gnome_launch.py) target=launch.py ;; *) target="$item" ;; esac
        cp "$(dirname "$0")/$item" "$destination/$target.tmp.$$" && mv "$destination/$target.tmp.$$" "$destination/$target" || fail "installing $item"
    done
fi

if [ "$DESKTOP_MODE" = "native" ]; then
    if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        native_ubuntu_packages=""
        [ "$DESKTOP_DE" != ubuntu_gnome ] || native_ubuntu_packages="gtk3 gnome-desktop3 gnome-terminal gnome-text-editor gnome-calculator eog"
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS pulseaudio python dbus dconf gvfs adwaita-icon-theme gnome-backgrounds xorg-xdpyinfo $native_ubuntu_packages || fail 'apt install GNOME'
    elif [ "$DESKTOP_DE" = "xfce4" ]; then
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS pulseaudio xfce4 xfce4-terminal dbus || fail "apt install xfce4"
    else
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS pulseaudio plasma-desktop plasma-workspace kactivitymanagerd dolphin konsole \
            || fail "apt install"
    fi
else
    # The session itself lives in the container; the host needs the display server and proot.
    # shellcheck disable=SC2086
    host_packages="proot proot-distro pulseaudio"
    if [ "$DESKTOP_DE" = kwin_plasma ]; then
        host_packages="$host_packages mesa mesa-demos"
        if [ "$DESKTOP_HOST_GL_MODE" = zink ]; then
            if [ "$PLASMA_EMULATOR" = 1 ]; then host_packages="$host_packages vulkan-loader mesa-vulkan-icd-swrast"
            else host_packages="$host_packages vulkan-loader mesa-vulkan-icd-freedreno"; fi
        fi
    fi
    if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        host_packages="$host_packages python dbus xorg-xdpyinfo"
        [ "$DESKTOP_HOST_GL_MODE" != zink ] || host_packages="$host_packages mesa mesa-vulkan-icd-freedreno vulkan-loader"
    fi
    run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS $host_packages || fail "apt install"
    # The container's Plasma must be about as new as the native KWin (6.6): Debian trixie's Plasma
    # 6.3 hits protocol mismatches (kde_output_device_mode_v2 events) and shows a black screen, while
    # forky's 6.7 renders. XFCE has no such coupling and stays on the stable image.
    case "$DESKTOP_DE" in
        kwin_plasma) default_base="debian:forky" ;;
        ubuntu_gnome) default_base="ubuntu:noble" ;;
        *) default_base="debian:trixie" ;;
    esac
    ensure_container_instance "$DESKTOP_PROOT_DISTRO" "${DESKTOP_CONTAINER_BASE:-$default_base}"
    rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
    case "$DESKTOP_DE" in
        kwin_plasma) expected_distribution=debian:forky ;;
        ubuntu_gnome) expected_distribution=ubuntu:noble ;;
        *) expected_distribution=debian:trixie ;;
    esac
    [ "${DESKTOP_CONTAINER_BASE:-$default_base}" = "$expected_distribution" ] || fail "desktop base must be $expected_distribution"
    codename=$(sed -n 's/^VERSION_CODENAME=//p' "$rootfs/etc/os-release" | tr -d '\"')
    distro_id=$(sed -n 's/^ID=//p' "$rootfs/etc/os-release" | tr -d '\"')
    [ "$distro_id:$codename" = "$expected_distribution" ] || fail "container requires $expected_distribution, got $distro_id:$codename"
    de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE"
    # The container helper configures service-start policy before apt. Installation failure
    # is returned unchanged; package maintainer scripts are never replaced.
    rm -f "$de_marker" || fail 'resetting the container install marker'
    case "$DESKTOP_DE" in
        xfce4) container_packages="xfce4 xfce4-terminal dbus-x11" ;;
        ubuntu_gnome) container_packages="ubuntu-session ubuntu-settings gjs mesa-utils-bin gnome-shell gnome-settings-daemon nautilus gvfs-backends dbus-x11 libgl1-mesa-dri mesa-utils gnome-terminal gnome-text-editor gnome-control-center gnome-calculator gnome-clocks eog ubuntu-wallpapers ubuntu-wallpapers-noble fonts-ubuntu yaru-theme-gtk yaru-theme-icon yaru-theme-sound yaru-theme-gnome-shell gnome-shell-extension-ubuntu-dock gnome-shell-extension-appindicator gnome-shell-extension-desktop-icons-ng gnome-shell-extension-ubuntu-tiling-assistant" ;;
        gnome) container_packages="gjs mesa-utils-bin gnome-shell gnome-settings-daemon nautilus gvfs-backends gnome-backgrounds adwaita-icon-theme dbus-x11 libgl1-mesa-dri mesa-utils"
            [ "$DESKTOP_COMPONENT_RENDERER" != zink ] || container_packages="$container_packages mesa-vulkan-drivers libvulkan1 vulkan-tools libllvm19 libdrm2"
            ;;
        *) container_packages="qt6-wayland mesa-utils-bin plasma-desktop plasma-workspace kactivitymanagerd dolphin konsole dbus-x11 packagekit polkitd libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2"
           [ "$DESKTOP_COMPONENT_RENDERER" != "zink" ] || container_packages="$container_packages libvulkan-dev mesa-vulkan-drivers" ;;
    esac
    container_apt="$(dirname "$0")/desktop_container_plasma_apt.sh"
    [ -r "$container_apt" ] || fail "missing $container_apt"
    for helper in desktop_container_plasma_apt.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
        cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "cannot stage container helper $helper"
    done
    recommend_option=''
    { [ "$DESKTOP_DE" != gnome ] && [ "$DESKTOP_DE" != ubuntu_gnome ]; } || recommend_option=--no-install-recommends
    [ "$DESKTOP_DE" != ubuntu_gnome ] || recommend_option="--no-install-recommends --reinstall"
    # A prior Turnip overlay can leave Debian EGL linked to an absent LLVM ABI.
    # Reinstall matching distro Mesa on emulators; preserve existing apt hold selections.
    if [ "$PLASMA_EMULATOR" = 1 ]; then
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --reinstall --allow-change-held-packages \
            libgbm1 libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2 mesa-vulkan-drivers \
            || fail 'restoring matching emulator Mesa packages'
    fi
    # shellcheck disable=SC2086
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
        --statx-backend=proc-fdinfo $recommend_option $container_packages || fail "container apt install $DESKTOP_DE"
    # Idempotent and independent of the marker: a container created before this step still gets it.
    [ "$DESKTOP_DE" != "kwin_plasma" ] || [ "$PLASMA_EMULATOR" = 1 ] || [ "$DESKTOP_GUEST_GL_MODE" != "zink" ] || ensure_turnip_icd "$DESKTOP_PROOT_DISTRO"
    [ "$DESKTOP_DE:$DESKTOP_COMPONENT_RENDERER" != gnome:zink ] || ensure_gnome_turnip_icd "$DESKTOP_PROOT_DISTRO"
    if [ "$DESKTOP_USB_STORAGE" = read-only ]; then
        # The guest must load glibc GIO code. Never bind a host Bionic module into it.
        for helper in desktop_container_plasma_apt.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "staging USB container helper $helper"
        done
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --no-install-recommends gvfs-backends libglib2.0-bin || fail 'container GIO prerequisites'
        gio_archive=$(fetch_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$USB_GLIBC_ARTIFACT") || fail 'downloading glibc GIO artifact'
        gio_archive="${gio_archive# }"
        gio_archive=$(readlink -f "$gio_archive") || fail 'resolving GIO archive path'
        canonical_rootfs=$(readlink -f "$rootfs") || fail 'resolving GIO rootfs path'
        run python3 -I -B "$(dirname "$0")/desktop_install_gio.py" --archive "$gio_archive" \
            --rootfs "$canonical_rootfs" --codename trixie --bridge-version 0.4.1 || fail 'installing glibc GIO payloads'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- gio-querymodules \
            /usr/local/lib/termux-usb-bridge/gio/modules || fail 'registering container GIO module'
    fi
fi

audit=$(dpkg --audit 2>&1) || fail 'dpkg --audit failed'
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
if [ "$DESKTOP_DE" = "xfce4" ] || [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
    # The Xlorie xorg-server (release build) is older than Termux's stock one; keep a later
    # `pkg upgrade` from replacing it with a server that has no Xlorie.
    run apt-mark hold xorg-server || fail "holding xorg-server"
fi
verification_manifest="$DEBS"
case "$DESKTOP_DE" in
    xfce4) verification_manifest="$XLORIE_DEBS" ;;
    gnome|ubuntu_gnome) verification_manifest="$XLORIE_DEBS"
        [ "$DESKTOP_MODE" != native ] || verification_manifest="$verification_manifest
$GNOME_DEBS" ;;
esac
[ "$DESKTOP_MODE:$DESKTOP_DE" != native:ubuntu_gnome ] || verification_manifest="$verification_manifest
$NATIVE_GNOME_APPS_DEBS"
[ "$DESKTOP_USB_STORAGE" != read-only ] || verification_manifest="$verification_manifest
$USB_DEBS"
printf '%s\n' "$verification_manifest" > "$DESKTOP_LOG_FILE.verify-manifest.$$" || fail 'writing final verification manifest'
while read -r name digest; do
    [ -n "$name" ] || continue
    package="${name%%_*}"
    version="${name#*_}"; version="${version%_*}"
    pinned_package_valid "$package" "$version" || fail "installed package verification failed: $package $version"
done < "$DESKTOP_LOG_FILE.verify-manifest.$$"
rm -f "$DESKTOP_LOG_FILE.verify-manifest.$$"
if [ "$DESKTOP_MODE" = native ] && { [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; }; then
    # Match the launcher's core D-Bus policy: apt success alone does not prove these exist.
    for service in ca.desrt.dconf org.gtk.vfs.Daemon org.gnome.Nautilus; do
        [ -f "$PREFIX/share/dbus-1/services/$service.service" ] || \
            fail "Required Native GNOME D-Bus service is missing: $service.service. Reinstall the GNOME desktop."
    done
fi
if [ "$DESKTOP_MODE:$DESKTOP_DE" = native:ubuntu_gnome ]; then
    ubuntu_profile=$(fetch_native_ubuntu_profile) || fail 'downloading verified native Ubuntu appearance'
    run python3 -I -B "$(dirname "$0")/desktop_native_ubuntu.py" --prefix "$PREFIX" \
        --install "$ubuntu_profile" || fail 'installing verified native Ubuntu appearance'
elif [ "$DESKTOP_DE" = ubuntu_gnome ]; then
    run python3 -I -B "$(dirname "$0")/desktop_ubuntu_gnome.py" --rootfs "$rootfs" --prepare || {
        validation_code=$?
        rm -f "$de_marker"
        log 'Ubuntu GNOME session/theme/extension verification failed'
        exit "$validation_code"
    }
fi
initialize_desktop
if [ "$DESKTOP_MODE" = proot ]; then
    : > "$de_marker" || fail 'writing the verified container install marker'
fi
log "Desktop install finished."
INSTALL_DONE=1
exit 0
