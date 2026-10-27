#!/bin/sh
# Installs the KWin hardware-accelerated Plasma packages (and, for proot mode, proot-distro plus
# the container). Runs in a visible Termux terminal session.

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

BASE_URL="https://github.com/jiaxinchen-max/kwin/releases/download/$DESKTOP_RELEASE_TAG"
# sha256 of the pinned release assets (GitHub release digests of termux-hwaccel-6.6-r22).
if [ "$DESKTOP_RELEASE_TAG" != "termux-hwaccel-6.6-r22" ]; then
    log "Unsupported desktop release $DESKTOP_RELEASE_TAG"
    exit 65
fi
DESKTOP_DE="${DESKTOP_DE:-kwin_plasma}"

# Xlorie (independent X server) packages, pinned to the xserver release that carries the
# termux-render fixes; needed by every XFCE4 desktop and shared with the KWin ones.
XLORIE_RELEASE_TAG="termux-xlorie-21.1.16-r6"
XLORIE_BASE_URL="https://github.com/jiaxinchen-max/xserver/releases/download/$XLORIE_RELEASE_TAG"
XLORIE_DEBS="
xorg-server_21.1.16-6_aarch64.deb 9533f812ea464987cf66bf2aa89deb7e2862e289ae5a14a9ca3d6a48b7255016
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
libxshmfence_1.3.3-2_aarch64.deb be95caf500b002fed8b5c0142ad360efbdec4ff1334be5565e9b9628f8663dc2
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
"

DEBS="
kwin_6.6.0-22_aarch64.deb 6cb14a59c5301b73fae340b80b3050ba68e7d6f99eeb7c5fa70a6bdff279b635
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
xwayland_24.1.11-1_aarch64.deb 3cc2999ae0ba90495abad71445f794723efc06a97c001b383467665e3038e8a4
gl4es_1.1.7.20260927_aarch64.deb eb0ba70d084c87b84b144ef2ce6ad415ca3810dcb356d77e4e8cefe962157913
"

run() {
    status_file="$DESKTOP_LOG_FILE.status.$$"
    rm -f "$status_file"
    (
        "$@"
        printf '%s\n' "$?" > "$status_file"
    ) 2>&1 | tee -a "$DESKTOP_LOG_FILE"
    status=$(cat "$status_file" 2>/dev/null || printf '1')
    rm -f "$status_file"
    return "$status"
}

fail() {
    log "Desktop install failed: $*"
    exit 1
}

if [ "$(uname -m)" != "aarch64" ]; then
    fail "KWin packages are only built for aarch64"
fi

log "Installing KWin desktop packages ($DESKTOP_RELEASE_TAG, $DESKTOP_MODE mode)"
run pkg install -y x11-repo || fail "x11-repo"
run pkg update -y || fail "pkg update"

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
            { curl -fL --retry 3 -o "$target" "$base_url/$name" || fail "download $name"; } \
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

DEB_PATHS=""
if [ "$DESKTOP_DE" = "xfce4" ]; then
    DEB_PATHS=$(fetch_debs "$XLORIE_RELEASE_TAG" "$XLORIE_BASE_URL" "$XLORIE_DEBS")
else
    DEB_PATHS=$(fetch_debs "$DESKTOP_RELEASE_TAG" "$BASE_URL" "$DEBS")
fi

if [ "$DESKTOP_MODE" = "native" ]; then
    if [ "$DESKTOP_DE" = "xfce4" ]; then
        # shellcheck disable=SC2086
        run apt install -y --allow-downgrades $DEB_PATHS xfce4 xfce4-terminal dbus || fail "apt install xfce4"
    else
        # shellcheck disable=SC2086
        run apt install -y --allow-downgrades $DEB_PATHS plasma-desktop plasma-workspace kactivitymanagerd \
            || fail "apt install"
    fi
else
    # The session itself lives in the container; the host needs the display server and proot.
    # shellcheck disable=SC2086
    run apt install -y --allow-downgrades $DEB_PATHS proot proot-distro || fail "apt install"
    ensure_container_instance "$DESKTOP_PROOT_DISTRO" "${DESKTOP_CONTAINER_BASE:-debian}"
    de_marker="$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO/.desktop-de-$DESKTOP_DE"
    if [ -f "$de_marker" ]; then
        log "Container packages for $DESKTOP_DE already present; skipping"
    elif [ "$DESKTOP_DE" = "xfce4" ]; then
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh -c \
            "export DEBIAN_FRONTEND=noninteractive; apt update && apt install -y xfce4 xfce4-terminal dbus-x11" \
            || fail "container apt install xfce4"
        : > "$de_marker"
    else
        run proot-distro login "$DESKTOP_PROOT_DISTRO" --shared-tmp -- sh -c \
            "export DEBIAN_FRONTEND=noninteractive; apt update && apt install -y plasma-desktop plasma-workspace kactivitymanagerd" \
            || fail "container apt install plasma"
        : > "$de_marker"
    fi
fi

audit=$(dpkg --audit 2>&1)
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
if [ "$DESKTOP_DE" = "xfce4" ]; then
    # The Xlorie xorg-server (release build) is older than Termux's stock one; keep a later
    # `pkg upgrade` from replacing it with a server that has no Xlorie.
    run apt-mark hold xorg-server
fi
log "Desktop install finished."
exit 0
