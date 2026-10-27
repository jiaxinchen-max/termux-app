#!/bin/sh
# Installs the independent Xlorie X server packages for this device's architecture, from the
# manifest x-server-packages.txt. Runs in a visible Termux terminal session.
#
# Spec (KEY=value, sourced): XSERVER_ARCH, XSERVER_BASE_URL, XSERVER_MANIFEST, XSERVER_LOG_FILE

set -u

SPEC_PATH="${1:?Usage: $0 <xserver_install_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
PATH="$PREFIX/bin:$PREFIX/bin/applets:${PATH:-}"
export PREFIX HOME PATH DEBIAN_FRONTEND=noninteractive

mkdir -p "$(dirname "$XSERVER_LOG_FILE")"
log() { printf '%s\n' "$*" | tee -a "$XSERVER_LOG_FILE"; }
fail() { log "Xlorie install failed: $*"; exit 1; }

run() {
    status_file="$XSERVER_LOG_FILE.status.$$"
    rm -f "$status_file"
    (
        "$@"
        printf '%s\n' "$?" > "$status_file"
    ) 2>&1 | tee -a "$XSERVER_LOG_FILE"
    status=$(cat "$status_file" 2>/dev/null || printf '1')
    rm -f "$status_file"
    return "$status"
}

[ -f "$XSERVER_MANIFEST" ] || fail "manifest missing: $XSERVER_MANIFEST"
DEB_DIR="${TMPDIR:-$PREFIX/tmp}/xlorie-debs"
mkdir -p "$DEB_DIR" || fail "cannot create $DEB_DIR"
DEB_PATHS=""
count=0
while read -r arch name digest; do
    case "$arch" in ''|\#*) continue ;; esac
    [ "$arch" = "$XSERVER_ARCH" ] || continue
    count=$((count + 1))
    target="$DEB_DIR/$name"
    if [ -f "$target" ] && [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ]; then
        log "Cached $name"
    else
        run curl -fL --retry 3 -o "$target" "$XSERVER_BASE_URL/$name" || fail "download $name"
        [ "$(sha256sum "$target" | cut -d' ' -f1)" = "$digest" ] || {
            rm -f "$target"
            fail "checksum mismatch for $name"
        }
    fi
    DEB_PATHS="$DEB_PATHS $target"
done < "$XSERVER_MANIFEST"
[ "$count" -gt 0 ] || fail "no Xlorie packages are published for $XSERVER_ARCH yet"

log "Installing the Xlorie X server ($count packages)"
run pkg install -y x11-repo || fail "x11-repo"
run pkg update -y || fail "pkg update"
# shellcheck disable=SC2086
run apt install -y --allow-downgrades $DEB_PATHS || fail "apt install"
run apt-mark hold xorg-server
[ -x "$PREFIX/bin/Xlorie" ] || fail "Xlorie is not installed after apt finished"

audit=$(dpkg --audit 2>&1)
[ -z "$audit" ] || fail "dpkg --audit reported problems: $audit"
log "Xlorie install finished."
exit 0
