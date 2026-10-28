#!/bin/sh
# Runs INSIDE the proot container: installs the Plasma packages, tolerating systemd's maintainer scripts.
#
# On Debian forky (Plasma 6.7) the dependency tree pulls in systemd, udev, polkitd, network-manager,
# ... whose postinst scripts cannot run under proot: there is no init and /etc/machine-id cannot be
# written ("Protocol driver not attached"). One half-configured systemd then leaves dozens of packages
# unconfigured and `apt install` exits non-zero. None of those scripts' work is needed in a container
# without an init, so after a failed attempt the postinst of the packages that are half configured is
# replaced by a no-op and configuration is resumed. Packages that are merely waiting on a dependency
# keep their real scripts (dbus, for instance, creates its user there).
#
# Usage: desktop_container_plasma_apt.sh <package>...
export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Keep dpkg from trying to start services.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d

# dbus's postinst needs the messagebus account, which is normally created by systemd-sysusers (it
# cannot run here). Create it up front so dbus keeps its real maintainer script.
getent group messagebus >/dev/null 2>&1 || groupadd --system messagebus
getent passwd messagebus >/dev/null 2>&1 || \
    useradd --system --gid messagebus --home-dir /nonexistent --no-create-home \
        --shell /usr/sbin/nologin messagebus

apt-get update || exit 1
apt-get install -y -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef "$@" \
    && dpkg -s "$1" 2>/dev/null | grep -q '^Status: install ok installed' && exit 0
echo "apt install did not complete; neutralising the maintainer scripts that cannot run under proot"

half_configured() {
    # `dpkg --audit` lists them under "only half configured"; package lines start with one space.
    dpkg --audit 2>/dev/null | awk '
        /only half configured/ { inlist = 1; next }
        inlist && /^$/         { if (seen) inlist = 0; next }
        inlist && /^ [a-z0-9]/ { print $1; seen = 1 }'
}

install_all() {
    apt-get install -y -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-confdef "$@"
}

all_installed() {
    for pkg in "$@"; do
        dpkg -s "$pkg" 2>/dev/null | grep -q '^Status: install ok installed' || return 1
    done
}

round=0
while [ "$round" -lt 6 ]; do
    round=$((round + 1))
    for pkg in $(half_configured); do
        script="/var/lib/dpkg/info/$pkg.postinst"
        [ -f "$script" ] || continue
        echo "round $round: postinst of $pkg replaced by a no-op"
        printf '#!/bin/sh\nexit 0\n' > "$script"
    done
    # Finish configuring what was unpacked, then resume the install that was asked for: `apt-get
    # install -f` alone only repairs broken packages and would leave the requested ones uninstalled.
    dpkg --configure -a
    if install_all "$@" && all_installed "$@"; then
        exit 0
    fi
done
echo "ERROR: could not install and configure: $*" >&2
exit 1
