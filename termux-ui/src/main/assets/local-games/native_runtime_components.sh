# Exact per-profile Native component selection. Sourced by bootstrap and game launch.
# Requires TERMUX_GLIBC_DIR, TERMUX_OPT_DIR, WINE_PATH and WINEPREFIX.
native_component_error() {
    printf 'ERROR: %s\n' "$1" >&2
    return 1
}

native_require_custom_receipt() {
    native_receipt="$1/.custom-component.properties"
    [ -f "$native_receipt" ] && grep -Fqx 'schemaVersion=1' "$native_receipt" &&
        grep -Fqx 'runtimeBackend=glibc_termux_box' "$native_receipt" &&
        grep -Fqx "componentId=$2" "$native_receipt" && grep -Fqx "kind=$3" "$native_receipt" || {
        native_component_error "custom_component_receipt_invalid:$2"; return 1;
    }
}

require_native_wine_runtime() {
    case "${TERMUX_BOX_WINE_PACKAGE:-}" in ''|*[!A-Za-z0-9._-]*) native_component_error wine_package_invalid; return 1 ;; esac
    export WINE_PATH="$TERMUX_GLIBC_DIR/$TERMUX_BOX_WINE_PACKAGE"
    export GAMES_WINESERVER_BIN="$WINE_PATH/bin/wineserver"
    case "${TERMUX_BOX_WINE_PACKAGE:-}" in
        glibc_wine_*|glibc_proton_*) ;;
        custom-wine-?*) native_require_custom_receipt "$WINE_PATH" "$TERMUX_BOX_WINE_PACKAGE" wine || return 1 ;;
        *) native_component_error wine_package_unsupported; return 1 ;;
    esac
    [ -x "$WINE_PATH/bin/wine" ] && [ -x "$WINE_PATH/bin/wineserver" ] || {
        native_component_error "wine_selected_binary_missing:$TERMUX_BOX_WINE_PACKAGE"; return 1;
    }
    if [ -f "${WINEPREFIX:-/nonexistent}/.games-runtime/native-user" ]; then
        [ ! -L "$WINEPREFIX/.games-runtime/native-user" ] || return 1
        native_user=$(cat "$WINEPREFIX/.games-runtime/native-user") || return 1
        case "$TERMUX_BOX_WINE_PACKAGE:$native_user" in
            glibc_wine_*:root|glibc_proton_11.0:steamuser) ;;
            *) native_component_error native_prefix_user_mismatch; return 1 ;;
        esac
        # Wine reads USER to select the Windows account. This does not change Android UID.
        export USER="$native_user" LOGNAME="$native_user"
    fi
}

resolve_native_box64() {
    NATIVE_GLIBC_LOADER="$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1"
    case "${GAMES_CUSTOM_BOX64_ID:-}" in
        '') NATIVE_BOX64_BIN="$TERMUX_GLIBC_DIR/components/glibc_box64_0.2.7/bin/box64" ;;
        glibc_box64_0.2.7|glibc_box64_0.4.4)
            NATIVE_BOX64_BIN="$TERMUX_GLIBC_DIR/components/$GAMES_CUSTOM_BOX64_ID/bin/box64"
            ;;
        custom-box64-?*)
            case "$GAMES_CUSTOM_BOX64_ID" in *[!A-Za-z0-9._-]*) native_component_error box64_selection_invalid; return 1 ;; esac
            NATIVE_BOX64_BIN="$TERMUX_GLIBC_DIR/components/$GAMES_CUSTOM_BOX64_ID/bin/box64"
            native_require_custom_receipt "$TERMUX_GLIBC_DIR/components/$GAMES_CUSTOM_BOX64_ID" "$GAMES_CUSTOM_BOX64_ID" box64 || return 1
            ;;
        *) native_component_error box64_selection_unsupported; return 1 ;;
    esac
    [ -x "$NATIVE_GLIBC_LOADER" ] || { native_component_error glibc_loader_missing; return 1; }
    [ -x "$NATIVE_BOX64_BIN" ] || { native_component_error "box64_selected_binary_missing:${GAMES_CUSTOM_BOX64_ID:-runtime}"; return 1; }
    export NATIVE_GLIBC_LOADER NATIVE_BOX64_BIN
}

native_run_box64() {
    "$NATIVE_GLIBC_LOADER" --library-path "$TERMUX_GLIBC_DIR/lib" "$NATIVE_BOX64_BIN" "$@"
}

native_dx_archive_name() {
    case "$1" in
        native-dxvk-0.96|native-dxvk-1.6.1|native-dxvk-async-1.10.3|native-dxvk-async-2.0|native-dxvk-dev|native-dxvk-gplasync-2.2|native-dxvk-gplasync-2.3|native-vkd3d)
            printf '%s.7z\n' "${1#native-}" ;;
        *) native_component_error "dx_wrapper_unsupported:$1"; return 1 ;;
    esac
}

native_turnip_archive_name() {
    case "$1" in
        native-turnip-v2|native-turnip-v3.5|native-turnip-v4|native-turnip-v5|native-turnip-v6.5)
            printf '%s.7z\n' "${1#native-}" ;;
        *) native_component_error "renderer_unsupported:$1"; return 1 ;;
    esac
}

# Catalog bundles are immutable component sources. Unpack only the selected archive under its
# own ID. Never publish a selected driver or Direct3D layer over another version's global files.
native_unpack_archive() {
    native_archive=$1
    native_destination=$2
    [ -f "$native_archive" ] || { native_component_error "component_archive_missing:$native_archive"; return 1; }
    [ -f "$native_destination/.unpacked" ] && return 0
    command -v 7z >/dev/null 2>&1 || { native_component_error p7zip_missing; return 1; }
    native_stage="$native_destination.stage.$$"
    rm -rf "$native_stage"
    mkdir -p "$native_stage" || return 1
    if ! 7z x "$native_archive" "-o$native_stage" -y >/dev/null 2>&1; then
        rm -rf "$native_stage"
        native_component_error "component_archive_unpack_failed:$native_archive"
        return 1
    fi
    : > "$native_stage/.unpacked" || return 1
    # Termux coreutils mv -T -n publishes atomically and never nests a losing concurrent
    # stage inside another process's published directory. A killed process leaves only its stage.
    mv -T -n "$native_stage" "$native_destination" || return 1
    [ ! -d "$native_stage" ] || rm -rf "$native_stage"
    [ -f "$native_destination/.unpacked" ] || { native_component_error component_archive_publish_failed; return 1; }
}

apply_native_graphics_component() {
    NATIVE_VIRGL=0
    case "$TERMUX_BOX_GRAPHICS_DRIVER" in
        custom-turnip-?*)
            case "$TERMUX_BOX_GRAPHICS_DRIVER" in *[!A-Za-z0-9._-]*) native_component_error renderer_invalid; return 1 ;; esac
            NATIVE_TURNIP_ROOT="$TERMUX_GLIBC_DIR/components/$TERMUX_BOX_GRAPHICS_DRIVER"
            native_require_custom_receipt "$NATIVE_TURNIP_ROOT" "$TERMUX_BOX_GRAPHICS_DRIVER" turnip || return 1
            ;;
        native-turnip-*)
            native_turnip_archive=$(native_turnip_archive_name "$TERMUX_BOX_GRAPHICS_DRIVER") || return 1
            NATIVE_TURNIP_ROOT="$TERMUX_GLIBC_DIR/components/$TERMUX_BOX_GRAPHICS_DRIVER"
            native_unpack_archive "$TERMUX_OPT_DIR/libs/mesa/$native_turnip_archive" "$NATIVE_TURNIP_ROOT" || return 1
            ;;
        virgl|virgl-mesa)
            [ -x "$TERMUX_OPT_DIR/virgl/libvirgl_test_server.so" ] || { native_component_error virgl_selected_binary_missing; return 1; }
            case "$TERMUX_BOX_DXWRAPPER" in wined3d) ;; *) native_component_error runtime_combination_unsupported:virgl_vulkan; return 1 ;; esac
            NATIVE_VIRGL=1
            unset VK_ICD_FILENAMES VK_DRIVER_FILES
            export GALLIUM_DRIVER=virpipe
            return 0
            ;;
        *) native_component_error "renderer_unsupported:$TERMUX_BOX_GRAPHICS_DRIVER"; return 1 ;;
    esac
    [ -s "$NATIVE_TURNIP_ROOT/lib/libvulkan_freedreno.so" ] || { native_component_error turnip_selected_library_missing; return 1; }
    # The ICD lives in this prefix, so two profiles can select different drivers without a
    # shared freedreno_icd.json or libvulkan_freedreno.so being overwritten.
    mkdir -p "$WINEPREFIX/termux-boxmeta" || return 1
    NATIVE_TURNIP_ICD="$WINEPREFIX/termux-boxmeta/turnip-icd.json"
    printf '{"file_format_version":"1.0.0","ICD":{"library_path":"%s/lib/libvulkan_freedreno.so","api_version":"1.3.255"}}\n' \
        "$NATIVE_TURNIP_ROOT" > "$NATIVE_TURNIP_ICD" || return 1
    export VK_ICD_FILENAMES="$NATIVE_TURNIP_ICD"
    export VK_DRIVER_FILES="$NATIVE_TURNIP_ICD"
    unset GALLIUM_DRIVER
}

native_copy_dlls() {
    native_source=$1
    native_target=$2
    native_count=0
    [ -d "$native_source" ] || { native_component_error "direct3d_selected_payload_missing:$native_source"; return 1; }
    mkdir -p "$native_target" || return 1
    for native_dll in "$native_source"/*.dll; do
        [ -f "$native_dll" ] || continue
        # A prefix may contain a symlink to a global runtime DLL. Replace the link itself.
        rm -f "$native_target/${native_dll##*/}" || return 1
        cp "$native_dll" "$native_target/${native_dll##*/}" || return 1
        native_count=$((native_count + 1))
    done
    [ "$native_count" -gt 0 ] || { native_component_error "direct3d_selected_payload_empty:$native_source"; return 1; }
}

clear_native_dx_dlls() {
    # Remove every managed layer first. Wine builtin loading is explicit in WineD3D mode;
    # stale DXVK/VKD3D DLLs must never survive a version/family switch.
    for native_dll_name in d3d8 d3d9 d3d10 d3d10_1 d3d10core d3d11 dxgi d3d12 d3d12core; do
        rm -f "$WINEPREFIX/drive_c/windows/system32/$native_dll_name.dll" \
            "$WINEPREFIX/drive_c/windows/syswow64/$native_dll_name.dll" || return 1
    done
}

apply_native_dx_component() {
    native_system32="$WINEPREFIX/drive_c/windows/system32"
    native_syswow64="$WINEPREFIX/drive_c/windows/syswow64"
    case "$TERMUX_BOX_DXWRAPPER" in
        wined3d)
            clear_native_dx_dlls || return 1
            NATIVE_D3D_OVERRIDES='d3d8,d3d9,d3d10,d3d10_1,d3d10core,d3d11,dxgi,d3d12,d3d12core=b'
            return 0
            ;;
        custom-dxvk-?*|custom-vkd3d-?*)
            case "$TERMUX_BOX_DXWRAPPER" in *[!A-Za-z0-9._-]*) native_component_error dx_wrapper_invalid; return 1 ;; esac
            native_dx_root="$TERMUX_GLIBC_DIR/components/$TERMUX_BOX_DXWRAPPER"
            native_dx_64="$native_dx_root/x64"
            case "$TERMUX_BOX_DXWRAPPER" in
                custom-dxvk-*) native_dx_32="$native_dx_root/x32"; native_require_custom_receipt "$native_dx_root" "$TERMUX_BOX_DXWRAPPER" dxvk || return 1 ;;
                custom-vkd3d-*) native_dx_32="$native_dx_root/x86"; native_require_custom_receipt "$native_dx_root" "$TERMUX_BOX_DXWRAPPER" vkd3d || return 1 ;;
            esac
            ;;
        native-*)
            native_dx_archive=$(native_dx_archive_name "$TERMUX_BOX_DXWRAPPER") || return 1
            native_dx_root="$TERMUX_GLIBC_DIR/components/$TERMUX_BOX_DXWRAPPER"
            native_unpack_archive "$TERMUX_OPT_DIR/libs/d3d/$native_dx_archive" "$native_dx_root" || return 1
            native_dx_64="$native_dx_root/system32"
            native_dx_32="$native_dx_root/syswow64"
            ;;
        *) native_component_error "dx_wrapper_unsupported:$TERMUX_BOX_DXWRAPPER"; return 1 ;;
    esac
    # Reject missing versions before changing any DLL in the existing prefix.
    set -- "$native_dx_64"/*.dll
    [ -f "$1" ] || { native_component_error direct3d_selected_payload_missing; return 1; }
    set -- "$native_dx_32"/*.dll
    [ -f "$1" ] || { native_component_error direct3d_selected_payload_missing; return 1; }
    clear_native_dx_dlls || return 1
    native_copy_dlls "$native_dx_64" "$native_system32" || return 1
    native_copy_dlls "$native_dx_32" "$native_syswow64" || return 1
    case "$TERMUX_BOX_DXWRAPPER" in
        *vkd3d*) NATIVE_D3D_OVERRIDES='d3d12,d3d12core=n;d3d8,d3d9,d3d10,d3d10_1,d3d10core,d3d11,dxgi=b' ;;
        *) NATIVE_D3D_OVERRIDES='d3d8,d3d9,d3d10,d3d10_1,d3d10core,d3d11,dxgi=n;d3d12,d3d12core=b' ;;
    esac
}
