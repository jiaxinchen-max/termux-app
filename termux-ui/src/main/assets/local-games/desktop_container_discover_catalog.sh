#!/bin/sh
# Publish the OS application catalog before the first Discover process loads its pool.
# Desktop packages (including appstream and the default CA bundle) must already be installed.
set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[ "$#" -eq 1 ] && [ "$1" = --statx-backend=proc-fdinfo ] || exit 64
. /etc/os-release
[ "${ID:-}" = ubuntu ] || { echo 'ERROR: Discover catalog preparation requires Ubuntu' >&2; exit 64; }
command -v appstreamcli >/dev/null 2>&1 || { echo 'ERROR: appstreamcli is not installed' >&2; exit 69; }
helper_dir=$(dirname "$0")
. "$helper_dir/desktop_container_statx_setup.sh" || exit "$?"
. "$helper_dir/desktop_container_apt_sources.sh" || exit "$?"
configure_ubuntu_apt_sources || exit "$?"
# Installing appstream introduces APT's catalog index configuration: fetch these indexes now.
run_proot_config proc-fdinfo apt-get update --error-on=any || exit "$?"
appstreamcli refresh --force --source=os
