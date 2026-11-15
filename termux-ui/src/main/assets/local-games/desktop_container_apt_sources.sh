#!/bin/sh
# Configure only official Ubuntu sources; keep suites, signatures and third-party URIs intact.
# The optional root is used by the isolated filesystem contract tests.
configure_ubuntu_apt_sources() {
    apt_root="${1:-}"
    [ -r "$apt_root/etc/os-release" ] || return 0
    apt_distro=$(sed -n 's/^ID=//p' "$apt_root/etc/os-release" | tr -d "\"'")
    [ "$apt_distro" = ubuntu ] || return 0
    for apt_source in "$apt_root/etc/apt/sources.list" \
        "$apt_root"/etc/apt/sources.list.d/*.list "$apt_root"/etc/apt/sources.list.d/*.sources; do
        [ -f "$apt_source" ] || continue
        apt_candidate=$(mktemp "$apt_source.desktop-https.XXXXXX") || return 1
        if ! cp -p "$apt_source" "$apt_candidate" || ! sed -E \
            's@http://(archive\.ubuntu\.com/ubuntu|security\.ubuntu\.com/ubuntu|ports\.ubuntu\.com/ubuntu-ports)(/|[[:space:]]|$)@https://\1\2@g' \
            "$apt_source" > "$apt_candidate"; then
            rm -f "$apt_candidate"
            return 1
        fi
        if cmp -s "$apt_source" "$apt_candidate"; then
            rm -f "$apt_candidate"
            continue
        fi
        if [ ! -e "$apt_source.desktop-http.bak" ]; then
            cp -p "$apt_source" "$apt_source.desktop-http.bak" || {
                rm -f "$apt_candidate"; return 1;
            }
        fi
        mv -f "$apt_candidate" "$apt_source" || { rm -f "$apt_candidate"; return 1; }
        echo "Configured official Ubuntu HTTPS sources: $apt_source"
    done
}
