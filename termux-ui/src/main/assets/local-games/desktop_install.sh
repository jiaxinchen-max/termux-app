#!/bin/sh
# Installs the KWin hardware-accelerated Plasma packages (and, for proot mode, proot-distro plus
# the container). Runs in a visible Termux terminal session.

set -u

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
case "${DESKTOP_MODE:-}" in native|proot) ;; *) log 'Explicit desktop run mode required'; exit 64 ;; esac
[ "$OPERATION" != host-prerequisites ] || [ "$DESKTOP_MODE" = proot ] || { log 'Host prerequisites require PROOT mode'; exit 64; }
case "${DESKTOP_DE:-}" in xfce4|kwin_plasma) ;; *) log 'Unsupported desktop environment'; exit 64 ;; esac
case "${DESKTOP_GL_MODE:-}" in system|zink|llvmpipe) ;; *) log 'Explicit system, zink or llvmpipe renderer required'; exit 64 ;; esac

BASE_URL="https://github.com/jiaxinchen-max/kwin/releases/download/$DESKTOP_RELEASE_TAG"
# sha256 of the pinned release assets (GitHub release digests of termux-hwaccel-6.6-r27).
if [ "$DESKTOP_RELEASE_TAG" != "termux-hwaccel-6.6-r27" ]; then
    log "Unsupported desktop release $DESKTOP_RELEASE_TAG"
    exit 65
fi
case "$DESKTOP_DE" in
    xfce4) DESKTOP_LABEL="XFCE4" ;;
    kwin_plasma) DESKTOP_LABEL="KWin Plasma" ;;
    *) DESKTOP_LABEL="$DESKTOP_DE" ;;
esac

# Xlorie (independent X server) packages, pinned to the xserver release that carries the
# termux-render fixes; needed by every XFCE4 desktop and shared with the KWin ones.
XLORIE_RELEASE_TAG="termux-xlorie-21.1.16-r7"
XLORIE_BASE_URL="https://github.com/jiaxinchen-max/xserver/releases/download/$XLORIE_RELEASE_TAG"
XLORIE_DEBS="
xorg-server_21.1.16-7_aarch64.deb 3b50befbb214617a8a210696905553b8676510a8b6fe4f8cd923807d365c8425
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
libxshmfence_1.3.3-2_aarch64.deb be95caf500b002fed8b5c0142ad360efbdec4ff1334be5565e9b9628f8663dc2
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
"

DEBS="
gl4es_1.1.7.20260927_aarch64.deb eb0ba70d084c87b84b144ef2ce6ad415ca3810dcb356d77e4e8cefe962157913
kwin_6.6.0-27_aarch64.deb b5c4ccd7983849eea8008cb5d61740c90ab834d3fa23730385e0a01dafe8ecf3
libandroid-stub_30-1_aarch64.deb 5b392496146309b8454780821591dccb7e6a7050f8789f3bc97b4bb6c2b6bc67
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
qt6-qtbase-cross-tools_6.12.0-1+termuxkwin1_all.deb 07b9a9ae857225a814e85c74fdc613cc0720c328d3b2f4f5270a47b67169bf5f
qt6-qtbase-gtk-platformtheme_6.12.0-1+termuxkwin1_aarch64.deb c682bf46ff7251449f41c420fe7818b2ff4f91a21477266fc458bf7d86795bb5
qt6-qtbase_6.12.0-1+termuxkwin1_aarch64.deb 5b1507ff7c945c284e9230138752ccf2b63119cf03c4d782714aa8b3a6e91262
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
xwayland_24.1.11-1_aarch64.deb 3cc2999ae0ba90495abad71445f794723efc06a97c001b383467665e3038e8a4
"

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
        if [ -f "$target" ] && [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ]; then
            # stdout of this function is the deb path list; logs go to the file only.
            printf '%s\n' "Cached $name" >> "$DESKTOP_LOG_FILE"
        else
            { curl -fL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 30 -o "$target" "$base_url/$name" || fail "download $name"; } \
                >> "$DESKTOP_LOG_FILE" 2>&1
            [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ] || {
                rm -f "$target"
                fail "checksum mismatch for $name"
            }
        fi
        paths="$paths $target"
    done < "$deb_dir/manifest"
    printf '%s' "$paths"
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

# Fixed host-package identity plus dpkg file verification. Critical runtime files are also
# checked against the published payload, so same-version alternate local builds are rejected.
HOST_FILES='
kwin bin/kwin_wayland 8ccb4e3bd40ca9dfd9426e49056fcd8167dca54900ac01c54fa33775844bbd36
kwin bin/start-plasma 366c1300a6b3c255e401b13d358778b2536300a4a92fe96117887ed9431e6de0
kwin bin/kwin-android-rendering-env c00cef0311e4829e8229059837cb8ee177751345856c159294b15494572c42da
kwin bin/kwin-qt-client-renderer-probe 2f56beabb6caeea4e0d44f9300227bf02d14c9137326868f7c7daabc16d3c3bb
kwin lib/libkwin.so.6.6.7 eee1bfc582e045ec44d8a9216ff787e435a8409a3ce4afbb0cd2114f131c4c87
kwin lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so ad6533bb6a2ddbb7bc1333d60cfb262261454b6650ee105c04a0f31eddccdf69
kwin lib/qt6/termux-system-egl/libEGL.so.1 d5e14819eff485aef4cb3373a713ff6c2eb6da8de9fe976ae7874990295b77d1
kwin lib/qt6/termux-system-egl/libEGL.so d5e14819eff485aef4cb3373a713ff6c2eb6da8de9fe976ae7874990295b77d1
qt6-qtbase lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so 76d7ddc767cf70fc340f57b84f2fdacd88ca7be15c46ec0286a882d0a0bf397b
qt6-qtbase-gtk-platformtheme lib/qt6/plugins/platformthemes/libqgtk3.so fec799f8ce9f9a65522ad6be1118d6bc7064da699bd950bfc6256e353c621733
qt6-qtbase-cross-tools opt/qt6/cross/lib/qt6/moc 7bdd400ea3e8eb2d888774f92505894502fa45828ba6531cd4ba898a1dc555e5
gl4es lib/gl4es/libGL.so.1 4cef5efd53f4ba96447bcefdc755a1fc466d9c3031df69536c59287c83ff5813
libandroid-stub lib/libtermux-platform-ns.so 6c39c6a3318accd5e34f4f2c9e5c9e14eed113fb3a92e8c0be86006dc9256e34
libepoxy lib/libepoxy.so aa2b42ea59a8e9fd7769f29d5480cee0808aa14d543c5110116149affcf331b2
termux-render lib/libtermux-render.so e233a5479d8f353eb83181d98e0eb82fbcd9bb5ec7ec873167c96d998cc53ddc
xwayland bin/Xwayland 7475e64912162047442e6de9f96213a21bfc38ca2ce902b06a609869a28a4476
xorg-server bin/Xlorie 741e9b87cbf01ea48e2db89648bd2fd1441b6df2ddadf70ebdf05793f4544ef6
libxshmfence lib/libxshmfence.so 1ccd07bab7c2d89a8ae37bc97254a2022e8a49fe131aede93c80ecf6c6173c3d
'
package_version() {
    package_status=$(dpkg-query -W -f='${Status}' "$1" 2>/dev/null) || return 1
    [ "$package_status" = 'install ok installed' ] || return 1
    dpkg-query -W -f='${Version}' "$1" 2>/dev/null
}
package_files_valid() {
    verification=$(dpkg --verify "$1" 2>&1) || return 1
    [ -z "$verification" ] || return 1
}
pinned_package_valid() { # package version
    current=$(package_version "$1") || return 1
    [ "$current" = "$2" ] || return 1
    package_files_valid "$1" || return 1
    printf '%s\n' "$HOST_FILES" | while read -r package relative digest; do
        [ "$package" = "$1" ] || continue
        [ -f "$PREFIX/$relative" ] || exit 1
        actual=$(sha256sum "$PREFIX/$relative") || exit 1
        [ "${actual%% *}" = "$digest" ] || exit 1
    done
}
proot_valid() {
    current=$(package_version proot) || return 1
    dpkg --compare-versions "$current" ge 5.1.107 || return 1
    [ -x "$PREFIX/bin/proot" ] && package_files_valid proot
}
proot_distro_valid() {
    current=$(package_version proot-distro) || return 1
    # The archive manager uses the 5.10 containers/<alias>/rootfs contract only.
    # Unknown future layout contracts are rejected, rather than guessed.
    dpkg --compare-versions "$current" ge 5.10.3 || return 1
    dpkg --compare-versions "$current" lt 6 || return 1
    [ -x "$PREFIX/bin/proot-distro" ] || return 1
    package_files_valid proot-distro || return 1
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
    proot_valid || standard_packages="$standard_packages proot"
    proot_distro_valid || standard_packages="$standard_packages proot-distro"
    if [ -n "$missing_debs" ] || [ -n "$standard_packages" ]; then
        log "Installing explicitly requested host prerequisites only ($DESKTOP_DE/$DESKTOP_GL_MODE)"
        run pkg install -y x11-repo || fail 'x11-repo'
        run pkg update -y || fail 'pkg update'
        host_paths=''
        if [ -n "$missing_debs" ]; then
            host_paths=$(fetch_debs "$release" "$url" "$missing_debs") || fail 'downloading host prerequisites'
            [ -n "$host_paths" ] || fail 'no host prerequisite packages downloaded'
        fi
        # All paths are app-private, conservative spec paths; each deb SHA is checked above.
        # --reinstall repairs an altered same-version package instead of calling it satisfied.
        # shellcheck disable=SC2086
        run apt install -y --reinstall --allow-downgrades --allow-change-held-packages $host_paths $standard_packages \
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
    audit=$(dpkg --audit 2>&1) || fail 'dpkg --audit failed'
    [ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
    if [ "$DESKTOP_DE" = xfce4 ]; then
        run apt-mark hold xorg-server || fail 'holding xorg-server'
    fi
    log 'Host prerequisites finished; no container was created or modified.'
}

if [ "$OPERATION" = host-prerequisites ]; then
    check_host_prerequisites
    INSTALL_DONE=1
    exit 0
fi

log "Installing $DESKTOP_LABEL desktop packages ($DESKTOP_MODE mode)"
run pkg install -y x11-repo || fail "x11-repo"
run pkg update -y || fail "pkg update"

# fetch_debs runs in a command substitution, so the exit of its fail() only ends that subshell: check
# its status here, or a failed download goes on with an empty package list and "installs" nothing.
DEB_PATHS=""
if [ "$DESKTOP_DE" = "xfce4" ]; then
    DEB_PATHS=$(fetch_debs "$XLORIE_RELEASE_TAG" "$XLORIE_BASE_URL" "$XLORIE_DEBS") \
        || fail "downloading the $DESKTOP_LABEL packages"
else
    DEB_PATHS=$(fetch_debs "$DESKTOP_RELEASE_TAG" "$BASE_URL" "$DEBS") \
        || fail "downloading the $DESKTOP_LABEL packages"
fi
[ -n "$DEB_PATHS" ] || fail "no $DESKTOP_LABEL packages were downloaded"

if [ "$DESKTOP_MODE" = "native" ]; then
    if [ "$DESKTOP_DE" = "xfce4" ]; then
        # shellcheck disable=SC2086
        run apt install -y --allow-downgrades --allow-change-held-packages $DEB_PATHS xfce4 xfce4-terminal dbus || fail "apt install xfce4"
    else
        # shellcheck disable=SC2086
        run apt install -y --allow-downgrades --allow-change-held-packages $DEB_PATHS plasma-desktop plasma-workspace kactivitymanagerd \
            || fail "apt install"
    fi
else
    # The session itself lives in the container; the host needs the display server and proot.
    # shellcheck disable=SC2086
    run apt install -y --allow-downgrades --allow-change-held-packages $DEB_PATHS proot proot-distro || fail "apt install"
    # The container's Plasma must be about as new as the native KWin (6.6): Debian trixie's Plasma
    # 6.3 hits protocol mismatches (kde_output_device_mode_v2 events) and shows a black screen, while
    # forky's 6.7 renders. XFCE has no such coupling and stays on the stable image.
    case "$DESKTOP_DE" in
        kwin_plasma) default_base="debian:forky" ;;
        *) default_base="debian" ;;
    esac
    ensure_container_instance "$DESKTOP_PROOT_DISTRO" "${DESKTOP_CONTAINER_BASE:-$default_base}"
    de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE"
    if [ -f "$de_marker" ]; then
        log "Container packages for $DESKTOP_DE already present; skipping"
    else
        # The container helper configures service-start policy before apt. Installation failure
        # is returned unchanged; package maintainer scripts are never replaced.
        case "$DESKTOP_DE" in
            xfce4) container_packages="xfce4 xfce4-terminal dbus-x11" ;;
            *) container_packages="plasma-desktop plasma-workspace kactivitymanagerd dbus-x11 packagekit polkitd"
               [ "$DESKTOP_GL_MODE" != "zink" ] || container_packages="$container_packages libvulkan-dev" ;;
        esac
        container_apt="$(dirname "$0")/desktop_container_plasma_apt.sh"
        [ -r "$container_apt" ] || fail "missing $container_apt"
        for helper in desktop_container_plasma_apt.sh desktop_container_statx_setup.sh desktop_container_run_config.sh desktop_container_statx_aarch64.bin; do
            cp "$(dirname "$0")/$helper" "$PREFIX/tmp/$helper" || fail "cannot stage container helper $helper"
        done
        # shellcheck disable=SC2086
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh /tmp/desktop_container_plasma_apt.sh \
            --statx-backend=proc-fdinfo $container_packages || fail "container apt install $DESKTOP_DE"
        : > "$de_marker"
    fi
    # Idempotent and independent of the marker: a container created before this step still gets it.
    [ "$DESKTOP_DE" != "kwin_plasma" ] || [ "$DESKTOP_GL_MODE" != "zink" ] || ensure_turnip_icd "$DESKTOP_PROOT_DISTRO"
fi

audit=$(dpkg --audit 2>&1)
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
if [ "$DESKTOP_DE" = "xfce4" ]; then
    # The Xlorie xorg-server (release build) is older than Termux's stock one; keep a later
    # `pkg upgrade` from replacing it with a server that has no Xlorie.
    run apt-mark hold xorg-server || fail "holding xorg-server"
fi
log "Desktop install finished."
INSTALL_DONE=1
exit 0
