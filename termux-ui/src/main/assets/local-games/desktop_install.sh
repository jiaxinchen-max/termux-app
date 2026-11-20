#!/bin/sh
# Installs the KWin hardware-accelerated Plasma packages (and, for proot mode, proot-distro plus
# the container). Runs in a visible Termux terminal session.

set -u

SOFTWARE_CENTER_PACKAGES="gnome-software gnome-software-plugin-deb packagekit appstream apt-config-icons polkitd python3 python3-gi gir1.2-polkit-1.0 zenity"
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

if [ "${DESKTOP_DE:-}" = gnome ]; then
    log 'Independent GNOME is retired; create Ubuntu GNOME instead. Existing records can still be deleted.'
    printf 'exit:64\n' > "$DESKTOP_STATUS_FILE"
    exit 64
fi

INSTALL_DONE=0
finish_operation() {
    code=$?
    trap - EXIT
    [ "$INSTALL_DONE" -eq 1 ] || { [ "$code" -ne 0 ] || code=1; }
    if [ "$OPERATION" = install ]; then
        if [ "$INSTALL_DONE" -ne 1 ] && [ "${DESKTOP_PREPARE_ONLY:-0}" != 1 ] && [ "${DESKTOP_MODE:-}:${DESKTOP_DE:-}" = native:ubuntu_gnome ]; then
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
case "${DESKTOP_REINSTALL:-0}" in 0|1) ;; *) log 'Invalid explicit reinstall flag'; exit 64 ;; esac
case "${DESKTOP_RENDERER_CONFIGURE_ONLY:-0}" in 0|1) ;; *) log 'Invalid renderer configuration flag'; exit 64 ;; esac
if [ "${DESKTOP_RENDERER_CONFIGURE_ONLY:-0}" = 1 ]; then
    [ "$OPERATION:${DESKTOP_PREPARE_ONLY:-0}:${DESKTOP_REINSTALL:-0}:${DESKTOP_USB_CONFIGURE_ONLY:-0}:${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" = install:1:0:0:0 ] || exit 64
fi
REINSTALL_OPTION=''
if [ "${DESKTOP_REINSTALL:-0}" = 1 ]; then
    if [ "$OPERATION" != install ] || [ "${DESKTOP_PREPARE_ONLY:-0}" != 0 ] ||
        [ "${DESKTOP_USB_CONFIGURE_ONLY:-0}" != 0 ] || [ "${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" != 0 ]; then
        log 'Reinstall cannot be combined with preparation, USB, theme switch or host prerequisites'; exit 64
    fi
    REINSTALL_OPTION=--reinstall
fi
. "$(dirname "$0")/desktop_rendering.sh"
desktop_resolve_rendering || { log 'Unsupported host/container renderer pair; choose compatible renderers explicitly.'; exit 64; }
case "${DESKTOP_MODE:-}" in native|proot) ;; *) log 'Explicit desktop run mode required'; exit 64 ;; esac
[ "$OPERATION" != host-prerequisites ] || [ "$DESKTOP_MODE" = proot ] || { log 'Host prerequisites require PROOT mode'; exit 64; }
case "${DESKTOP_DE:-}" in xfce4|kwin_plasma|ubuntu_gnome|hyprland) ;; *) log 'Unsupported desktop environment'; exit 64 ;; esac
case "${DESKTOP_COMPONENT_RENDERER:-}" in system|zink|llvmpipe) ;; *) log 'Explicit system, zink or llvmpipe renderer required'; exit 64 ;; esac
if [ "$DESKTOP_DE" = hyprland ]; then
    [ "${DESKTOP_HYPRLAND_BACKEND:-}:${DESKTOP_HYPRLAND_HOST_GL_MODE:-system}" = android:system ] || {
        log 'Hyprland requires its pinned Android/System EGL host.'; exit 64
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
if [ "$DESKTOP_MODE:${DESKTOP_USB_STORAGE:-off}" = native:read-write ]; then
    log 'USB read-write requires a supported PRoot desktop.'
    exit 64
fi
case "${DESKTOP_USB_STORAGE:-}" in off|read-only|read-write) ;; *) log 'Explicit USB storage mode off|read-only|read-write required'; exit 64 ;; esac
if [ "$DESKTOP_MODE" = native ] && [ "$DESKTOP_USB_STORAGE" != off ] && [ "$DESKTOP_DE" != xfce4 ]; then
    log 'USB storage in this Native desktop is unsupported.'; exit 64
fi

if [ "$DESKTOP_DE" = gnome ]; then
    case "$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER:${DESKTOP_GNOME_BACKEND:-}:${DESKTOP_GNOME_RENDERER:-}" in
        native:system:android:system)
            [ "$DESKTOP_REFRESH_RATE" = 60 ] || { log 'Native GNOME requires 60Hz'; exit 64; }
            [ -z "${DESKTOP_GNOME_DISPLAY_GL_MODE:-}${DESKTOP_GNOME_DISPLAY_BUFFER_MODE:-}" ] || { log 'Native GNOME does not use X11 display parameters'; exit 64; } ;;
        *) log 'Unsupported explicit GNOME backend/renderer combination'; exit 64 ;;
    esac
fi

# Invalidate the previous completion before initialization; failure must leave no success marker.
if [ "$OPERATION" = install ] && [ "${DESKTOP_PREPARE_ONLY:-0}" != 1 ] && [ "${DESKTOP_USB_CONFIGURE_ONLY:-0}" != 1 ] && [ "${DESKTOP_MODE:-}" = proot ] && [ "${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" != 1 ]; then
    rm -f "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE" \
        "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE-zink" || exit 74
fi

# An unsuccessful retry must also invalidate the exact pair's old initialization receipt.
if [ "${DESKTOP_PREPARE_ONLY:-0}" != 1 ] && [ "${DESKTOP_USB_CONFIGURE_ONLY:-0}" != 1 ] && [ "$OPERATION:$DESKTOP_MODE:$DESKTOP_DE" = install:proot:kwin_plasma ]; then
    rm -f "$PREFIX/var/lib/termux-desktop/rendering/$DESKTOP_PROOT_DISTRO.$DESKTOP_HOST_GL_MODE.$DESKTOP_GUEST_GL_MODE.env" || exit 74
fi

PLASMA_EMULATOR=0
if [ "$DESKTOP_MODE:$DESKTOP_DE" = proot:kwin_plasma ] &&
    [ "$(/system/bin/getprop ro.kernel.qemu 2>/dev/null)" = 1 ]; then
    PLASMA_EMULATOR=1
fi

BASE_URL="https://github.com/jiaxinchen-max/kwin/releases/download/$DESKTOP_RELEASE_TAG"
# sha256 of the pinned release assets (GitHub release digests of termux-hwaccel-6.7-r5).
if [ "$DESKTOP_RELEASE_TAG" != "termux-hwaccel-6.7-r5" ]; then
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
kwin_6.7.5-5_aarch64.deb 0ae72b7f2533740865da08b1cab501c6b2b88d5b557d3acf194beaa46035dec4
libei_1.6.0_aarch64.deb be94458e11fe2f0eceec657a67d3da0f2286ff1f92ecc49775a8382c5f1a7b8b
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
# Both modes run the Bionic Shell/Mutter. These dependencies are absent from the
# public apt repositories and must accompany the compositor in PRoot mode too.
GNOME_SHELL_SUPPORT_DEBS="
accountsservice_23.13.9_aarch64.deb 4d1dfa7ae196beba68378d04913a0ab2eb09b105b4e305da27bd97a355fe1f4e
gnome-settings-daemon-schemas_48.1_all.deb 03ed7b7a1f8d184353906f511e66bbf767f32622fb26c280ea896cb3789f9215
libgdm_48.0-1_aarch64.deb fb9d03f4665fd540af125d91784d0d284122b5ee81b53f279705eafdacb63231
polkit_126_aarch64.deb 1eddba206bc72c6b423557ff749416971efdec25be03fca45ad5498b33e98b67
"
GNOME_NATIVE_DEBS="
gexiv2_0.14.7_aarch64.deb 2eea495d80f59b7285f8225df2526cd8a7c06ce03f9abd1ca2d65b9427772221
gnome-autoar_0.4.5_aarch64.deb 3d98ef33d6e48a21af97bc1d03440303106172969ae5fd67bd77ded9348b03f6
localsearch-schemas_3.9.0_all.deb 13a0f641887be3fb7c92034ae485a67bf9a8726a73bed04d8f6f77d3e6f1f2b6
nautilus_48.3-3_aarch64.deb ee53e9a2961294df5516ba0d7e4a3e138e57634ec4b132f37f1ea41c97d26506
pipewire-dbus_1.4.9_aarch64.deb f42dd82ff03801d5c1e14203f78c3092cfdbf0a57add3133c37331afd4b8ac8b
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
USB_GLIBC_ARTIFACT="termux-usb-gio-resolute-aarch64-0.4.1.tar.gz a843a28f1821c1e598d8c653ea0db7e6540d188d42296cc5a305bd4a838982ea"
# Prepared on the original Linux builder; publish this component before releasing an APK.
GNOME_RESOLUTE_TAG="gnome-50-resolute-20261116-r1"
GNOME_RESOLUTE_URL="https://github.com/jiaxinchen-max/termux-app-packages/releases/download/$GNOME_RESOLUTE_TAG"
# Shared Native/PRoot Bionic compositor runtime; no Xlorie display server.
GNOME_RUNTIME_DEBS="
gjs_1.88.0_aarch64.deb 75764e4d0a55fa3ce8cc8240fcea019467d92b1fd38cec9b74b9c8cbc3c23529
gnome-shell_50.1-2_aarch64.deb 37c447a7e282e42c891d2f343d9d3cc506811e487bd3cca2defae3fc5882eb19
mutter_50.1-4_aarch64.deb 294a5de617a3da94aa8c81a486bdcd021eb0d0524e278cea8b10fd0ebd69ac37
spidermonkey_140.17.0-1_aarch64.deb dd60c5d67bbff83038202b796bb2dcc6e9c01acaddae5c1a4ff32f6f35134f9e
libandroid-stub_30-3_aarch64.deb 88c7f4ba78a5eb57eafc2f1102ceea562a156fe7346528ca35c771eb0927fefc
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
"
GNOME_GLYCIN_ARTIFACT="termux-gnome-glycin-resolute-aarch64-2.1.1-r1.tar.gz cb72b99666baacb5cd8b4fd62960f3a54568c49c04a05bd8b5f9280ba8493eb9"

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
        profile_name=native-ubuntu-gnome-50-r1.bin
        profile_digest=5d043a773b503c472743c1902d8347ea8ba6c8f7f88c79868e5f661b26288e0e
        profile_url="https://github.com/jiaxinchen-max/termux-app-packages/releases/download/native-ubuntu-gnome-50-r1/$profile_name"
        profile_cache="${TMPDIR:-$PREFIX/tmp}/desktop-native-ubuntu-gnome-50-r1"
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
RESOLUTE_TURNIP_ARTIFACT="mesa-for-android-container_27.0.0-devel-20261025_ubuntu_resolute_arm64.tar.gz 51aa11a8e7bc8c98bc9e852b260ee2dac99392b4ee8658182e4837c309818faf"
ensure_resolute_turnip_icd() { # alias [backup directory]; never infer a driver from file presence
    mesa_backup_dir="${2:-}"
    rootfs="$PREFIX/var/lib/proot-distro/containers/$1/rootfs"
    archive=$(fetch_debs "$TURNIP_RELEASE_TAG" "https://github.com/$TURNIP_REPO/releases/download/$TURNIP_RELEASE_TAG" "$RESOLUTE_TURNIP_ARTIFACT") || fail 'downloading verified GNOME Zink Mesa/Turnip'
    set -- $archive
    [ "$#" -eq 1 ] || fail 'expected one verified Mesa artifact'
    if [ -n "$mesa_backup_dir" ]; then
        backup_dir="$mesa_backup_dir"
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland_gpu.py" backup-mesa \
            --archive "$1" --rootfs "$rootfs" --destination "$backup_dir" || fail 'backing up container Mesa files'
        [ ! -L "$backup_dir/package-holds-before.txt" ] || fail 'unsafe package holds backup'
        if [ ! -f "$backup_dir/package-holds-before.txt" ]; then
            holds_temporary="$backup_dir/package-holds-before.tmp.$$"
            (set -C; run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- apt-mark showhold > "$holds_temporary") || fail 'recording package holds'
            mv "$holds_temporary" "$backup_dir/package-holds-before.txt" || fail 'saving package holds'
        fi
    fi
    run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_install_mesa.py" \
        --archive "$1" --rootfs "$rootfs" --codename resolute --gl-mode zink || fail 'installing verified Resolute Zink Mesa/Turnip'
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh -eu -c '
        for library in /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so /usr/lib/aarch64-linux-gnu/libgallium-27.0.0-devel.so /usr/lib/aarch64-linux-gnu/libGLX_mesa.so.0; do
            dependencies=$(ldd "$library")
            printf "%s\n" "$dependencies"
            case "$dependencies" in *"not found"*) exit 1 ;; esac
        done' || fail 'resolving container Mesa/Turnip libraries'
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- apt-mark hold \
        mesa-vulkan-drivers libgbm1 libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2 \
        || fail 'holding the explicit container Mesa/Turnip build'
}

# All retained PRoot profiles have already verified ubuntu:resolute here.
ensure_turnip_icd() {
    ensure_resolute_turnip_icd "$1"
}

# Required runtime files, with release digests retained as publication metadata.
# Installation checks package status, version and file presence without digest verification.
HOST_FILES='
libei lib/libei.so a1c1d1479afffd912b2e6cbe5272e7d774270018c0e59c1d699f245e5cd9e09a
libei lib/libei.so.1 a1c1d1479afffd912b2e6cbe5272e7d774270018c0e59c1d699f245e5cd9e09a
libei lib/libei.so.1.6.0 a1c1d1479afffd912b2e6cbe5272e7d774270018c0e59c1d699f245e5cd9e09a
libei lib/libeis.so ce9b4d9760b1ddbd79ed14134ffbd514b6ae5ab22e6c1f911ffb0fb0434a8528
libei lib/libeis.so.1 ce9b4d9760b1ddbd79ed14134ffbd514b6ae5ab22e6c1f911ffb0fb0434a8528
libei lib/libeis.so.1.6.0 ce9b4d9760b1ddbd79ed14134ffbd514b6ae5ab22e6c1f911ffb0fb0434a8528
libandroid-stub lib/libandroid.so 0a9ca6b42e5f786e931b5f97f83c57f671ac59946919cfdb47e94f2ec2d4eee8
nautilus bin/nautilus 8ddf9a4fbc6b19df2fdb70476674a13198470c05bee70e4652edee9119c9bee5
nautilus bin/nautilus-autorun-software e338f23ab98b50d401bf75b43a667286bc526cbfb8e404d836622c4d6520bd0e
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
kwin bin/kwin_wayland 607bdf70293405e79432dc04cddbd4c806339e31928198f2207e47ad1fc32428
kwin bin/start-plasma 366c1300a6b3c255e401b13d358778b2536300a4a92fe96117887ed9431e6de0
kwin bin/kwin-android-rendering-env c00cef0311e4829e8229059837cb8ee177751345856c159294b15494572c42da
kwin bin/kwin-qt-client-renderer-probe 6a3839f89da5717af765622b29cc5a3601997789699dbc78c6722c827b98d5a5
kwin lib/libkwin.so.6.7.5 cd2046ee512ca6597eacb9996dc5bc35dbb9ac02881ff776f9a7d2910743282b
kwin lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so cf16443e4e24941e11a5052f3e182d2bb07df47eecf8b4d29f592501864c02b8
kwin lib/qt6/termux-system-egl/libEGL.so.1 2f20863549ee9c552985f1d9dda6cab4635b43c4cf00288288d962ca264d293b
kwin lib/qt6/termux-system-egl/libEGL.so 2f20863549ee9c552985f1d9dda6cab4635b43c4cf00288288d962ca264d293b
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
gnome-shell bin/gnome-extensions 1c6557a1b2adf4282bd0ab9e3ea6bba47093f3bae5a8615ccbaf0e0c5a686c2e
gnome-shell bin/gnome-shell 0ec8baedfd6a59c0f08d59ff7fe07b2b5010b9f62785e53ab5607275d1482ef1
gnome-shell bin/gnome-shell-extension-tool beb1a2cba645dd835d8c61d75c93e2b16b168e16bc64b1fdcba71d6490a05228
gnome-shell bin/gnome-shell-test-tool 48872d83fee383fcf91adcfdaf386f43ee9006340c58a7e63424d47195a5d2c6
mutter lib/mutter-18/libmutter-android-system-egl.so 605530b424848346698d861fc9e4afc7d12e0bae40967df026aa0b8a3175123f
mutter lib/mutter-18/libvndksupport.so 1b98bb73b720e5ccf0bc9bc3b895d7988c96702991441e9e4573585ebb73fc18
mutter lib/mutter-18/plugins/libdefault.so 12349e9dc59144c01b69694490c717a8b66dac5c5fc52d7f19847f71364ae478
mutter bin/gdctl 9bc58921f17ca26fee12d0c85d728227265d4a1d33f409cb7d2ec87fd625aff2
mutter bin/gnome-service-client 86f76a660deb70307be741551080d207560429e918f85b28d6f22c9a8f6ed960
mutter bin/mutter 4fd6d6cb33d47d03544c3b022b93ad0cf440bf24b41f9a167e6faf9450a25fb1
mutter lib/libmutter-18.so.0.0.0 290dafdc4afaa97b851c4660bdac4860d7796549cdc50a714440b9965fe425a4
mutter lib/mutter-18/Clutter-18.gir dae38f11f411845226d8aea96a9f9ec7196d8c6a3d10b4ad2a530fa358f809d8
mutter lib/mutter-18/Clutter-18.typelib abd1008ab9eeb0c207b35b19e6408de3319f306207e66ad3fb90fbfb432892b7
mutter lib/mutter-18/Cogl-18.gir 36eab14acfb054149e5cf16bc950aaf151a2168427c1fd3d638a69100a837ca0
mutter lib/mutter-18/Cogl-18.typelib 069443364e6896ac91de16ebd05caee1fa5bea1f7adfaf289066564023d5c0b2
mutter lib/mutter-18/Meta-18.gir 04a9efcbc3a0e2f202c22dbbcf4fda8ddd31718ab4ab23325605d4fc6e94fa11
mutter lib/mutter-18/Meta-18.typelib e59ddcff6c032bd32bef46c7fabe1f6b9e11ac3dbac7c8f7b3d88af4b7a12935
mutter lib/mutter-18/Mtk-18.gir 1bc063e8009a2dfde588de9e9259abec98cf39d7957162e16230402ee663b075
mutter lib/mutter-18/Mtk-18.typelib 61fea61c78d3c4442df82ee5393ea7a41d72b91c0b8c5981c1dc17f5f8466fa5
mutter lib/mutter-18/libmutter-clutter-18.so.0.0.0 fd5a08043933bdbaad5c1974687519bf29f70ec3b55a52e4646de8c4cd2e7394
mutter lib/mutter-18/libmutter-cogl-18.so.0.0.0 a57f0abca1ab100d9b392661e14afc914d76903f9b41cc606184d00cced14958
mutter lib/mutter-18/libmutter-mtk-18.so.0.0.0 f6e57d16f73d75ff578d22adeec489d40ec5d357170d4323ed6db4215ccc6a96
gjs bin/gjs aed646e13819f34ebf7bfc25a26c0872cf362fdb847b75284cd228d403f54c9f
gjs bin/gjs-console aed646e13819f34ebf7bfc25a26c0872cf362fdb847b75284cd228d403f54c9f
spidermonkey bin/js140 cb54db3a3216ab0ad899a5b1cc479878114a36593eedcaba2d9d73b73c63f51b
spidermonkey bin/js140-config 845aa444cb89ebd1259674cc7355c9bba236f564bca1825927d8f3bab8f47395
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
register_embedded_host_packages() {
    # The runtime payload has already been verified against the APK inventory.
    # Register it after the LAST apt transaction, including fresh restore preflight.
    embedded_packages=termux-api
    if package_version upower >/dev/null; then
        embedded_packages="$embedded_packages upower"
    fi
    for embedded_package in $embedded_packages; do
        embedded_deb="$PREFIX/share/embedded-api/$embedded_package.deb"
        [ -f "$embedded_deb" ] || fail "custom host package missing: $embedded_package; retry runtime initialization"
        embedded_name=$(dpkg-deb -f "$embedded_deb" Package) || fail 'reading custom package name'
        [ "$embedded_name" = "$embedded_package" ] || fail 'custom host package identity mismatch'
        embedded_version=$(dpkg-deb -f "$embedded_deb" Version) || fail 'reading custom package version'
        [ -n "$embedded_version" ] || fail 'empty custom host package version'
        log "Registering APK-matched host package: $embedded_package $embedded_version"
        run dpkg -i "$embedded_deb" || fail "custom host package registration: $embedded_package"
        installed_embedded_version=$(package_version "$embedded_package") || fail "custom host package not configured: $embedded_package"
        [ "$installed_embedded_version" = "$embedded_version" ] || fail "custom host package version mismatch: $embedded_package"
        run apt-mark hold "$embedded_package" || fail "protecting custom host package: $embedded_package"
    done
}

install_gnome_launchers() {
    destination="$PREFIX/libexec/termux-gnome"
    mkdir -p "$destination" || fail 'creating GNOME launcher directory'
    launcher_assets="desktop_gnome_launch.py desktop_usb_storage.py"
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        if [ "$DESKTOP_MODE" = native ]; then
            launcher_assets="$launcher_assets desktop_native_ubuntu.py"
        else
            launcher_assets="$launcher_assets desktop_ubuntu_gnome.py desktop_install_glycin.py desktop_install_gio.py desktop_software_center.py desktop_container_software_services.py desktop_container_polkit_agent.py"
        fi
    fi
    for item in $launcher_assets; do
        case "$item" in desktop_gnome_launch.py) target=launch.py ;; *) target="$item" ;; esac
        cp "$(dirname "$0")/$item" "$destination/$target.tmp.$$" && mv "$destination/$target.tmp.$$" "$destination/$target" || fail "installing $item"
    done
}

host_manifest_group() {
    printf '%s\n' "$1" | while read -r name digest; do
        [ -n "$name" ] || continue
        printf '%s %s %s %s\n' "$name" "$digest" "$2" "$3"
    done
}

gnome_standard_package_valid() {
    package_version "$1" >/dev/null || return 1
    case "$1" in
        python) [ -x "$PREFIX/bin/python3" ] ;;
        dbus) [ -x "$PREFIX/bin/dbus-daemon" ] && [ -x "$PREFIX/bin/dbus-run-session" ] ;;
    esac
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
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        host_manifest_group "$GNOME_RUNTIME_DEBS" "$GNOME_RESOLUTE_TAG" "$GNOME_RESOLUTE_URL" > "$host_manifest" &&
        host_manifest_group "$GNOME_SHELL_SUPPORT_DEBS" "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" >> "$host_manifest" || fail 'writing GNOME host manifest'
    else
        host_manifest_group "$manifest" "$release" "$url" > "$host_manifest" || fail 'writing host manifest'
    fi
    while read -r name digest release url; do
        [ -n "$name" ] || continue
        package="${name%%_*}"
        version="${name#*_}"; version="${version%_*}"
        if pinned_package_valid "$package" "$version"; then
            log "Host prerequisite verified: $package $version"
        else
            log "Host prerequisite missing or mismatched: $package $version"
            missing_debs="$missing_debs
$name $digest $release $url"
        fi
    done < "$host_manifest"
    standard_packages=''
    if [ ! -x "$PREFIX/bin/pulseaudio" ] || [ ! -x "$PREFIX/bin/pactl" ]; then
        standard_packages="$standard_packages pulseaudio"
    fi
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        for package in python dbus dconf gsettings-desktop-schemas librsvg; do
            gnome_standard_package_valid "$package" || standard_packages="$standard_packages $package"
        done
    fi
    proot_valid || standard_packages="$standard_packages proot"
    proot_distro_valid || standard_packages="$standard_packages proot-distro"
    # Fresh restores do not run initialize_desktop(), which normally installs mesa-demos.
    # Only Mesa host modes execute eglinfo during local restoration initialization.
    if [ "$DESKTOP_HOST_GL_MODE" != system ] && [ ! -x "$PREFIX/bin/eglinfo" ]; then
        standard_packages="$standard_packages mesa-demos"
    fi
    if [ -n "$missing_debs" ] || [ -n "$standard_packages" ]; then
        log "Installing explicitly requested host prerequisites only ($DESKTOP_DE/$DESKTOP_COMPONENT_RENDERER)"
        run pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold x11-repo || fail 'x11-repo'
        run pkg update -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold || fail 'pkg update'
        host_paths=''
        if [ -n "$missing_debs" ]; then
            printf '%s\n' "$missing_debs" > "$host_manifest.missing" || fail 'writing missing host manifest'
            while read -r name digest release url; do
                [ -n "$name" ] || continue
                if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
                    downloaded=$(fetch_verified_debs "$release" "$url" "$name $digest") || fail 'downloading GNOME host prerequisite'
                else
                    downloaded=$(fetch_debs "$release" "$url" "$name $digest") || fail 'downloading host prerequisite'
                fi
                [ -n "$downloaded" ] || fail 'no host prerequisite package downloaded'
                host_paths="$host_paths $downloaded"
            done < "$host_manifest.missing"
            rm -f "$host_manifest.missing"
        fi
        # All paths are app-private, conservative spec paths.
        # --reinstall repairs explicitly requested missing or mismatched prerequisites.
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $host_paths $standard_packages \
            || fail 'host prerequisite apt install'
    else
        log 'Host prerequisites already satisfied; no network or package update required.'
    fi
    while read -r name digest release url; do
        [ -n "$name" ] || continue
        package="${name%%_*}"
        version="${name#*_}"; version="${version%_*}"
        pinned_package_valid "$package" "$version" || fail "host prerequisite verification failed: $package $version"
    done < "$host_manifest"
    rm -f "$host_manifest"
    if [ "$DESKTOP_HOST_GL_MODE" != system ] && [ ! -x "$PREFIX/bin/eglinfo" ]; then
        fail 'host EGL diagnostics are missing (mesa-demos/eglinfo)'
    fi
    proot_valid || fail 'proot verification failed'
    proot_distro_valid || fail 'proot-distro new containers layout verification failed (requires 5.10.3 <= version < 6 and --name)'
    [ -x "$PREFIX/bin/pulseaudio" ] && [ -x "$PREFIX/bin/pactl" ] || fail 'host PulseAudio support is missing'
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        for package in python dbus dconf gsettings-desktop-schemas librsvg; do
            gnome_standard_package_valid "$package" || fail "GNOME host prerequisite missing: $package"
        done
    fi
    register_embedded_host_packages
    audit=$(dpkg --audit 2>&1) || fail 'dpkg --audit failed'
    [ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
    if [ "$DESKTOP_DE" = xfce4 ]; then
        run apt-mark hold xorg-server || fail 'holding xorg-server'
    fi
    if [ "$DESKTOP_DE" = kwin_plasma ]; then
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_initialize.py" prepare-plasma \
            || fail 'preparing Plasma host launcher for restored desktop'
    fi
    [ "$DESKTOP_DE" != ubuntu_gnome ] || install_gnome_launchers
    log 'Host prerequisites finished; no container was created or modified.'
}

ensure_hyprland_guest_shell() {
    for helper in desktop_container_plasma_apt.sh desktop_container_apt_sources.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
        cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail 'staging container desktop helper'
    done
    guest_shell_packages='python3-gi gir1.2-gtk-3.0 gir1.2-gtklayershell-0.1 swaybg xfce4-notifyd librsvg2-common xdg-terminal-exec'
    if [ "$DESKTOP_HYPRLAND_THEME_ID" = m3ww-v1 ]; then
        guest_shell_packages="$guest_shell_packages foot fish starship fontconfig cargo rustc build-essential pkg-config libgtk-3-dev libgtk-layer-shell-dev libdbusmenu-gtk3-dev"
    else
        guest_shell_packages="$guest_shell_packages xfce4-panel xfce4-genmon-plugin"
    fi
    # shellcheck disable=SC2086
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
        --statx-backend=proc-fdinfo --no-install-recommends $REINSTALL_OPTION $guest_shell_packages || fail 'Ubuntu desktop dependencies'
    if [ "$DESKTOP_HYPRLAND_THEME_ID" = m3ww-v1 ]; then
        for helper in desktop_install_eww.py hyprland-eww-Cargo.lock; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail 'staging Ubuntu EWW builder'
        done
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- \
            sh /tmp/desktop_container_run_config.sh --statx-backend=proc-fdinfo python3 /tmp/desktop_install_eww.py || fail 'building Ubuntu EWW'
    fi
    # Resolute GTK uses Glycin; reuse the verified glibc adapter used by Ubuntu GNOME.
    # Only the current PRoot view binds it; distribution libraries are preserved.
    hyprland_rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
    glycin_archive=$(fetch_verified_debs "$GNOME_RESOLUTE_TAG" "$GNOME_RESOLUTE_URL" "$GNOME_GLYCIN_ARTIFACT") || fail 'downloading verified Resolute image decoder'
    glycin_archive=$(readlink -f "${glycin_archive# }") || fail 'resolving image decoder archive'
    run python3 -I -B "$(dirname "$0")/desktop_install_glycin.py" --archive "$glycin_archive" \
        --rootfs "$hyprland_rootfs" || fail 'installing verified Resolute image decoder'
}

ensure_hyprland_host() {
    support="$HYPRLAND_SUPPORT"
    if [ "$DESKTOP_MODE" = proot ]; then support='python dbus pulseaudio'; fi
    if [ "$DESKTOP_MODE" = native ]; then support="$support thunar"; else support="$support proot proot-distro"; fi
    missing_support=0
    for package in $support; do
        package_version "$package" >/dev/null || missing_support=1
    done
    [ -x "$PREFIX/bin/pulseaudio" ] && [ -x "$PREFIX/bin/pactl" ] || missing_support=1
    if [ "$DESKTOP_MODE" = proot ]; then
        proot_valid && proot_distro_valid || missing_support=1
    fi
    if [ "$DESKTOP_MODE" = native ]; then
        "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" check-shell \
            --theme-id "$DESKTOP_HYPRLAND_THEME_ID" >/dev/null 2>&1 || missing_support=1
    fi
    if [ "$missing_support" = 1 ] || [ "${DESKTOP_REINSTALL:-0}" = 1 ]; then
        run pkg install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold x11-repo || fail 'x11-repo'
        run pkg update -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold || fail 'pkg update'
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold $REINSTALL_OPTION $support || fail 'Hyprland runtime/client dependencies'
    else
        log 'Hyprland host dependencies satisfied; no package update required.'
    fi
    if [ "${DESKTOP_REINSTALL:-0}" != 1 ] && run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" check-runtime; then
        log 'Selected Hyprland compositor already verified; preserving its files'
    else
        artifact=$(fetch_debs "$HYPRLAND_RELEASE" "$HYPRLAND_BASE_URL" "$HYPRLAND_ASSET $HYPRLAND_ARCHIVE_SHA256") || fail 'downloading Hyprland component'
        artifact="${artifact# }"
        # REINSTALL_OPTION is either empty or the single validated --reinstall switch.
        # shellcheck disable=SC2086
        if ! run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" install "$artifact" $REINSTALL_OPTION; then
            rm -f "$artifact"
            fail 'Hyprland runtime verification/install failed; retry will download the component again'
        fi
    fi
    if [ "$DESKTOP_MODE" = native ]; then
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland_components.py" install \
        --runtime "$DESKTOP_HYPRLAND_RUNTIME_ID" --theme "$DESKTOP_HYPRLAND_THEME_ID" || fail 'Hyprland theme verification/install'
        run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_hyprland.py" check-shell \
        --theme-id "$DESKTOP_HYPRLAND_THEME_ID" || fail 'Hyprland shell verification'
    fi
    [ -x "$PREFIX/bin/pulseaudio" ] && [ -x "$PREFIX/bin/pactl" ] || fail 'Host audio support missing'
    if [ "$DESKTOP_MODE" = proot ]; then
        proot_valid && proot_distro_valid || fail 'Hyprland PRoot host prerequisites invalid'
    fi
    register_embedded_host_packages
    audit=$(dpkg --audit 2>&1) || fail 'dpkg audit'
    [ -z "$audit" ] || fail "$audit"
}

initialize_desktop() {
    case "$DESKTOP_DE" in
        gnome|ubuntu_gnome)
            if [ "$DESKTOP_MODE" = proot ]; then
                saved_work="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs/home/termux/.local/share/termux-gnome"
            else
                saved_work="$HOME/.termux/gnome-$DESKTOP_ID"
            fi
            rm -f "$saved_work/prepared-session.json" || fail 'invalidating saved GNOME preparation' ;;
        hyprland) rm -f "$HOME/.termux/hyprland-$DESKTOP_ID/prepared-session.json" || fail 'invalidating saved Hyprland preparation' ;;
    esac
    # EGL diagnostics are initialization-only and never invoked by session entrypoints.
    [ "${DESKTOP_PREPARE_ONLY:-0}" = 1 ] || run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold python mesa-demos || fail 'initialization tools'
    [ "${DESKTOP_PREPARE_ONLY:-0}" = 1 ] || register_embedded_host_packages
    set -- --de "$DESKTOP_DE" --run-mode "$DESKTOP_MODE" \
        --host-gl-mode "$DESKTOP_HOST_GL_MODE" --guest-gl-mode "$DESKTOP_GUEST_GL_MODE" \
        --usb-storage "$DESKTOP_USB_STORAGE" --desktop-id "$DESKTOP_ID"
    [ "$DESKTOP_MODE" != proot ] || set -- "$@" --alias "$DESKTOP_PROOT_DISTRO"
    case "$DESKTOP_DE" in gnome|ubuntu_gnome|hyprland)
        ready_suffix=gnome
        [ "$DESKTOP_DE" != hyprland ] || ready_suffix=hyprland
        set -- "$@" --ready-file "$DESKTOP_PID_FILE.$ready_suffix-ready" \
            --size "${DESKTOP_WIDTH}x${DESKTOP_HEIGHT}" --refresh-rate "$DESKTOP_REFRESH_RATE" \
            --render-socket "$DESKTOP_RENDER_SOCKET" ;;
    esac
    [ "$DESKTOP_DE" != hyprland ] || set -- "$@" --runtime-id "$DESKTOP_HYPRLAND_RUNTIME_ID" --theme-id "$DESKTOP_HYPRLAND_THEME_ID"
    if [ "$DESKTOP_MODE" = proot ]; then
        usb_user_config="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs/home/termux/.config"
    else
        usb_user_config="${XDG_CONFIG_HOME:-$HOME/.config}"
    fi
    disable_global_usb_autostart "$usb_user_config" || fail 'disabling global USB bridge autostart'
    prepare_desktop_audio "${DESKTOP_AUDIO_MODE:-}" || fail 'saving host audio configuration'
    initialization_audit=$(dpkg --audit 2>&1) || fail 'initialization dpkg audit'
    [ -z "$initialization_audit" ] || fail "$initialization_audit"
    run "$PREFIX/bin/python3" -I -B "$(dirname "$0")/desktop_initialize.py" initialize "$@" || fail 'desktop initialization verification'
}

install_usb_host() {
    [ "$DESKTOP_USB_STORAGE" != off ] || return 0
    usb_paths=$(fetch_verified_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$USB_DEBS") || fail 'downloading USB storage bridge'
    run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold $usb_paths || fail 'installing USB bridge'
}

install_usb_guest() {
    [ "$DESKTOP_MODE" = proot ] && [ "$DESKTOP_USB_STORAGE" != off ] || return 0
    usb_browser_packages=''
    [ "$DESKTOP_DE" != kwin_plasma ] || usb_browser_packages=thunar
        # The guest must load glibc GIO code. Never bind a host Bionic module into it.
        for helper in desktop_container_plasma_apt.sh desktop_container_apt_sources.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "staging USB container helper $helper"
        done
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --no-install-recommends gvfs-backends libglib2.0-bin $usb_browser_packages || fail 'container GIO prerequisites'
        gio_archive=$(fetch_verified_debs "$GNOME_RESOLUTE_TAG" "$GNOME_RESOLUTE_URL" "$USB_GLIBC_ARTIFACT") || fail 'downloading glibc GIO artifact'
        gio_archive="${gio_archive# }"
        gio_archive=$(readlink -f "$gio_archive") || fail 'resolving GIO archive path'
        canonical_rootfs=$(readlink -f "$rootfs") || fail 'resolving GIO rootfs path'
        run python3 -I -B "$(dirname "$0")/desktop_install_gio.py" --archive "$gio_archive" \
            --rootfs "$canonical_rootfs" --codename resolute --bridge-version 0.4.1 || fail 'installing glibc GIO payloads'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- gio-querymodules \
            /usr/local/lib/termux-usb-bridge/gio/modules || fail 'registering container GIO module'
}

# Renderer changes reuse the installed desktop, without fetching compositor assets,
# refreshing the software catalog, provisioning accounts, or reinstalling packages.
if [ "${DESKTOP_RENDERER_CONFIGURE_ONLY:-0}" = 1 ]; then
    [ "$OPERATION:${DESKTOP_PREPARE_ONLY:-0}:${DESKTOP_REINSTALL:-0}:${DESKTOP_USB_CONFIGURE_ONLY:-0}:${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" = install:1:0:0:0 ] || exit 64
    set -- --de "$DESKTOP_DE" --host-mode "$DESKTOP_HOST_GL_MODE" --guest-mode "$DESKTOP_GUEST_GL_MODE"
    [ "$DESKTOP_MODE" != proot ] || set -- "$@" --alias "$DESKTOP_PROOT_DISTRO"
    renderer_setup="$(dirname "$0")/desktop_renderer_setup.py"
    run "$PREFIX/bin/python3" -I -B "$renderer_setup" dependencies "$@" || fail 'graphics dependencies'
    if [ "$DESKTOP_MODE:$DESKTOP_GUEST_GL_MODE:$PLASMA_EMULATOR" = proot:zink:0 ]; then
        if run "$PREFIX/bin/python3" -I -B "$renderer_setup" probe-guest "$@"; then
            log 'Reusing the working container Zink backend'
        else
            if [ "$DESKTOP_DE" = hyprland ]; then
                ensure_resolute_turnip_icd "$DESKTOP_PROOT_DISTRO" "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.hyprland-mesa-backup"
            else
                ensure_turnip_icd "$DESKTOP_PROOT_DISTRO"
            fi
        fi
    fi
    initialize_desktop
    INSTALL_DONE=1
    exit 0
fi

# Saving an existing desktop prepares it locally. No network, apt or repair.
if [ "${DESKTOP_PREPARE_ONLY:-0}" = 1 ]; then
    [ "$OPERATION" = install ] || exit 64
    initialize_desktop
    INSTALL_DONE=1
    exit 0
fi

# USB settings are prepared as a candidate, without reinstalling a desktop or its catalog.
if [ "${DESKTOP_USB_CONFIGURE_ONLY:-0}" = 1 ]; then
    install_usb_host
    rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
    install_usb_guest
    case "$DESKTOP_DE" in gnome|ubuntu_gnome) install_gnome_launchers ;; esac
    DESKTOP_PREPARE_ONLY=1
    initialize_desktop
    INSTALL_DONE=1
    exit 0
fi

if [ "$DESKTOP_DE" = hyprland ]; then
    log "Installing Hyprland Android candidate ($DESKTOP_MODE); device validation pending"
    ensure_hyprland_host
    if [ "$OPERATION" = host-prerequisites ]; then
        log 'Hyprland host prerequisites finished; container files untouched.'
        INSTALL_DONE=1
        exit 0
    fi
    if [ "${DESKTOP_HYPRLAND_COMPONENT_SWITCH:-0}" = 1 ]; then
        if [ "$DESKTOP_MODE" = proot ]; then ensure_hyprland_guest_shell; fi
        # A theme transaction must preserve the previous container and completion receipts.
        # Initialization validates the candidate's clients/renderers without installing a guest.
        initialize_desktop
        log 'Hyprland theme candidate verified; App will commit the selection'
        INSTALL_DONE=1
        exit 0
    fi
    if [ "$DESKTOP_MODE" = proot ]; then
        ensure_container_instance "$DESKTOP_PROOT_DISTRO" ubuntu:resolute
        rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
        [ -f "$rootfs/etc/os-release" ] || fail 'Hyprland container missing'
        [ "$(sed -n 's/^ID=//p' "$rootfs/etc/os-release" | tr -d '"')":"$(sed -n 's/^VERSION_CODENAME=//p' "$rootfs/etc/os-release" | tr -d '"')" = ubuntu:resolute ] || fail 'Hyprland clients require Ubuntu Resolute'
        . "$(dirname "$0")/desktop_container_apt_sources.sh" || fail 'loading container HTTPS setup'
        bootstrap_ubuntu_ca_certificates "$rootfs" "$PREFIX/etc/tls/cert.pem" || fail 'initializing Hyprland container HTTPS trust'
        de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-hyprland"
        rm -f "$de_marker" "$de_marker-zink" || fail 'resetting Hyprland client install marker'
        for helper in desktop_container_plasma_apt.sh desktop_container_apt_sources.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "staging client helper $helper"
        done
        zink_packages=''
        if [ "$DESKTOP_COMPONENT_RENDERER" = zink ]; then
            zink_packages='libvulkan1 vulkan-tools libllvm21 libdisplay-info3 libsensors5 libdrm2 libgbm1 libegl1 libgles2 libgl1-mesa-dri libegl-mesa0 libglx-mesa0 mesa-vulkan-drivers python3 python3-gi gir1.2-gtk-3.0 libepoxy0 mesa-utils-bin'
        fi
        # shellcheck disable=SC2086
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --no-install-recommends $REINSTALL_OPTION xfce4-terminal thunar dbus-x11 adwaita-icon-theme passwd sudo $SOFTWARE_CENTER_PACKAGES $zink_packages || fail 'Hyprland container desktop'
        ensure_hyprland_guest_shell
        cp "$(dirname "$0")/desktop_container_discover_catalog.sh" "$PREFIX/tmp/desktop_container_discover_catalog.sh" || fail 'staging software catalog helper'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_discover_catalog.sh \
            --statx-backend=proc-fdinfo || fail 'preparing software application catalog'
        if [ "$DESKTOP_COMPONENT_RENDERER" = zink ]; then
            ensure_resolute_turnip_icd "$DESKTOP_PROOT_DISTRO" "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.hyprland-mesa-backup"
            run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- python3 -I -c \
                "import gi; gi.require_version('Gtk', '3.0'); from gi.repository import Gtk" || fail 'container GPU test dependencies'
        fi
        run proot-distro login "$DESKTOP_PROOT_DISTRO" -- test -x /usr/bin/xfce4-terminal || fail 'missing container terminal'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" -- test -x /usr/bin/thunar || fail 'missing container file manager'
    else
        [ -x "$PREFIX/bin/xfce4-terminal" ] && [ -x "$PREFIX/bin/thunar" ] || fail 'missing native clients'
    fi
    if [ "$DESKTOP_MODE" = proot ]; then
        cp "$(dirname "$0")/desktop_container_user.sh" "$PREFIX/tmp/desktop_container_user.sh" || fail 'staging container account setup'
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_user.sh || fail 'creating container user'
    fi
    install_usb_host
    install_usb_guest
    initialize_desktop
    audit=$(dpkg --audit 2>&1) || fail 'dpkg audit'
    [ -z "$audit" ] || fail "$audit"
    [ "$DESKTOP_MODE" != proot ] || : > "$de_marker" || fail 'writing client installation marker'
    if [ "$DESKTOP_MODE:$DESKTOP_COMPONENT_RENDERER" = proot:zink ]; then
        printf '%s\n' 51aa11a8e7bc8c98bc9e852b260ee2dac99392b4ee8658182e4837c309818faf > "$de_marker-zink" || fail 'writing experimental Zink identity'
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
    DEB_PATHS=$(fetch_verified_debs "$GNOME_RESOLUTE_TAG" "$GNOME_RESOLUTE_URL" "$GNOME_RUNTIME_DEBS") || fail 'downloading verified GNOME 50 runtime'
    GNOME_SUPPORT_PATHS=$(fetch_verified_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$GNOME_SHELL_SUPPORT_DEBS") || fail 'downloading verified GNOME Shell dependencies'
    DEB_PATHS="$DEB_PATHS $GNOME_SUPPORT_PATHS"
    if [ "$DESKTOP_MODE" = native ]; then
        GNOME_PATHS=$(fetch_verified_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$GNOME_NATIVE_DEBS") || fail 'downloading verified native GNOME applications and services'
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
if [ "$DESKTOP_USB_STORAGE" != off ]; then
    USB_PATHS=$(fetch_verified_debs "$GNOME_USB_RELEASE_TAG" "$GNOME_USB_BASE_URL" "$USB_DEBS") || fail 'downloading USB storage bridge'
    DEB_PATHS="$DEB_PATHS $USB_PATHS"
fi
if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
    install_gnome_launchers
fi

if [ "$DESKTOP_MODE" = "native" ]; then
    if [ "$DESKTOP_DE" = gnome ] || [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        native_ubuntu_packages=""
        [ "$DESKTOP_DE" != ubuntu_gnome ] || native_ubuntu_packages="gtk3 gnome-desktop3 gnome-terminal gnome-text-editor gnome-calculator eog"
        # shellcheck disable=SC2086
        run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS pulseaudio python dbus dconf gvfs librsvg adwaita-icon-theme gnome-backgrounds $native_ubuntu_packages || fail 'apt install GNOME'
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
        host_packages="$host_packages python dbus dconf gsettings-desktop-schemas librsvg"
        [ "$DESKTOP_HOST_GL_MODE" != zink ] || host_packages="$host_packages mesa mesa-vulkan-icd-freedreno vulkan-loader"
    fi
    run apt install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold --reinstall --allow-downgrades --allow-change-held-packages $DEB_PATHS $host_packages || fail "apt install"
    # Every retained container profile uses the same explicitly selected LTS base.
    default_base="ubuntu:resolute"
    ensure_container_instance "$DESKTOP_PROOT_DISTRO" "${DESKTOP_CONTAINER_BASE:-$default_base}"
    rootfs="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/rootfs"
    expected_distribution=ubuntu:resolute
    [ "${DESKTOP_CONTAINER_BASE:-$default_base}" = "$expected_distribution" ] || fail "desktop base must be $expected_distribution"
    codename=$(sed -n 's/^VERSION_CODENAME=//p' "$rootfs/etc/os-release" | tr -d '\"')
    distro_id=$(sed -n 's/^ID=//p' "$rootfs/etc/os-release" | tr -d '\"')
    [ "$distro_id:$codename" = "$expected_distribution" ] || fail "container requires $expected_distribution, got $distro_id:$codename"
    # Minimal Ubuntu OCI images have no CA package. Seed HTTPS trust before the first update;
    # the container's ca-certificates package takes over normal certificate maintenance.
    . "$(dirname "$0")/desktop_container_apt_sources.sh" || fail 'loading container HTTPS setup'
    bootstrap_ubuntu_ca_certificates "$rootfs" "$PREFIX/etc/tls/cert.pem" || fail 'initializing container HTTPS trust'
    de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE"
    # The container helper configures service-start policy before apt. Installation failure
    # is returned unchanged; package maintainer scripts are never replaced.
    rm -f "$de_marker" || fail 'resetting the container install marker'
    case "$DESKTOP_DE" in
        xfce4) container_packages="xfce4 xfce4-terminal dbus-x11" ;;
        ubuntu_gnome) container_packages="ubuntu-session ubuntu-settings gjs mesa-utils-bin gnome-shell gnome-settings-daemon nautilus gvfs-backends dbus-x11 libgl1-mesa-dri mesa-utils gnome-terminal gnome-text-editor gnome-control-center gnome-calculator gnome-clocks eog ubuntu-wallpapers ubuntu-wallpapers-resolute fonts-ubuntu yaru-theme-gtk yaru-theme-icon yaru-theme-sound yaru-theme-gnome-shell gnome-shell-ubuntu-extensions" ;;
        *) container_packages="kubuntu-settings-desktop python3 qt6-wayland mesa-utils-bin plasma-desktop plasma-workspace kactivitymanagerd dolphin konsole dbus-x11 packagekit appstream polkitd python3-gi gir1.2-polkit-1.0 kdialog libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2"
           [ "$DESKTOP_COMPONENT_RENDERER" != "zink" ] || container_packages="$container_packages libvulkan-dev mesa-vulkan-drivers" ;;
    esac
    container_packages="$container_packages passwd sudo"
    [ "$DESKTOP_DE" = kwin_plasma ] || container_packages="$container_packages $SOFTWARE_CENTER_PACKAGES"
    container_apt="$(dirname "$0")/desktop_container_plasma_apt.sh"
    [ -r "$container_apt" ] || fail "missing $container_apt"
    for helper in desktop_container_plasma_apt.sh desktop_container_apt_sources.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
        cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "cannot stage container helper $helper"
    done
    if [ "$DESKTOP_MODE" = proot ]; then
        cp "$(dirname "$0")/desktop_container_discover_catalog.sh" "$PREFIX/tmp/desktop_container_discover_catalog.sh" || fail 'staging Discover catalog helper'
    fi
    recommend_option=''
    { [ "$DESKTOP_DE" != gnome ] && [ "$DESKTOP_DE" != ubuntu_gnome ]; } || recommend_option=--no-install-recommends
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        recommend_option="--no-install-recommends --reinstall"
    elif [ "${DESKTOP_REINSTALL:-0}" = 1 ]; then
        recommend_option="$recommend_option --reinstall"
    fi
    # A prior Turnip overlay can leave distro EGL linked to an absent LLVM ABI.
    # Reinstall matching distro Mesa on emulators; preserve existing apt hold selections.
    if [ "$PLASMA_EMULATOR" = 1 ]; then
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo --reinstall --allow-change-held-packages \
            libgbm1 libegl-mesa0 libgl1-mesa-dri libglx-mesa0 libgles2 mesa-vulkan-drivers \
            || fail 'restoring matching emulator Mesa packages'
    fi
    # shellcheck disable=SC2086
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
        --statx-backend=proc-fdinfo $recommend_option $container_packages || fail "container apt install $DESKTOP_DE"
    if [ "$DESKTOP_MODE" = proot ]; then
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_discover_catalog.sh \
            --statx-backend=proc-fdinfo || fail 'preparing Discover application catalog'
    fi
    cp "$(dirname "$0")/desktop_container_user.sh" "$PREFIX/tmp/desktop_container_user.sh" || fail 'staging container account setup'
    run proot-distro login "$DESKTOP_PROOT_DISTRO" --user root --shared-tmp -- sh /tmp/desktop_container_user.sh || fail 'creating container user'
    if [ "$DESKTOP_DE" = ubuntu_gnome ]; then
        glycin_archive=$(fetch_verified_debs "$GNOME_RESOLUTE_TAG" "$GNOME_RESOLUTE_URL" "$GNOME_GLYCIN_ARTIFACT") || fail 'downloading verified Resolute image decoder'
        glycin_archive=$(readlink -f "${glycin_archive# }") || fail 'resolving image decoder archive'
        canonical_rootfs=$(readlink -f "$rootfs") || fail 'resolving image decoder rootfs'
        run python3 -I -B "$(dirname "$0")/desktop_install_glycin.py" --archive "$glycin_archive" \
            --rootfs "$canonical_rootfs" || fail 'installing verified Resolute image decoder'
    fi
    # Idempotent and independent of the marker: a container created before this step still gets it.
    [ "$DESKTOP_DE" != "kwin_plasma" ] || [ "$PLASMA_EMULATOR" = 1 ] || [ "$DESKTOP_GUEST_GL_MODE" != "zink" ] || ensure_turnip_icd "$DESKTOP_PROOT_DISTRO"
    install_usb_guest
fi

audit=$(dpkg --audit 2>&1) || fail 'dpkg --audit failed'
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
if [ "$DESKTOP_DE" = "xfce4" ]; then
    # The Xlorie xorg-server (release build) is older than Termux's stock one; keep a later
    # `pkg upgrade` from replacing it with a server that has no Xlorie.
    run apt-mark hold xorg-server || fail "holding xorg-server"
fi
verification_manifest="$DEBS"
case "$DESKTOP_DE" in
    xfce4) verification_manifest="$XLORIE_DEBS" ;;
    gnome|ubuntu_gnome) verification_manifest="$GNOME_RUNTIME_DEBS
$GNOME_SHELL_SUPPORT_DEBS"
        [ "$DESKTOP_MODE" != native ] || verification_manifest="$verification_manifest
$GNOME_NATIVE_DEBS" ;;
esac
[ "$DESKTOP_MODE:$DESKTOP_DE" != native:ubuntu_gnome ] || verification_manifest="$verification_manifest
$NATIVE_GNOME_APPS_DEBS"
[ "$DESKTOP_USB_STORAGE" = off ] || verification_manifest="$verification_manifest
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
