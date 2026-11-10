#!/bin/sh
# Trusted parser/argv builder for frozen bindings. Input values are data, never shell code.
# Caller sets TERMUX_FILES_DIR and PROOT_BIN; binding fields must follow codec field order.
PROOT_COMPONENT_RECEIPTS=
PROOT_COMPONENT_BINDS=
PROOT_BINDING_COUNT=
PROOT_BINDING_INDEX=0
PROOT_BINDING_FIELD=componentId
PROOT_BINDING_ROLES=' '
PROOT_BINDING_IDENTITY=
PROOT_DXVK_IDENTITY=

proot_binding_decode() {
    pbd_value=$(printf '%s' "$1" | base64 -d 2>/dev/null) || return 2
    # Command substitution must not silently trim newlines or discard NUL bytes.
    [ "$(printf '%s' "$pbd_value" | base64 | tr -d '\n')" = "$1" ] || return 2
    pbd_cr=$(printf '\r')
    case "$pbd_value" in *'
'*|*"$pbd_cr"*) return 2 ;; esac
    printf '%s' "$pbd_value"
}

# 0 = binding field, 1 = another protocol field, 2 = malformed binding.
proot_binding_field() {
    case "$1" in
        bindingCount)
            [ -z "$PROOT_BINDING_COUNT" ] || return 2
            case "$2" in ''|*[!0-9]*|0[0-9]*) return 2 ;; esac
            [ "${#2}" -le 2 ] && [ "$2" -le 16 ] || return 2
            PROOT_BINDING_COUNT=$2
            return 0 ;;
        binding.*) ;;
        *) return 1 ;;
    esac
    [ -n "$PROOT_BINDING_COUNT" ] || return 2
    [ "$PROOT_BINDING_INDEX" -lt "$PROOT_BINDING_COUNT" ] || return 2
    [ "$1" = "binding.$PROOT_BINDING_INDEX.$PROOT_BINDING_FIELD" ] || return 2
    pbf_value=$(proot_binding_decode "$2") || return 2
    case "$PROOT_BINDING_FIELD" in
        componentId)
            PB_ID=$pbf_value
            case "$PB_ID" in ''|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) return 2 ;; esac
            [ "${#PB_ID}" -le 128 ] || return 2
            PROOT_BINDING_FIELD=version ;;
        version)
            PB_VERSION=$pbf_value
            case "$PB_VERSION" in ''|0*|*[!0-9]*) return 2 ;; esac
            [ "${#PB_VERSION}" -le 10 ] && [ "$PB_VERSION" -le 2147483647 ] || return 2
            PROOT_BINDING_FIELD=sha256 ;;
        sha256)
            PB_SHA=$pbf_value
            case "$PB_SHA" in *[!0-9a-f]*) return 2 ;; esac
            [ "${#PB_SHA}" -eq 64 ] || return 2
            PROOT_BINDING_FIELD=versionDirectory ;;
        versionDirectory) PB_VERSION_DIR=$pbf_value; PROOT_BINDING_FIELD=contentRelativePath ;;
        contentRelativePath) PB_CONTENT=$pbf_value; PROOT_BINDING_FIELD=guestTarget ;;
        guestTarget) PB_TARGET=$pbf_value; PROOT_BINDING_FIELD=role ;;
        role) PB_ROLE=$pbf_value; PROOT_BINDING_FIELD=revision ;;
        revision)
            [ "$pbf_value" = 1 ] || return 2
            proot_binding_validate || return 2
            PROOT_COMPONENT_RECEIPTS="${PROOT_COMPONENT_RECEIPTS}${PB_ID}|${PB_VERSION_DIR}
"
            PROOT_COMPONENT_BINDS="${PROOT_COMPONENT_BINDS}${PB_SOURCE}:${PB_TARGET}
"
            case "$PB_ROLE" in WINE|BOX64)
                PROOT_BINDING_IDENTITY="${PROOT_BINDING_IDENTITY}${PB_ROLE}|${PB_ID}|${PB_VERSION}|${PB_SHA}
" ;;
            esac
            PROOT_BINDING_INDEX=$((PROOT_BINDING_INDEX + 1))
            PROOT_BINDING_FIELD=componentId ;;
    esac
}

proot_binding_validate() {
    case "$PB_VERSION_DIR/$PB_CONTENT" in *:*|*'\'*|*'/../'*|*/..|*'/./'*|*/.) return 2 ;; esac
    case "$PB_CONTENT" in /*|*//*|*/) return 2 ;; esac
    pb_install=$(realpath "$TERMUX_FILES_DIR/front/components/install") || return 2
    pb_versions="$pb_install/$PB_ID/versions"
    [ "$(realpath "$pb_versions")" = "$pb_versions" ] || return 2
    [ "$(dirname "$PB_VERSION_DIR")" = "$pb_versions" ] || return 2
    [ "$(realpath "$PB_VERSION_DIR")" = "$PB_VERSION_DIR" ] || return 2
    PB_SOURCE=$PB_VERSION_DIR
    [ -z "$PB_CONTENT" ] || PB_SOURCE="$PB_VERSION_DIR/$PB_CONTENT"
    PB_SOURCE=$(realpath "$PB_SOURCE") || return 2
    case "$PB_SOURCE" in "$PB_VERSION_DIR"|"$PB_VERSION_DIR"/*) ;; *) return 2 ;; esac
    [ -d "$PB_SOURCE" ] || return 2
    pb_receipt="$PB_VERSION_DIR/.wine-component.properties"
    grep -Fqx 'schemaVersion=1' "$pb_receipt" &&
        grep -Fqx "packageName=$PB_ID" "$pb_receipt" &&
        grep -Fqx "version=$PB_VERSION" "$pb_receipt" &&
    case "$PB_ROLE" in
        WINE)
            case "$PB_ID" in
                rootfs_wine_10.0) pb_expected=/opt/box64-wine ;;
                rootfs_proton_11.0) pb_expected=/opt/box64-proton ;;
                custom-wine-?*) pb_expected="/opt/custom-wine/$PB_ID" ;;
                *) return 2 ;;
            esac ;;
        BOX64)
            case "$PB_ID" in rootfs_box64_0.2.8|rootfs_box64_0.3.8|rootfs_box64_0.4.4|custom-box64-?*) ;; *) return 2 ;; esac
            pb_expected="/opt/box64/$PB_ID" ;;
        DXVK64|DXVK32)
            case "$PB_ID" in
                rootfs_dxvk_?*)
                    case "${PB_ID#rootfs_dxvk_}" in *[!0-9.]*) return 2 ;; esac
                    pb_expected="/opt/games-runtime/$PB_ID"
                    [ "$PB_ROLE" != DXVK64 ] || pb_expected="$pb_expected/system32"
                    [ "$PB_ROLE" != DXVK32 ] || pb_expected="$pb_expected/syswow64" ;;
                custom-dxvk-?*)
                    pb_expected="/opt/dxvk/$PB_ID"
                    [ "$PB_ROLE" != DXVK64 ] || pb_expected="$pb_expected/x64"
                    [ "$PB_ROLE" != DXVK32 ] || pb_expected="$pb_expected/x32" ;;
                *) return 2 ;;
            esac ;;
        *) return 2 ;;
    esac
    [ "$PB_TARGET" = "$pb_expected" ] || return 2
    case "$PB_ROLE" in DXVK64|DXVK32)
        pb_identity="$PB_ID|$PB_VERSION|$PB_SHA|$PB_VERSION_DIR"
        [ -z "$PROOT_DXVK_IDENTITY" ] || [ "$PROOT_DXVK_IDENTITY" = "$pb_identity" ] || return 2
        PROOT_DXVK_IDENTITY=$pb_identity ;;
    esac
    case "$PROOT_BINDING_ROLES" in *" $PB_ROLE "*) return 2 ;; esac
    PROOT_BINDING_ROLES="$PROOT_BINDING_ROLES$PB_ROLE "
}

proot_bindings_complete() {
    case "$PROOT_BINDING_ROLES" in
        *' DXVK64 '*) case "$PROOT_BINDING_ROLES" in *' DXVK32 '*) ;; *) return 1 ;; esac ;;
        *' DXVK32 '*) return 1 ;;
    esac
    [ -n "$PROOT_BINDING_COUNT" ] && [ "$PROOT_BINDING_COUNT" -eq "$PROOT_BINDING_INDEX" ] &&
        [ "$PROOT_BINDING_FIELD" = componentId ]
}

# Host-side DLL readers use the same frozen source as the guest. Fallback is legacy-only.
proot_binding_host_path() {
    while IFS= read -r pbh_bind; do
        [ -n "$pbh_bind" ] || continue
        pbh_source=${pbh_bind%:*}
        pbh_target=${pbh_bind##*:}
        case "$1" in
            "$pbh_target") printf '%s\n' "$pbh_source"; return 0 ;;
            "$pbh_target"/*) printf '%s/%s\n' "$pbh_source" "${1#"$pbh_target"/}"; return 0 ;;
        esac
    done <<BINDINGS
$PROOT_COMPONENT_BINDS
BINDINGS
    case "$1" in /usr/*|/etc/*) printf '%s%s\n' "$ROOTFS_CANONICAL" "$1"; return 0 ;; esac
    if [ -z "$PROOT_BINDING_COUNT" ]; then printf '%s%s\n' "$ROOTFS_CANONICAL" "$1"; return 0; fi
    return 1
}

# Must exec in the caller's process so cancellation/watchdogs still target PRoot itself.
proot_exec_bound() {
    while IFS= read -r peb_bind; do
        [ -n "$peb_bind" ] || continue
        set -- -b "$peb_bind" "$@"
    done <<BINDINGS
$PROOT_COMPONENT_BINDS
BINDINGS
    exec "$PROOT_BIN" "$@"
}

proot_component_receipt_root() {
    while IFS='|' read -r pcr_id pcr_root; do
        if [ "$pcr_id" = "$1" ]; then printf '%s\n' "$pcr_root"; return 0; fi
    done <<RECEIPTS
$PROOT_COMPONENT_RECEIPTS
RECEIPTS
    return 1
}
