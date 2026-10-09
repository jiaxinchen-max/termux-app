#!/data/data/com.termux/files/usr/bin/sh
# Installs a user-picked local Wine or Box64 build into the already-published shared RootFS --
# an incremental, single-component add, distinct from setup_rootfs_runtime.sh's full base-image
# build/publish pipeline. Spec format (see CustomComponentInstallSpecCodec):
#   schemaVersion=1
#   taskId=<id>
#   kind=wine|box64
#   componentId=<custom-wine-<slug>|custom-box64-<slug>>
#   payloadPath=<app-private staged copy of the SAF-picked file, canonical>
#   payloadKind=wine-tree|box64-tree
#   eventsPath=<private events jsonl path, under .../games/runtime/custom-install/events/>
#   logPath=<private log path, under .../games/runtime/custom-install/logs/>
#
# Both kinds are a tar(.xz/.gz/.zst/.tar) archive, detected and extracted identically (by content,
# not by the payload file's name/extension -- the staged file is named after its taskId, not the
# original SAF-picked display name). No .deb/apt-get path exists for either: apt-get install would
# let an arbitrary .deb's dependency resolution touch/replace shared libraries used by every other
# container, which is unacceptable for a user-supplied package nobody has reviewed. Everything here
# is a plain, isolated file copy -- never a package manager -- so a bad pick can only ever leave its
# own dedicated /opt/custom-*/<componentId>/ directory in a bad state, never the rest of the RootFS.
#
# wine:wine-tree  -- archive containing bin/wine+bin/wineboot+lib*/wine at some depth (same
#                     content-detection runtime-rootfs/setup-container.sh already uses for its own
#                     portable-Wine-build loop). The whole wine tree is copied to
#                     /opt/custom-wine/<componentId>/ inside the shared RootFS.
# box64:box64-tree -- archive containing a `box64` executable at some depth. Only that one binary
#                     is copied, to /opt/custom-box64/<componentId>/box64 -- a dedicated path, never
#                     /usr/local/bin/box64 or any other system location.

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: install_custom_rootfs_component.sh <install-spec>}
SCRIPT_PATH=$(realpath "$0" 2>/dev/null) || {
    printf '%s\n' custom_install_script_unreadable >&2
    exit 2
}

TASK_ID=
KIND=
COMPONENT_ID=
PAYLOAD_PATH=
PAYLOAD_KIND=
EVENTS_PATH=
LOG_PATH=
COMPLETED=false
FAILURE_CODE=custom_install_script_failed

on_exit() {
    status=$?
    if [ "$status" -ne 0 ] && [ "$COMPLETED" = false ] && [ -n "$EVENTS_PATH" ]; then
        event_directory=${EVENTS_PATH%/*}
        mkdir -p "$event_directory" 2>/dev/null || true
        if [ -n "$LOG_PATH" ]; then
            log_directory=${LOG_PATH%/*}
            mkdir -p "$log_directory" 2>/dev/null || true
            printf 'FAILED: %s (exit=%s)\n' "$FAILURE_CODE" "$status" \
                >> "$LOG_PATH" 2>/dev/null || true
        fi
        printf '{"schemaVersion":1,"taskId":"%s","state":"FAILED","errorCode":"%s"}\n' \
            "$TASK_ID" "$FAILURE_CODE" >> "$EVENTS_PATH" 2>/dev/null || true
    fi
    [ -z "${STAGE_DIR:-}" ] || rm -rf "$STAGE_DIR" 2>/dev/null || true
}
trap on_exit EXIT

fail() {
    FAILURE_CODE=$1
    exit "${2:-1}"
}

while IFS='=' read -r key value; do
    case "$key" in
        schemaVersion) [ "$value" = 1 ] || fail unsupported_custom_install_spec 64 ;;
        taskId) TASK_ID=$value ;;
        kind) KIND=$value ;;
        componentId) COMPONENT_ID=$value ;;
        payloadPath) PAYLOAD_PATH=$value ;;
        payloadKind) PAYLOAD_KIND=$value ;;
        eventsPath) EVENTS_PATH=$value ;;
        logPath) LOG_PATH=$value ;;
        '') ;;
        *) fail invalid_custom_install_spec 64 ;;
    esac
done < "$SPEC_PATH"

case "$KIND" in wine|box64) ;; *) fail invalid_custom_install_spec 64 ;; esac
case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail invalid_custom_install_identifier 64 ;; esac
case "$COMPONENT_ID" in
    custom-wine-*) [ "$KIND" = wine ] || fail custom_install_kind_mismatch 64 ;;
    custom-box64-*) [ "$KIND" = box64 ] || fail custom_install_kind_mismatch 64 ;;
    *) fail invalid_custom_component_id 64 ;;
esac
case "$COMPONENT_ID" in *[!A-Za-z0-9._-]*) fail invalid_custom_component_id 64 ;; esac
case "$PAYLOAD_KIND" in wine-tree|box64-tree) ;; *) fail invalid_custom_install_spec 64 ;; esac
case "$KIND:$PAYLOAD_KIND" in
    wine:wine-tree|box64:box64-tree) ;;
    *) fail custom_component_kind_unsupported 64 ;;
esac
case "$PAYLOAD_PATH" in /*) ;; *) fail invalid_custom_install_payload_path 64 ;; esac
case "$PAYLOAD_PATH" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_custom_install_payload_path 64 ;; esac

PRIVATE_ROOT=${SPEC_PATH%/runtime/custom-install/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || \
    fail invalid_custom_install_spec_path 64
case "$SPEC_PATH" in "$PRIVATE_ROOT"/runtime/custom-install/specs/*.installspec) ;; *)
    fail invalid_custom_install_spec_path 64 ;;
esac
# Same Java-getCanonicalPath()-derived convention as setup_rootfs_runtime.sh/backup_restore_rootfs.sh.
TERMUX_FILES_ROOT=${PRIVATE_ROOT%/games}
[ -n "$TERMUX_FILES_ROOT" ] && [ "$TERMUX_FILES_ROOT" != "$PRIVATE_ROOT" ] || \
    fail invalid_custom_install_spec_path 64
for path in "$EVENTS_PATH" "$LOG_PATH" "$PAYLOAD_PATH"; do
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail custom_install_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_custom_install_private_path 64 ;; esac
done

events_directory=${EVENTS_PATH%/*}
logs_directory=${LOG_PATH%/*}
[ "$events_directory" != "$EVENTS_PATH" ] && [ "$logs_directory" != "$LOG_PATH" ] || \
    fail invalid_custom_install_output_path 64
mkdir -p "$events_directory" "$logs_directory" || fail custom_install_output_directory_failed 70
: >> "$LOG_PATH"

# Co-located with this script by LaunchScriptInstaller (same runtimeDirectory). Defines progress(),
# run_logged(), run_logged_watchdog().
. "$(dirname "$SCRIPT_PATH")/rootfs_script_common.sh"

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
SHARED_ROOTFS_CONTAINER_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY/rootfs"
STAGE_DIR="$SHARED_ROOTFS_CONTAINER_DIRECTORY/.custom-install-stage/$COMPONENT_ID"

[ -f "$PAYLOAD_PATH" ] || fail custom_component_payload_missing 70

# Same completeness check setup_rootfs_runtime.sh/backup_restore_rootfs.sh use.
runtime_complete() {
    root=$1
    [ -x "$root/usr/bin/env" ] &&
        [ -x "$root/usr/local/bin/box64" ] &&
        [ -x "$root/usr/bin/wine" ] &&
        [ -x "$root/usr/bin/wineboot" ] &&
        [ -f "$root/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" ] &&
        [ -f "$root/etc/games-runtime.properties" ] &&
        [ -d "$root/mnt/games/game" ] &&
        [ -d "$root/mnt/games/prefix" ]
}

progress '==> Checking the shared base environment is built'
runtime_complete "$SHARED_ROOTFS" || fail rootfs_not_built_yet 70

printf '{"schemaVersion":1,"taskId":"%s","state":"RUNNING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

progress '==> Extracting the custom build'
# Detected by content (magic bytes), not by the payload file's name/extension -- see this script's
# header comment. Matches this codebase's existing "install by content" convention (see
# runtime-rootfs/setup-container.sh's own header comment on the same principle for the base-image
# build).
MAGIC=$(od -An -tx1 -N 6 "$PAYLOAD_PATH" 2>/dev/null | tr -d ' \n')
case "$MAGIC" in
    fd377a585a00*) run_logged tar -C "$STAGE_DIR" -J -xpf "$PAYLOAD_PATH" ;;
    1f8b*) run_logged tar -C "$STAGE_DIR" -z -xpf "$PAYLOAD_PATH" ;;
    28b52ffd*) run_logged tar -C "$STAGE_DIR" --zstd -xpf "$PAYLOAD_PATH" ;;
    *)
        # Not a recognized compressed-archive magic -- try as a plain uncompressed tar (ustar
        # magic at offset 257) before giving up.
        if ! run_logged tar -C "$STAGE_DIR" -xpf "$PAYLOAD_PATH"; then
            fail custom_archive_format_unsupported 64
        fi
        ;;
esac

case "$KIND:$PAYLOAD_KIND" in
    wine:wine-tree)
        WINEBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wine -path '*/bin/*' | head -n 1)
        [ -n "$WINEBIN" ] || fail custom_wine_missing_wine_binary 70
        WINEBOOTBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wineboot -path '*/bin/*' | head -n 1)
        [ -n "$WINEBOOTBIN" ] || fail custom_wine_missing_wineboot_binary 70
        # A genuine Wine build ships its PE/builtin DLL tree under lib/wine (or lib64/wine) -- bin/
        # wine and bin/wineboot alone can be present in an unrelated archive, so require the DLL
        # tree too before trusting this as a real Wine build. All still in staging: an archive that
        # fails here has not touched the shared RootFS.
        if ! find "$STAGE_DIR" -type d -path '*/lib*/wine' 2>/dev/null | grep -q .; then
            fail custom_wine_not_a_wine_build 64
        fi
        SRCROOT=$(dirname "$(dirname "$WINEBIN")")

        progress "==> Installing $COMPONENT_ID into the shared RootFS"
        DEST="$SHARED_ROOTFS/opt/custom-wine/$COMPONENT_ID"
        rm -rf "$DEST"
        mkdir -p "$(dirname "$DEST")"
        cp -a "$SRCROOT" "$DEST"
        chmod +x "$DEST/bin/wine" "$DEST/bin/wineboot"

        progress "==> Registering $COMPONENT_ID in /etc/games-runtime.properties"
        MANIFEST="$SHARED_ROOTFS/etc/games-runtime.properties"
        [ -f "$MANIFEST" ] || fail rootfs_runtime_manifest_missing 70
        if ! grep -q "^runtimePackages=.*\\b$COMPONENT_ID\\b" "$MANIFEST"; then
            CURRENT=$(sed -n 's/^runtimePackages=//p' "$MANIFEST")
            [ -n "$CURRENT" ] || fail rootfs_runtime_manifest_missing_packages_key 70
            sed -i "s/^runtimePackages=.*/runtimePackages=${CURRENT},${COMPONENT_ID}/" "$MANIFEST"
        fi
        ;;
    box64:box64-tree)
        progress '==> Validating the custom Box64 binary'
        BOX64BIN=$(find "$STAGE_DIR" -type f -name box64 | head -n 1)
        [ -n "$BOX64BIN" ] || fail custom_box64_missing_binary 70
        # box64 on this RootFS must be a NATIVE ARM64 (AArch64) executable (box64 translates x86
        # guests but is itself an ARM64 binary); an x86 build picked by mistake would be copied in
        # and then fail to exec at launch. Still entirely within staging -- nothing written to the
        # shared RootFS until this passes. ELF e_machine is a 2-byte little-endian field at offset
        # 18; AArch64 == 0x00B7.
        BOX64_MAGIC=$(od -An -tx1 -N 20 "$BOX64BIN" 2>/dev/null | tr -d ' \n')
        case "$BOX64_MAGIC" in
            7f454c46*) ;;
            *) fail custom_box64_not_elf 64 ;;
        esac
        [ "$(printf '%s' "$BOX64_MAGIC" | cut -c37-40)" = b700 ] || fail custom_box64_not_aarch64 64

        progress "==> Installing $COMPONENT_ID into the shared RootFS"
        # Only the one binary is copied, to its own dedicated directory -- never /usr/local/bin/
        # box64 or any other system path, so a bad or malicious pick cannot affect any other
        # container or translator. See this script's header comment.
        DEST="$SHARED_ROOTFS/opt/custom-box64/$COMPONENT_ID/box64"
        mkdir -p "$(dirname "$DEST")"
        cp "$BOX64BIN" "$DEST"
        chmod 755 "$DEST"
        ;;
esac

rm -rf "$STAGE_DIR"
progress '==> Custom component install completed'

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
COMPLETED=true
