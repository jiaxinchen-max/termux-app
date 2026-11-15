#!/bin/sh
# Configure only official Ubuntu sources; keep suites, signatures and third-party URIs intact.
# The optional root is used by the isolated filesystem contract tests.
bootstrap_ubuntu_ca_certificates() {
    ca_root="$1"
    ca_source="$2"
    [ -r "$ca_root/etc/os-release" ] || return 0
    ca_distro=$(sed -n 's/^ID=//p' "$ca_root/etc/os-release" | tr -d "\"'")
    [ "$ca_distro" = ubuntu ] || return 0
    ca_target="$ca_root/etc/ssl/certs/ca-certificates.crt"
    [ ! -s "$ca_target" ] || return 0
    if [ ! -s "$ca_source" ] || ! grep -q '^-----BEGIN CERTIFICATE-----$' "$ca_source"; then
        echo 'ERROR: trusted host CA bundle is required for initial Ubuntu HTTPS access' >&2
        return 66
    fi
    mkdir -p "$ca_root/etc/ssl/certs" || return 1
    ca_temporary=$(mktemp "$ca_root/etc/ssl/certs/.desktop-ca.XXXXXX") || return 1
    if ! cp "$ca_source" "$ca_temporary" || ! chmod 644 "$ca_temporary" || ! mv -f "$ca_temporary" "$ca_target"; then
        rm -f "$ca_temporary"
        return 1
    fi
    echo 'Initialized Ubuntu HTTPS trust from the existing host CA bundle'
}

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
