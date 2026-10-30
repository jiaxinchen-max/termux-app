#!/bin/sh
# Real APT restoration test; permitted only inside the disposable rootfs fixture.
set -eu
[ "${TERMUX_STATX_TEST_ROOTFS:-}" = independent-copy ] \
    && [ -f /tmp/termux-statx-independent-rootfs ] || exit 64
. /tmp/desktop_container_statx_setup.sh
work=$(mktemp -d /tmp/termux-restore-frontend.XXXXXX)
hook_config=/etc/apt/apt.conf.d/99termux-test-restore-frontend
[ ! -e "$hook_config" ] || exit 65
trap 'status=$?; trap - EXIT; rm -f "$hook_config"; cleanup=$?; [ "$status" -ne 0 ] || status=$cleanup; rm -rf "$work"; cleanup=$?; [ "$status" -ne 0 ] || status=$cleanup; exit "$status"' EXIT
script=/var/lib/dpkg/info/polkitd.postinst
cp "$script" "$work/original.postinst"
[ "$(cat "$script")" != "$(printf '#!/bin/sh\nexit 0')" ] || exit 65
cat > "$work/frontend-hook" <<'HOOK'
#!/bin/sh
set -eu
[ "${DEBIAN_FRONTEND:-}" = noninteractive ] || {
    echo "ERROR: APT pre-install child frontend=${DEBIAN_FRONTEND:-unset}" >&2
    exit 98
}
printf 'frontend=%s stage=APT-Pre-Install-Pkgs\n' "$DEBIAN_FRONTEND" >> /tmp/termux-restore-frontend-proof.txt
cat > /dev/null
HOOK
chmod 755 "$work/frontend-hook"
printf 'DPkg::Pre-Install-Pkgs { "%s"; };\n' "$work/frontend-hook" > "$hook_config"
: > /tmp/termux-restore-frontend-proof.txt
printf '#!/bin/sh\nexit 0\n' > "$script"
export DEBIAN_FRONTEND=dialog
restore_legacy_postinsts
[ "$DEBIAN_FRONTEND" = dialog ] || exit 65
[ -z "${LD_PRELOAD+x}" ] || exit 65
cmp "$work/original.postinst" "$script"
grep -q '^frontend=noninteractive stage=APT-Pre-Install-Pkgs$' /tmp/termux-restore-frontend-proof.txt
[ -z "$(dpkg --audit)" ] || exit 65
cat /tmp/termux-restore-frontend-proof.txt
echo 'PASS real production restoration: APT pre-install child frontend=noninteractive; parent frontend=dialog; original postinst restored; dpkg audit empty'
