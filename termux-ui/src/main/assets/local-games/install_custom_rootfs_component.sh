#!/data/data/com.termux/files/usr/bin/sh
EXPECTED_BACKEND=rootfs_proot
# A component import is an isolated archive copy, never apt/pkg/deb execution.
# runtimeBackend is mandatory; selecting the other installer is an error.
set -eu

SPEC_PATH=${1:?Usage: custom-component-installer <install-spec>}
SCRIPT_PATH=$(realpath "$0") || exit 2
SPEC_VERSION=
TASK_ID= KIND= COMPONENT_ID= PAYLOAD_PATH= PAYLOAD_KIND= EVENTS_PATH= LOG_PATH= RUNTIME_BACKEND= RUNTIME_TRANSLATOR= PAYLOAD_SHA256=
OUTPUTS_READY=false
COMPLETED=false
PUBLISHED=false
FAILURE_CODE=custom_install_script_failed
STAGE_DIR= DEST=

fail() { FAILURE_CODE=$1; exit "${2:-1}"; }
on_exit() {
    status=$?
    if [ "$status" -ne 0 ] && [ "$COMPLETED" = false ]; then
        if [ "$PUBLISHED" = true ] && [ -n "$DEST" ]; then rm -rf "$DEST"; fi
        if [ "$OUTPUTS_READY" = true ]; then
            printf '{"schemaVersion":1,"taskId":"%s","state":"FAILED","errorCode":"%s"}\n' \
                "$TASK_ID" "$FAILURE_CODE" >> "$EVENTS_PATH" 2>/dev/null || true
        fi
        [ "$OUTPUTS_READY" = false ] || printf 'FAILED: %s (exit=%s)\n' "$FAILURE_CODE" "$status" >> "$LOG_PATH" 2>/dev/null || true
    fi
    if [ -n "$STAGE_DIR" ]; then
        # Archives may carry read-only directories; make our private stage removable.
        find "$STAGE_DIR" -type d -exec chmod u+rwx {} + 2>/dev/null || true
        rm -rf "$STAGE_DIR"
    fi
}
trap on_exit EXIT
trap 'fail custom_install_interrupted 130' INT TERM

while IFS='=' read -r key value; do
    case "$key" in
        schemaVersion) SPEC_VERSION=$value ;;
        taskId) TASK_ID=$value ;;
        kind) KIND=$value ;;
        runtimeBackend) RUNTIME_BACKEND=$value ;;
        runtimeTranslator) RUNTIME_TRANSLATOR=$value ;;
        componentId) COMPONENT_ID=$value ;;
        payloadPath) PAYLOAD_PATH=$value ;;
        payloadKind) PAYLOAD_KIND=$value ;;
        payloadSha256) PAYLOAD_SHA256=$value ;;
        eventsPath) EVENTS_PATH=$value ;;
        logPath) LOG_PATH=$value ;;
        '') ;;
        *) fail invalid_custom_install_spec 64 ;;
    esac
done < "$SPEC_PATH"
# Validate identifiers before using them in paths or JSON.
case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail invalid_custom_install_identifier 64 ;; esac
case "$KIND" in wine|box64|dxvk|vkd3d|turnip) ;; *) fail invalid_custom_install_kind 64 ;; esac
case "$COMPONENT_ID" in "custom-$KIND-"?*) ;; *) fail invalid_custom_component_id 64 ;; esac
case "$COMPONENT_ID" in *[!A-Za-z0-9._-]*) fail invalid_custom_component_id 64 ;; esac
[ "$PAYLOAD_KIND" = "$KIND-tree" ] || fail custom_install_kind_mismatch 64

PRIVATE_ROOT=${SPEC_PATH%/runtime/custom-install/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || fail invalid_custom_install_spec_path 64
TERMUX_FILES_ROOT=${PRIVATE_ROOT%/front}
[ "$TERMUX_FILES_ROOT" != "$PRIVATE_ROOT" ] || fail invalid_custom_install_spec_path 64
# Canonical private paths only. Reject symlinked output directories as well as textual traversal.
for path in "$SPEC_PATH" "$EVENTS_PATH" "$LOG_PATH" "$PAYLOAD_PATH"; do
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail custom_install_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_custom_install_private_path 64 ;; esac
    canonical=$(realpath -m "$path") || fail invalid_custom_install_private_path 64
    [ "$canonical" = "$path" ] || fail invalid_custom_install_private_path 64
done
mkdir -p "${EVENTS_PATH%/*}" "${LOG_PATH%/*}"
: >> "$LOG_PATH"
OUTPUTS_READY=true
[ "$SPEC_VERSION" = 1 ] || fail unsupported_custom_install_spec 64
[ "$RUNTIME_BACKEND" = "$EXPECTED_BACKEND" ] || fail custom_install_backend_mismatch 64
if [ "$RUNTIME_BACKEND" = rootfs_proot ]; then
    case "$KIND" in wine|box64|dxvk) ;; *) fail custom_component_backend_unsupported 64 ;; esac
fi
case "$KIND:$RUNTIME_BACKEND:$RUNTIME_TRANSLATOR" in
    wine:rootfs_proot:hangover|wine:rootfs_proot:box64|wine:glibc_termux_box:none) ;;
    wine:*) fail custom_wine_translator_mismatch 64 ;;
    *:*:none) ;;
    *) fail custom_component_translator_not_allowed 64 ;;
esac
. "$(dirname "$SCRIPT_PATH")/rootfs_script_common.sh"
[ -f "$PAYLOAD_PATH" ] || fail custom_component_payload_missing 70
case "$PAYLOAD_SHA256" in ''|*[!a-f0-9]*) fail custom_component_hash_invalid 64 ;; esac
[ "${#PAYLOAD_SHA256}" = 64 ] || fail custom_component_hash_invalid 64

TERMUX_GLIBC_DIR="$TERMUX_FILES_ROOT/usr/glibc"
SHARED_CONTAINER="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_CONTAINER/rootfs"
if [ "$RUNTIME_BACKEND" = glibc_termux_box ]; then
    [ -x "$TERMUX_GLIBC_DIR/bin/box64" ] && [ -f "$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1" ] || fail glibc_runtime_not_installed 70
    case "$KIND" in
        wine) DEST="$TERMUX_GLIBC_DIR/$COMPONENT_ID" ;;
        *) DEST="$TERMUX_GLIBC_DIR/components/$COMPONENT_ID" ;;
    esac
    STAGE_BASE="$TERMUX_GLIBC_DIR/.custom-install-stage"
else
    [ -x "$SHARED_ROOTFS/usr/bin/env" ] && [ -x "$SHARED_ROOTFS/usr/bin/wine" ] &&
        [ -f "$SHARED_ROOTFS/etc/games-runtime.properties" ] || fail rootfs_not_built_yet 70
    COMPONENT_PACKAGE_ROOT="$PRIVATE_ROOT/components/install/$COMPONENT_ID"
    COMPONENT_VERSION_NAME="1-$PAYLOAD_SHA256"
    DEST="$COMPONENT_PACKAGE_ROOT/versions/$COMPONENT_VERSION_NAME"
    STAGE_BASE="$COMPONENT_PACKAGE_ROOT/.custom-install-stage"
fi
[ ! -e "$DEST" ] && [ ! -L "$DEST" ] || fail custom_component_already_installed 73
mkdir -p "$(dirname "$DEST")" "$STAGE_BASE"
STAGE_DIR=$(mktemp -d "$STAGE_BASE/$COMPONENT_ID.XXXXXX") || fail custom_install_staging_directory_failed 70
EXTRACT="$STAGE_DIR/extract"
PUBLISH="$STAGE_DIR/publish"
mkdir "$EXTRACT" "$PUBLISH"
printf '{"schemaVersion":1,"taskId":"%s","state":"RUNNING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

progress '==> Validating the local component archive'
# GNU tar is supplied by Termux. Never retry an unknown format using a different extraction mode.
MAGIC=$(od -An -tx1 -N 6 "$PAYLOAD_PATH" | tr -d ' \n')
case "$MAGIC" in
    fd377a585a00*) COMPRESSION=-J ;;
    1f8b*) COMPRESSION=-z ;;
    28b52ffd*) COMPRESSION=--zstd ;;
    *)
        TAR_MAGIC=$(dd if="$PAYLOAD_PATH" bs=1 skip=257 count=5 2>/dev/null)
        [ "$TAR_MAGIC" = ustar ] || fail custom_archive_format_unsupported 64
        COMPRESSION= ;;
esac
# Listing must succeed before extraction. Quoting makes control characters visible; reject them.
tar $COMPRESSION --quoting-style=escape -tf "$PAYLOAD_PATH" > "$STAGE_DIR/members" || fail custom_archive_invalid 64
[ -s "$STAGE_DIR/members" ] || fail custom_archive_empty 64
while IFS= read -r member; do
    case "$member" in /*|..|../*|*/../*|*/..|*'\'*|'') fail custom_archive_unsafe_path 64 ;; esac
done < "$STAGE_DIR/members"
tar $COMPRESSION --quoting-style=escape -tvf "$PAYLOAD_PATH" > "$STAGE_DIR/listing" || fail custom_archive_invalid 64
# Device nodes, FIFOs and sockets have no place in component archives.
awk 'substr($0,1,1) !~ /^[-dlh]$/ {exit 1}' "$STAGE_DIR/listing" || fail custom_archive_unsafe_entry 64
# GNU tar refuses symlink traversal and hardlinks outside the extraction root. Duplicate files
# are errors; no ownership or special permissions from an untrusted archive are retained.
tar $COMPRESSION --no-same-owner --no-same-permissions --keep-old-files -xf "$PAYLOAD_PATH" -C "$EXTRACT" >> "$LOG_PATH" 2>&1 || fail custom_archive_extract_failed 64
validate_links() {
    checked_root=$1
    find "$checked_root" -type l > "$STAGE_DIR/links"
    while IFS= read -r link; do
        resolved=$(realpath -e "$link") || fail custom_archive_unsafe_link 64
        case "$resolved" in "$checked_root"/*) ;; *) fail custom_archive_unsafe_link 64 ;; esac
    done < "$STAGE_DIR/links"
}
validate_links "$EXTRACT"
find "$EXTRACT" -type d -exec chmod u+rwx,go-rwx {} +
find "$EXTRACT" -type f -exec chmod u+rw,u-s,g-s {} +

require_aarch64() {
    header=$(od -An -tx1 -N 20 "$1" | tr -d ' \n')
    case "$header" in 7f454c460201*) ;; *) fail custom_component_not_elf64_le 64 ;; esac
    [ "$(printf '%s' "$header" | cut -c37-40)" = b700 ] || fail custom_component_not_aarch64 64
}
require_wine_abi() {
    # The chosen backend and translator determine one ABI; never probe another execution mode.
    case "$RUNTIME_BACKEND:$RUNTIME_TRANSLATOR" in
        glibc_termux_box:none|rootfs_proot:box64) wine_machine=3e00 ;;
        rootfs_proot:hangover) wine_machine=b700 ;;
        *) fail custom_wine_translator_mismatch 64 ;;
    esac
    wine_header=$(od -An -tx1 -N 20 "$1" | tr -d ' \n')
    case "$wine_header" in 7f454c460201*) ;; *) fail custom_wine_arch_mismatch 64 ;; esac
    [ "$(printf '%s' "$wine_header" | cut -c37-40)" = "$wine_machine" ] || fail custom_wine_arch_mismatch 64
    [ "$(wc -c < "$1" | tr -d ' ')" -ge 64 ] || fail custom_wine_arch_mismatch 64
}
require_pe() {
    pe_file=$1 expected_machine=$2
    magic=$(od -An -tx1 -N 2 "$pe_file" | tr -d ' \n')
    [ "$magic" = 4d5a ] || fail custom_component_not_pe_dll 64
    # PE's e_lfanew is a little-endian 32-bit offset, independent of the host architecture.
    set -- $(od -An -tu1 -j 60 -N 4 "$pe_file")
    [ "$#" = 4 ] || fail custom_component_not_pe_dll 64
    pe_offset=$(($1 + $2 * 256 + $3 * 65536 + $4 * 16777216))
    pe_signature=$(od -An -tx1 -j "$pe_offset" -N 6 "$pe_file" | tr -d ' \n')
    [ "$pe_signature" = "50450000$expected_machine" ] || fail custom_component_dll_arch_mismatch 64
    set -- $(od -An -tu1 -j "$((pe_offset + 22))" -N 2 "$pe_file")
    [ "$#" = 2 ] || fail custom_component_not_pe_dll 64
    [ "$(($2 & 32))" = 32 ] || fail custom_component_not_pe_dll 64
}
unique_file() {
    find "$EXTRACT" -type f -name "$1" > "$STAGE_DIR/matches"
    [ "$(wc -l < "$STAGE_DIR/matches" | tr -d ' ')" = 1 ] || fail custom_component_binary_missing_or_ambiguous 64
    IFS= read -r FOUND < "$STAGE_DIR/matches"
}

progress "==> Preparing $COMPONENT_ID for $RUNTIME_BACKEND"
case "$KIND" in
    wine)
        find "$EXTRACT" \( -type f -o -type l \) -path '*/bin/wine' > "$STAGE_DIR/matches"
        [ "$(wc -l < "$STAGE_DIR/matches" | tr -d ' ')" = 1 ] || fail custom_wine_binary_missing_or_ambiguous 64
        IFS= read -r wine_bin < "$STAGE_DIR/matches"
        source_root=${wine_bin%/bin/wine}
        [ -f "$source_root/bin/wineboot" ] && [ -f "$source_root/bin/wineserver" ] || fail custom_wine_missing_runtime_binary 64
        [ -d "$source_root/lib/wine" ] || [ -d "$source_root/lib64/wine" ] || fail custom_wine_not_a_wine_build 64
        require_wine_abi "$source_root/bin/wine"
        require_wine_abi "$source_root/bin/wineserver"
        # Keep executable and DLL links self-contained after removing the archive's wrapper dirs.
        validate_links "$source_root"
        cp -a "$source_root/." "$PUBLISH/"
        chmod +x "$PUBLISH/bin/wine" "$PUBLISH/bin/wineboot" "$PUBLISH/bin/wineserver"
        ;;
    box64)
        unique_file box64
        require_aarch64 "$FOUND"
        mkdir "$PUBLISH/bin"
        cp "$FOUND" "$PUBLISH/bin/box64"
        chmod 755 "$PUBLISH/bin/box64"
        if [ "$RUNTIME_BACKEND" = glibc_termux_box ]; then
            # A version probe detects an incompatible libc/loader without changing this build.
            # Android W^X/JIT compatibility is verified at launch; failure never selects another version.
            run_logged env -u LD_PRELOAD timeout 15 "$TERMUX_GLIBC_DIR/lib/ld-linux-aarch64.so.1" \
                --library-path "$TERMUX_GLIBC_DIR/lib" "$PUBLISH/bin/box64" --version || fail custom_box64_native_incompatible 64
        fi
        ;;
    dxvk|vkd3d)
        if [ "$KIND" = dxvk ]; then probe_dll=d3d11.dll; low_dest=x32; else probe_dll=d3d12.dll; low_dest=x86; fi
        find "$EXTRACT" -type f -path "*/x64/$probe_dll" > "$STAGE_DIR/matches"
        [ "$(wc -l < "$STAGE_DIR/matches" | tr -d ' ')" = 1 ] || fail custom_component_dll_layout_invalid 64
        IFS= read -r probe_path < "$STAGE_DIR/matches"
        dll_root=${probe_path%/x64/*}
        # DXVK upstream uses x32, some packages use x86. Reject ambiguous layouts.
        if [ -d "$dll_root/x32" ] && [ -d "$dll_root/x86" ]; then fail custom_component_dll_layout_ambiguous 64; fi
        if [ -d "$dll_root/x32" ]; then low_source=x32; else low_source=x86; fi
        [ -f "$dll_root/$low_source/$probe_dll" ] || fail custom_component_dll_layout_invalid 64
        if [ "$KIND" = dxvk ]; then
            [ -f "$dll_root/x64/dxgi.dll" ] && [ -f "$dll_root/$low_source/dxgi.dll" ] || fail custom_component_dll_layout_invalid 64
        else
            [ -f "$dll_root/x64/d3d12core.dll" ] && [ -f "$dll_root/$low_source/d3d12core.dll" ] || fail custom_component_dll_layout_invalid 64
        fi
        for architecture in "$low_source" x64; do
            if [ "$architecture" = x64 ]; then output_arch=x64; machine=6486; else output_arch=$low_dest; machine=4c01; fi
            mkdir "$PUBLISH/$output_arch"
            find "$dll_root/$architecture" -maxdepth 1 -type f -name '*.dll' > "$STAGE_DIR/dlls"
            while IFS= read -r dll; do
                require_pe "$dll" "$machine"
                cp "$dll" "$PUBLISH/$output_arch/"
            done < "$STAGE_DIR/dlls"
        done
        ;;
    turnip)
        unique_file libvulkan_freedreno.so
        require_aarch64 "$FOUND"
        mkdir "$PUBLISH/lib"
        cp "$FOUND" "$PUBLISH/lib/libvulkan_freedreno.so"
        ;;
esac
validate_links "$PUBLISH"

# Publish the receipt in the same rename as the payload; no half-installed registry entries.
printf 'schemaVersion=1\nruntimeBackend=%s\nruntimeTranslator=%s\nkind=%s\ncomponentId=%s\nsha256=%s\n' \
    "$RUNTIME_BACKEND" "$RUNTIME_TRANSLATOR" "$KIND" "$COMPONENT_ID" "$PAYLOAD_SHA256" > "$PUBLISH/.custom-component.properties"
if [ "$RUNTIME_BACKEND" = rootfs_proot ]; then
    printf 'schemaVersion=1\npackageName=%s\nversion=1\nsha256=%s\n' \
        "$COMPONENT_ID" "$PAYLOAD_SHA256" > "$PUBLISH/.games-component.properties"
fi
progress "==> Publishing $COMPONENT_ID"
# Same filesystem rename; -n preserves an existing version even if an outside writer raced us.
mv -T -n "$PUBLISH" "$DEST" || fail custom_component_publish_failed 70
[ ! -d "$PUBLISH" ] || fail custom_component_already_installed 73
PUBLISHED=true
if [ "$RUNTIME_BACKEND" = rootfs_proot ]; then
    printf 'schemaVersion=1\nactive=%s\nprevious=\n' "$COMPONENT_VERSION_NAME" > "$COMPONENT_PACKAGE_ROOT/active.properties.tmp"
    mv "$COMPONENT_PACKAGE_ROOT/active.properties.tmp" "$COMPONENT_PACKAGE_ROOT/active.properties" || fail custom_component_publish_failed 70
fi
progress '==> Custom component install completed'
printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
COMPLETED=true
