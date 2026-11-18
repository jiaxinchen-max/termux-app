#!/bin/sh
# Consume successful apt-get --simulate output under LC_ALL=C.UTF-8.
# Only the private image builder may allow removal of the Protected provider.
set -eu
[ "$#" = 1 ] && [ -f "$1" ] || exit 64
while IFS= read -r line; do
    case "$line" in
        'Remv '*)
            package=${line#Remv }
            package=${package%% *}
            case "$package" in
                coreutils-from-uutils|coreutils-from-uutils:all|rust-coreutils|rust-coreutils:arm64) ;;
                *) printf 'ERROR: unexpected package removal: %s\n' "$package" >&2; exit 65 ;;
            esac
            ;;
    esac
done < "$1"
