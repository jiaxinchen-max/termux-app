#!/bin/sh
# Installs the KWin hardware-accelerated Plasma packages (and, for proot mode, proot-distro plus
# the container). Runs in a visible Termux terminal session.

set -u

SPEC_PATH="${1:?Usage: $0 <desktop_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"
. "$(dirname "$0")/desktop_common.sh"

BASE_URL="https://github.com/jiaxinchen-max/kwin/releases/download/$DESKTOP_RELEASE_TAG"
# sha256 of the pinned release assets (GitHub release digests of termux-hwaccel-6.6-r19).
if [ "$DESKTOP_RELEASE_TAG" != "termux-hwaccel-6.6-r19" ]; then
    log "Unsupported desktop release $DESKTOP_RELEASE_TAG"
    exit 65
fi
DEBS="
kwin_6.6.0-19_aarch64.deb f92505e71cdf4c8b849fb9350532eae4fdf18200f0cfb66edb58d352afc1cd42
termux-render_0.1.0-1_aarch64.deb 4dc75a1aed887a59790f03a8c76948de514678fd7c8a00fb04fbc2975a0ac0d6
libepoxy_1.5.10-6_aarch64.deb c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83
xwayland_24.1.11-1_aarch64.deb 3cc2999ae0ba90495abad71445f794723efc06a97c001b383467665e3038e8a4
gl4es_1.1.7.20260924_aarch64.deb d502baa75516a23fae83e507745c23ea1801d116f44f02b8775698aa6cf17ef4
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

DEB_DIR="${TMPDIR:-$PREFIX/tmp}/desktop-debs-$DESKTOP_RELEASE_TAG"
mkdir -p "$DEB_DIR" || fail "cannot create $DEB_DIR"
DEB_PATHS=""
printf '%s\n' "$DEBS" | while read -r name digest; do
    [ -n "$name" ] || continue
    printf '%s %s\n' "$name" "$digest"
done > "$DEB_DIR/manifest"
while read -r name digest; do
    target="$DEB_DIR/$name"
    if [ -f "$target" ] && [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ]; then
        log "Cached $name"
    else
        run curl -fL --retry 3 -o "$target" "$BASE_URL/$name" || fail "download $name"
        [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ] || {
            rm -f "$target"
            fail "checksum mismatch for $name"
        }
    fi
    DEB_PATHS="$DEB_PATHS $target"
done < "$DEB_DIR/manifest"

if [ "$DESKTOP_MODE" = "native" ]; then
    # shellcheck disable=SC2086
    run apt install -y $DEB_PATHS plasma-desktop plasma-workspace kactivitymanagerd \
        || fail "apt install"
else
    # Plasma itself lives in the container; the host only needs KWin and the render bridge.
    # shellcheck disable=SC2086
    run apt install -y $DEB_PATHS proot proot-distro || fail "apt install"
    # proot-distro 4+ keeps containers in containers/, older releases in installed-rootfs/.
    if [ ! -d "$PREFIX/var/lib/proot-distro/containers/$DESKTOP_PROOT_DISTRO" ] \
        && [ ! -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$DESKTOP_PROOT_DISTRO" ]; then
        run proot-distro install "$DESKTOP_PROOT_DISTRO" || fail "proot-distro install"
    fi
fi

audit=$(dpkg --audit 2>&1)
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
log "Desktop install finished."
exit 0
