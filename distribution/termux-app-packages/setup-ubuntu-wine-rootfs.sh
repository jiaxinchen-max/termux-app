#!/bin/sh
# Offline image production only. The Android installer restores the finished image.
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8
work=/run/wine-build
phase=${1:-complete}
case "$phase" in complete|dependencies-only) ;; *) exit 64 ;; esac

. /etc/os-release
[ "$ID:$VERSION_ID:$VERSION_CODENAME" = ubuntu:26.04:resolute ] || {
    echo 'ERROR: Wine image production requires Ubuntu 26.04 Resolute' >&2; exit 65;
}
[ "$(dpkg --print-architecture)" = arm64 ] || exit 65
[ -s "$work/wine-runtime.properties" ] || exit 66
[ -s /etc/ssl/certs/ca-certificates.crt ] || exit 66

. "$work/desktop_container_statx_setup.sh"
. "$work/desktop_container_apt_sources.sh"
configure_proot_statx proc-fdinfo "$work/desktop_container_statx_aarch64.bin" \
    "$work/desktop_container_run_config.sh"
configure_ubuntu_apt_sources
# Retain the exact downloaded packages until the input lock has been written.
if [ -f /etc/apt/apt.conf.d/docker-clean ]; then
    mv /etc/apt/apt.conf.d/docker-clean "$work/docker-clean.original"
fi
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
run_proot_config proc-fdinfo apt-get update --error-on=any \
    -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt

if [ "$phase" = complete ]; then
    set -- "$work"/hangover/*.deb
    [ -f "$1" ] || { echo 'ERROR: pinned Hangover packages are missing' >&2; exit 66; }
else
    set --
fi
# All system libraries come from the locked Ubuntu package set. Local Hangover
# packages remain application inputs; apt must not select a different version.
[ -s "$work/ubuntu-packages.lock.tsv" ] || { echo 'ERROR: package lock missing' >&2; exit 66; }
while IFS="$(printf '\t')" read -r package architecture version status; do
    [ "$status" = 'install ok installed' ] || exit 65
    case "$package" in ''|*[!a-z0-9+.-]*) exit 65 ;; esac
    case "$architecture" in arm64|all) ;; *) exit 65 ;; esac
    if [ "$phase" = dependencies-only ]; then
        case "$package" in hangover-*) continue ;; esac
    fi
    set -- "$@" "$package:$architecture=$version"
done < "$work/ubuntu-packages.lock.tsv"
# The official OCI contains a Protected Rust provider. Replace only that
# provider and its payload, in this private build root, using Ubuntu's package
# scripts (including diversions). Reject any other planned package removal.
set -- "$@" coreutils-from-uutils- rust-coreutils-
run_proot_config proc-fdinfo apt-get --simulate install --allow-remove-essential \
    --no-install-recommends "$@" > "$work/ubuntu-install-plan.txt"
/bin/sh "$work/check-coreutils-plan.sh" "$work/ubuntu-install-plan.txt"
run_proot_config proc-fdinfo apt-get install -y --no-install-recommends --allow-remove-essential \
    -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt \
    -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
    "$@"

if [ "$phase" = dependencies-only ]; then
    echo 'Ubuntu system dependencies installed; pinned Wine input still required.'
    exit 0
fi

sed -i 's/^# *zh_CN.GBK GBK/zh_CN.GBK GBK/; s/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/' /etc/locale.gen
locale-gen
[ "$(LC_ALL=zh_CN.UTF-8 locale charmap)" = UTF-8 ]
[ "$(LC_ALL=zh_CN.GBK locale charmap)" = GBK ]
fc-match 'Noto Sans CJK SC'
audit=$(dpkg --audit)
[ -z "$audit" ] || { printf '%s\n' "$audit" >&2; exit 65; }
dpkg-query -W -f='${Package}\t${Architecture}\t${Version}\t${Status}\n' \
    > "$work/ubuntu-packages.tsv"
cmp "$work/ubuntu-packages.lock.tsv" "$work/ubuntu-packages.tsv" || {
    echo 'ERROR: installed packages differ from the pinned Ubuntu input set' >&2; exit 65;
}
find /var/cache/apt/archives -maxdepth 1 -name '*.deb' -type f -exec sha256sum {} + \
    > "$work/ubuntu-deb-sha256.txt"
cp /var/lib/dpkg/status "$work/ubuntu-dpkg-status"
install -m 644 "$work/wine-runtime.properties" /etc/wine-runtime.properties
mkdir -p /opt/box64-wine /opt/box64-proton /opt/box64/rootfs_box64_0.4.4 \
    /opt/box64/rootfs_box64_0.4.5 /mnt/games/game /mnt/games/prefix
for id in rootfs_dxvk_2.7 rootfs_dxvk_3.1 rootfs_dxvk_1.10.3; do
    mkdir -p "/opt/games-runtime/$id/system32" "/opt/games-runtime/$id/syswow64"
done
test -x /usr/bin/wine
test -x /usr/bin/wineboot
test -x /usr/bin/env
echo 'Ubuntu Wine image packages configured; trimming and runtime validation still required.'
