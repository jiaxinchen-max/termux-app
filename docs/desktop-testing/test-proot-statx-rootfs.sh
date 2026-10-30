#!/bin/sh
# Destructive fixtures are permitted only in the disposable rootfs copy created for this test.
set -eu
[ "${TERMUX_STATX_TEST_ROOTFS:-}" = independent-copy ] \
    && [ -f /tmp/termux-statx-independent-rootfs ] || exit 64
cd /tmp
. ./desktop_container_statx_setup.sh

if [ -f /etc/termux-proot-sysusers.conf ]; then
    cp /etc/termux-proot-sysusers.conf /tmp/owned-sysusers.manifest
    wrapper_before=$(sha256sum /usr/bin/systemd-sysusers)
    printf 'backend=unknown\n' > /etc/termux-proot-sysusers.conf
    set +e
    configure_proot_statx proc-fdinfo /tmp/desktop_container_statx_aarch64.bin /tmp/desktop_container_run_config.sh
    code=$?
    set -e
    [ "$code" -eq 65 ] || exit 65
    [ "$(sha256sum /usr/bin/systemd-sysusers)" = "$wrapper_before" ] || exit 65
    cp /tmp/owned-sysusers.manifest /etc/termux-proot-sysusers.conf
    echo 'PASS modified manifest rejected without touching its wrapper/diversion'
fi

export QSG_RHI_BACKEND=vulkan VK_ICD_FILENAMES=/explicit/renderer.json
configure_proot_statx proc-fdinfo /tmp/desktop_container_statx_aarch64.bin /tmp/desktop_container_run_config.sh
[ -z "$(dpkg-divert --listpackage /usr/bin/systemd-sysusers)" ] || exit 65
[ ! -f /etc/termux-proot-sysusers.conf ] || exit 65
[ "$QSG_RHI_BACKEND" = vulkan ] && [ "$VK_ICD_FILENAMES" = /explicit/renderer.json ] || exit 65
[ -z "${LD_PRELOAD+x}" ] && [ -z "${TERMUX_PROOT_STATX_MODE+x}" ] || exit 65
echo 'PASS strict owned-diversion retirement; no renderer/environment leakage'

printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
: > /var/lib/termux-proot-statx/legacy-postinst-verification.txt
while IFS= read -r specification; do
    package=${specification%%=*}
    [ "$(dpkg-query -W -f='${binary:Package}=${Version}' "$package")" = "$specification" ] || exit 65
    printf '#!/bin/sh\nexit 0\n' > "/var/lib/dpkg/info/$package.postinst"
done < /tmp/legacy-postinst-packages-15.txt
restore_legacy_postinsts
while IFS= read -r specification; do
    package=${specification%%=*}
    dpkg-query -W -f='${binary:Package}: ${Status} ${Version}\n' "$package"
done < /tmp/legacy-postinst-packages-15.txt
[ "$(wc -l < /var/lib/termux-proot-statx/legacy-postinst-verification.txt)" -eq 15 ] || exit 65
echo 'PASS all 15 exact-version real postinsts restored and matched to original deb controls'

run_proot_config proc-fdinfo /usr/bin/systemd-sysusers polkit.conf
run_proot_config proc-fdinfo sh -c '[ "$DEBIAN_FRONTEND" = noninteractive ]'
validate_polkit_identity
set +e
run_proot_config auto /bin/true
code=$?
set -e
[ "$code" -eq 64 ] || exit 65
run_proot_config proc-fdinfo sh -c 'exit 37' && exit 65 || code=$?
[ "$code" -eq 37 ] || exit 65
configure_proot_statx proc-fdinfo /tmp/desktop_container_statx_aarch64.bin /tmp/desktop_container_run_config.sh
[ -z "${LD_PRELOAD+x}" ] && [ -z "${TERMUX_PROOT_STATX_MODE+x}" ] || exit 65
echo 'PASS standard tool, explicit backend, original command failure and idempotence'
echo 'Independent standard-tool proc-fdinfo rootfs tests passed'
