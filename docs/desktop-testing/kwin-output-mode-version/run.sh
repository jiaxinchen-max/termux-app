#!/data/data/com.termux/files/usr/bin/bash
# SPDX-License-Identifier: MIT
set -u
root="${1:?Usage: run.sh test-directory Termux-prefix}"
prefix="${2:?Termux-prefix is required}"
set +e
LD_LIBRARY_PATH="$root/old:$prefix/lib" "$root/check-output-mode-version"
old_status=$?
LD_LIBRARY_PATH="$root/fixed:$prefix/lib" "$root/check-output-mode-version"
fixed_status=$?
set -e
printf 'old_status=%s fixed_status=%s\n' "$old_status" "$fixed_status"
[ "$old_status" = 1 ] && [ "$fixed_status" = 0 ]
