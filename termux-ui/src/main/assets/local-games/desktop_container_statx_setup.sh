#!/bin/sh
# Sourced only inside the explicitly selected proot container. Standard package tools remain intact.

statx_file_sha() {
    statx_hash_output=$(sha256sum "$1") || return $?
    printf '%s\n' "${statx_hash_output%% *}"
}

retire_owned_opensysusers() {
    old_manifest=/etc/termux-proot-sysusers.conf
    old_original=/usr/bin/systemd-sysusers.termux-proot-original
    diversion_owner=$(dpkg-divert --listpackage /usr/bin/systemd-sysusers) || return $?
    if [ ! -f "$old_manifest" ]; then
        [ -z "$diversion_owner" ] || {
            echo 'ERROR: unknown systemd-sysusers diversion; standard tool was not changed' >&2; return 65;
        }
        return 0
    fi
    old_hash=$(sed -n 's/^sha256=//p' "$old_manifest") || return $?
    case "$old_hash" in
        4cddaa37e532070e9226f7331366fbdb92dab9a6c7bcee43cc44734ee689d52f|b6bbbcb06d7fa7300fb409c1756ab36df7094fe52b3e2bfe402a481f9c242a21) ;;
        *) echo 'ERROR: unrecognized opensysusers manifest; no diversion was changed' >&2; return 65 ;;
    esac
    expected_manifest=$(printf 'backend=opensysusers\nversion=0.7.5\nsha256=%s\noriginal=%s' "$old_hash" "$old_original")
    [ "$(cat "$old_manifest")" = "$expected_manifest" ] || {
        echo 'ERROR: opensysusers manifest differs from the owned schema' >&2; return 65;
    }
    old_source=/usr/local/libexec/termux-proot-sysusers/opensysusers-sysusers
    [ "$(statx_file_sha "$old_source")" = "$old_hash" ] || {
        echo 'ERROR: installed opensysusers source differs from its manifest' >&2; return 65;
    }
    original_md5=$(awk '$2 == "usr/bin/systemd-sysusers" { print $1 }' /var/lib/dpkg/info/systemd.md5sums) || return $?
    [ "${#original_md5}" -eq 32 ] || { echo 'ERROR: packaged sysusers checksum is unavailable' >&2; return 65; }
    if [ -n "$diversion_owner" ]; then
        diversion_path=$(dpkg-divert --truename /usr/bin/systemd-sysusers) || return $?
        [ "$diversion_owner" = LOCAL ] && [ "$diversion_path" = "$old_original" ] || {
            echo 'ERROR: sysusers diversion is not the owned local diversion' >&2; return 65;
        }
        [ "$(statx_file_sha /usr/bin/systemd-sysusers)" = fdbd3b47699d6df4db7f4a45c681bbe95bc14b4cf2acfd5f512a85bdbb325b72 ] || {
            echo 'ERROR: sysusers wrapper was modified; diversion was not changed' >&2; return 65;
        }
        actual_md5=$(md5sum "$old_original") || return $?
        [ "${actual_md5%% *}" = "$original_md5" ] || {
            echo 'ERROR: original sysusers binary differs from package metadata' >&2; return 65;
        }
        rm /usr/bin/systemd-sysusers || return $?
        dpkg-divert --local --remove --rename --divert "$old_original" /usr/bin/systemd-sysusers || return $?
    else
        # A previous retirement may have completed the rename before its final manifest cleanup.
        [ ! -e "$old_original" ] || { echo 'ERROR: unexpected retired sysusers binary remains' >&2; return 65; }
    fi
    actual_md5=$(md5sum /usr/bin/systemd-sysusers) || return $?
    [ "${actual_md5%% *}" = "$original_md5" ] || {
        echo 'ERROR: restored standard sysusers binary checksum mismatch' >&2; return 65;
    }
    rm "$old_source" "$old_manifest" || return $?
    echo 'Retired verified owned opensysusers diversion; standard systemd-sysusers restored'
}

configure_proot_statx() { # explicit-backend bundled-library bundled-runner
    [ "${1:-}" = proc-fdinfo ] || { echo 'ERROR: explicitly select statx backend proc-fdinfo' >&2; return 64; }
    case "$(uname -m)" in aarch64|arm64) ;; *) echo 'ERROR: statx payload requires Linux arm64' >&2; return 69 ;; esac
    [ "$(id -u)" = 0 ] || { echo 'ERROR: statx setup requires the selected root container' >&2; return 69; }
    statx_library_source="$2"
    statx_runner_source="$3"
    statx_library_hash=92c351c40c267ec620cce42a9ae97f0ccd4600cff91848130f33ae5557e87fce
    [ "$(statx_file_sha "$statx_library_source")" = "$statx_library_hash" ] || {
        echo 'ERROR: bundled statx library checksum mismatch' >&2; return 65;
    }
    if getent passwd polkitd >/dev/null 2>&1; then
        validate_polkit_identity || return $?
    else
        lookup_code=$?
        [ "$lookup_code" -eq 2 ] || return "$lookup_code"
    fi
    retire_owned_opensysusers || return $?
    mkdir -p /usr/local/lib/termux-proot /usr/local/libexec/termux-proot /var/lib/termux-proot-statx || return $?
    install -m 644 "$statx_library_source" /usr/local/lib/termux-proot/libstatx-procfdinfo.so.new || return $?
    mv /usr/local/lib/termux-proot/libstatx-procfdinfo.so.new /usr/local/lib/termux-proot/libstatx-procfdinfo.so || return $?
    install -m 755 "$statx_runner_source" /usr/local/libexec/termux-proot/run-config || return $?
    /usr/local/libexec/termux-proot/run-config --statx-backend=proc-fdinfo /bin/true || return $?
    cat > /usr/local/libexec/termux-proot/dpkg-procfdinfo <<'WRAPPER'
#!/bin/sh
exec /usr/local/libexec/termux-proot/run-config --statx-backend=proc-fdinfo /usr/bin/dpkg "$@"
WRAPPER
    code=$?
    [ "$code" -eq 0 ] || return "$code"
    chmod 755 /usr/local/libexec/termux-proot/dpkg-procfdinfo || return $?
    printf 'Dir::Bin::dpkg "/usr/local/libexec/termux-proot/dpkg-procfdinfo";\n' \
        > /etc/apt/apt.conf.d/99termux-proot-statx || return $?
    printf 'backend=proc-fdinfo\nlibrary_sha256=%s\nscope=package-configuration\n' "$statx_library_hash" \
        > /etc/termux-proot-statx.conf || return $?
    echo "Configured statx backend=proc-fdinfo scope=apt/dpkg sha256=$statx_library_hash; standard tools preserved"
}

run_proot_config() {
    statx_requested_backend="${1:-}"
    shift
    /usr/local/libexec/termux-proot/run-config "--statx-backend=$statx_requested_backend" "$@"
}

restore_legacy_postinsts() (
    # APT preconfiguration runs before its dpkg wrapper; select the frontend for this subshell.
    export DEBIAN_FRONTEND=noninteractive
    # Planned same-version migration of the precise historical no-op signature, never a retry.
    statx_work=$(mktemp -d /tmp/termux-postinst-restore.XXXXXX) || exit $?
    trap 'status=$?; trap - EXIT; rm -rf "$statx_work"; cleanup_code=$?; [ "$status" -ne 0 ] || status=$cleanup_code; exit "$status"' EXIT
    record=/var/lib/termux-proot-statx/legacy-postinst-verification.txt
    touch "$record" || exit $?
    for script in /var/lib/dpkg/info/*.postinst; do
        [ -f "$script" ] || continue
        content=$(cat "$script") || exit $?
        [ "$content" = "$(printf '#!/bin/sh\nexit 0')" ] || continue
        package=${script##*/}
        package=${package%.postinst}
        case "$package" in ''|-*|*[!a-z0-9+.:_-]*) echo "ERROR: invalid legacy package name: $package"; exit 65 ;; esac
        version=$(dpkg-query -W -f='${Version}' "$package") || exit $?
        [ -n "$version" ] || { echo "ERROR: missing installed version: $package"; exit 65; }
        package_dir="$statx_work/$package"
        mkdir "$package_dir" || exit $?
        echo "Restoring historical postinst: $package=$version (exact version, signed APT, no removals/upgrades)"
        (cd "$package_dir" && apt-get -o APT::Sandbox::User=root download "$package=$version") || exit $?
        set -- "$package_dir"/*.deb
        [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "ERROR: ambiguous downloaded deb for $package"; exit 65; }
        deb="$1"
        [ "$(dpkg-deb --field "$deb" Version)" = "$version" ] || { echo "ERROR: downloaded version mismatch: $package"; exit 65; }
        [ "$(dpkg-deb --field "$deb" Package)" = "${package%%:*}" ] || { echo "ERROR: downloaded package name mismatch: $package"; exit 65; }
        installed_arch=$(dpkg-query -W -f='${Architecture}' "$package") || exit $?
        [ "$(dpkg-deb --field "$deb" Architecture)" = "$installed_arch" ] || { echo "ERROR: downloaded package architecture mismatch: $package"; exit 65; }
        dpkg-deb --control "$deb" "$package_dir/control" || exit $?
        original="$package_dir/control/postinst"
        [ -f "$original" ] || { echo "ERROR: original package has no postinst: $package"; exit 65; }
        LC_ALL=C apt-get -s --no-remove --reinstall install "$deb" > "$package_dir/plan" 2>&1
        code=$?
        [ "$code" -eq 0 ] || { cat "$package_dir/plan"; exit "$code"; }
        grep -Eq '^0 upgraded, .* 0 to remove and ' "$package_dir/plan" || {
            cat "$package_dir/plan"
            echo "ERROR: exact-version restoration would upgrade/remove packages: $package"; exit 65;
        }
        apt-get -y --no-remove --reinstall install "$deb"
        code=$?
        [ "$code" -eq 0 ] || { echo "ERROR: original postinst restoration failed: $package (status $code)"; exit "$code"; }
        cmp "$original" "$script" || { echo "ERROR: restored postinst differs from original deb: $package"; exit 65; }
        dpkg -s "$package" | grep -q '^Status: install ok installed' || { echo "ERROR: package not fully configured: $package"; exit 1; }
        original_hash=$(statx_file_sha "$original") || exit $?
        printf '%s=%s postinst_sha256=%s original_verified\n' "$package" "$version" "$original_hash" >> "$record" || exit $?
    done
    audit=$(dpkg --audit 2>&1)
    code=$?
    [ "$code" -eq 0 ] || { printf '%s\n' "$audit"; exit "$code"; }
    [ -z "$audit" ] || { printf 'ERROR: container package configuration incomplete:\n%s\n' "$audit"; exit 1; }
)

validate_polkit_identity() {
    # Inspect the effective standard definition only to validate the account's lock semantics.
    # Account creation remains entirely in the packaged systemd-sysusers implementation.
    polkit_definition=
    for polkit_config in /etc/sysusers.d/polkit.conf /run/sysusers.d/polkit.conf \
        /usr/local/lib/sysusers.d/polkit.conf /usr/lib/sysusers.d/polkit.conf; do
        [ -e "$polkit_config" ] || [ -L "$polkit_config" ] || continue
        polkit_definition=$(awk '$2 == "polkitd" { print $1 }' "$polkit_config") || return $?
        break
    done
    case "$polkit_definition" in
        u|u!) ;;
        *) echo 'ERROR: PolicyKit requires one recognized standard sysusers definition (u or u!)' >&2; return 65 ;;
    esac
    polkit_passwd=$(getent passwd polkitd) || { echo 'ERROR: PolicyKit user is missing' >&2; return 1; }
    polkit_group=$(getent group polkitd) || { echo 'ERROR: PolicyKit group is missing' >&2; return 1; }
    polkit_shadow=$(getent shadow polkitd) || { echo 'ERROR: PolicyKit shadow record is missing' >&2; return 1; }
    IFS=: read -r name password polkit_uid polkit_gid gecos polkit_home polkit_shell <<EOF
$polkit_passwd
EOF
    IFS=: read -r name password polkit_named_gid members <<EOF
$polkit_group
EOF
    IFS=: read -r name polkit_password changed minimum maximum warning inactive polkit_expire reserved <<EOF
$polkit_shadow
EOF
    for identity_number in "$polkit_uid" "$polkit_gid" "$polkit_named_gid"; do
        case "$identity_number" in
            ''|*[!0-9]*|0) echo 'ERROR: PolicyKit uid/gid is invalid or root' >&2; return 1 ;;
        esac
    done
    [ "$polkit_gid" = "$polkit_named_gid" ] && [ "$polkit_uid" = "$(id -u polkitd)" ] \
        && [ "$polkit_gid" = "$(id -g polkitd)" ] || {
        echo 'ERROR: PolicyKit primary gid differs from the named polkitd group' >&2; return 1;
    }
    case "$polkit_home" in /|/nonexistent) ;; *) echo 'ERROR: PolicyKit home is not a service home' >&2; return 1 ;; esac
    case "$polkit_shell" in /usr/sbin/nologin|/sbin/nologin) ;; *) echo 'ERROR: PolicyKit login shell is not nologin' >&2; return 1 ;; esac
    case "$polkit_password" in '!'*|'*'*) ;; *) echo 'ERROR: PolicyKit password is not locked' >&2; return 1 ;; esac
    case "$polkit_definition:$polkit_expire" in
        'u!:1'|'u:'|'u:1') ;;
        *) echo "ERROR: PolicyKit account expiry differs from its $polkit_definition sysusers definition" >&2; return 1 ;;
    esac
    echo "PolicyKit service identity: uid=$polkit_uid gid=$polkit_gid home=$polkit_home shell=$polkit_shell locked=yes definition=$polkit_definition"
}

