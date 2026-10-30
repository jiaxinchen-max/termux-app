#!/bin/sh
# Install the requested container packages once. A failed maintainer script remains intact and
# its error is returned; never rewrite dpkg scripts or silently repair package state after failure.
set -u
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

case "${1:-}" in --statx-backend=proc-fdinfo) shift ;;
    *) echo "ERROR: explicitly select --statx-backend=proc-fdinfo" >&2; exit 64 ;;
esac
STATX_SETUP="$(dirname "$0")/desktop_container_statx_setup.sh"
[ -r "$STATX_SETUP" ] || { echo "ERROR: proot statx setup asset is missing"; exit 66; }
. "$STATX_SETUP"
configure_proot_statx proc-fdinfo "$(dirname "$0")/desktop_container_statx_aarch64.bin" "$(dirname "$0")/desktop_container_run_config.sh" || exit "$?"

# Keep dpkg from trying to start services.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d || exit $?

# dbus's postinst needs messagebus before its ownership checks; keep its real maintainer script.
getent group messagebus >/dev/null 2>&1 || groupadd --system messagebus || exit $?
getent passwd messagebus >/dev/null 2>&1 || \
    useradd --system --gid messagebus --home-dir /nonexistent --no-create-home \
        --shell /usr/sbin/nologin messagebus || exit $?

run_proot_config proc-fdinfo apt-get update
code=$?
[ "$code" -eq 0 ] || exit "$code"
run_proot_config proc-fdinfo apt-get install -y --no-remove -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef "$@"
code=$?
if [ "$code" -ne 0 ]; then
    echo "ERROR: container apt install failed (exit $code): $*" >&2
    exit "$code"
fi
for pkg in "$@"; do
    dpkg -s "$pkg" 2>/dev/null | grep -q '^Status: install ok installed' || {
        echo "ERROR: requested package is not fully installed: $pkg" >&2
        exit 1
    }
done
restore_legacy_postinsts || exit "$?"

for pkg in "$@"; do
    case "$pkg" in
        plasma-*|packagekit|polkitd)
            run_proot_config proc-fdinfo /usr/bin/systemd-sysusers polkit.conf || exit "$?"
            validate_polkit_identity || exit "$?"
            break ;;
    esac
done
