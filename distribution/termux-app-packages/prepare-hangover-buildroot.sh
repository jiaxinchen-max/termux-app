#!/bin/bash
# Prepare a dependency-only chroot in an existing dedicated build workspace.
# No mounts, nested Docker, host package changes, or existing-build cleanup.
set -euo pipefail
test "$#" = 1 || { echo "usage: $0 /absolute/dedicated-build-workspace" >&2; exit 1; }
workspace=$(realpath -e -- "$1")
test "$workspace" != / && test "$workspace" != /home
test -f "$workspace/src/hangover/.git/config"
test -f "$workspace/build-hangover-source.sh"
test ! -e "$workspace/buildroot"
test ! -L "$workspace/buildroot"
cd "$workspace/downloads"
printf '%s\n' \
  'a6dd1c05fbba4f9028078a11f143cbb625d2408921eb8207bfa1dd1ad301276f  debian-trixie-amd64.tar.gz' \
  'cee8d2ce3da5145ce4dc882e70d0b0719a783d53a99752c60948fc0659975a65  llvm-mingw.tar.xz' | sha256sum -c -
sudo -n mkdir "$workspace/buildroot"
sudo -n tar -xzf debian-trixie-amd64.tar.gz -C "$workspace/buildroot"
sudo -n mkdir -p "$workspace/buildroot/opt" "$workspace/buildroot/work"
sudo -n tar -xJf llvm-mingw.tar.xz -C "$workspace/buildroot/opt"
sudo -n cp -L /etc/resolv.conf "$workspace/buildroot/etc/resolv.conf"
# Container CAP_MKNOD and CAP_SYS_CHROOT suffice; CAP_SYS_ADMIN is not needed.
for entry in null:1:3 zero:1:5 random:1:8 urandom:1:9; do
  IFS=: read -r name major minor <<< "$entry"
  if ! test -e "$workspace/buildroot/dev/$name"; then
    sudo -n mknod -m 666 "$workspace/buildroot/dev/$name" c "$major" "$minor"
  fi
done
sudo -n cp -a "$workspace/src/hangover" "$workspace/buildroot/work/hangover"
sudo -n cp "$workspace/build-hangover-source.sh" "$workspace/buildroot/work/"
sudo -n cp "$workspace/hangover-source.lock.json" "$workspace/buildroot/work/"
sudo -n chroot "$workspace/buildroot" /usr/bin/env \
  http_proxy="${http_proxy:-}" https_proxy="${https_proxy:-}" \
  /bin/bash /work/build-hangover-source.sh
