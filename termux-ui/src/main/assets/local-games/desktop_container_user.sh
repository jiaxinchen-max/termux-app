#!/bin/sh
# Run as container root after installing passwd/sudo. Never reset a changed password.
set -eu
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[ "$(id -u)" = 0 ] || { echo 'ERROR: account provisioning requires container root' >&2; exit 77; }
if ! getent passwd termux >/dev/null; then
    useradd --create-home --user-group --home-dir /home/termux --shell /bin/bash termux
    printf '%s\n' 'termux:termux' | chpasswd
fi
[ "$(id -u termux)" != 0 ] || { echo 'ERROR: termux must be a normal user' >&2; exit 77; }
[ "$(getent passwd termux | cut -d: -f6)" = /home/termux ] || {
    echo 'ERROR: unexpected termux home' >&2; exit 77;
}
usermod --append --groups sudo termux
install -d -m 0750 -o termux -g "$(id -gn termux)" /home/termux
for directory in .config .cache .local .local/share Desktop Documents Downloads Music Pictures Videos; do
    install -d -m 0750 -o termux -g "$(id -gn termux)" "/home/termux/$directory"
done
# Use packaged PAM/sudo authentication; do not grant passwordless policy access.
install -d -m 0750 /etc/sudoers.d
policy=$(mktemp /etc/sudoers.d/.termux.XXXXXX)
trap 'rm -f "$policy"' EXIT
printf '%s\n' 'termux ALL=(ALL:ALL) ALL' > "$policy"
visudo --check --file="$policy"
chmod 0440 "$policy"
mv -f "$policy" /etc/sudoers.d/termux
# Restore the packaged helper's standard setuid mode: extraction under an Android
# UID can lose it. PRoot emulates this within the guest; Android stays unprivileged.
if [ -f /usr/lib/polkit-1/polkit-agent-helper-1 ]; then
    chown root:root /usr/lib/polkit-1/polkit-agent-helper-1
    chmod 4755 /usr/lib/polkit-1/polkit-agent-helper-1
fi
