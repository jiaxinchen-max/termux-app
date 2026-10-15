#!/data/data/com.termux/files/usr/bin/sh
# Installs a user-picked local Wine build into the Termux glibc prefix (GLIBC_TERMUX_BOX backend)
# -- the GLIBC-side counterpart of install_custom_rootfs_component.sh. Spec format (see
# CustomComponentInstallSpecCodec, same codec both scripts share):
#   schemaVersion=1
#   taskId=<id>
#   kind=wine
#   componentId=<custom-wine-<slug>>
#   payloadPath=<app-private staged copy of the SAF-picked file, canonical>
#   payloadKind=wine-tree
#   eventsPath=<private events jsonl path, under .../games/runtime/custom-install/events/>
#   logPath=<private log path, under .../games/runtime/custom-install/logs/>
#
# Only "wine" kind exists here -- GLIBC_TERMUX_BOX has exactly one box64 translation path (see
# GlibcTermuxBoxBackend), so there is no per-build box64 choice to let a user override the way
# ROOTFS_PROOT's ".../opt/box64/<id>/" custom-box64 flow does; box64 itself is upgraded through
# the ordinary catalog Components page (glibc_box64_0.2.7 / glibc_box64_0.4.4), not this script.
#
# wine:wine-tree -- archive containing bin/wine+bin/wineboot+lib*/wine at some depth (same
#                   content-detection install_custom_rootfs_component.sh/
#                   runtime-rootfs/setup-container.sh already use). The whole wine tree is copied
#                   to $TERMUX_GLIBC_DIR/<componentId>/ -- the same per-id directory
#                   WINE_PATH="$TERMUX_GLIBC_DIR/${TERMUX_BOX_WINE_PACKAGE}" (see
#                   start_termux_box_game.sh) already resolves catalog wine packages from, so a
#                   custom build needs no launch-script changes to be picked up once selected.

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: install_custom_glibc_wine_component.sh <install-spec>}
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

[ "$KIND" = wine ] || fail invalid_custom_install_spec 64
case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail invalid_custom_install_identifier 64 ;; esac
case "$COMPONENT_ID" in
    custom-wine-*) ;;
    *) fail invalid_custom_component_id 64 ;;
esac
case "$COMPONENT_ID" in *[!A-Za-z0-9._-]*) fail invalid_custom_component_id 64 ;; esac
[ "$PAYLOAD_KIND" = wine-tree ] || fail invalid_custom_install_spec 64
case "$PAYLOAD_PATH" in /*) ;; *) fail invalid_custom_install_payload_path 64 ;; esac
case "$PAYLOAD_PATH" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_custom_install_payload_path 64 ;; esac

PRIVATE_ROOT=${SPEC_PATH%/runtime/custom-install/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || \
    fail invalid_custom_install_spec_path 64
case "$SPEC_PATH" in "$PRIVATE_ROOT"/runtime/custom-install/specs/*.installspec) ;; *)
    fail invalid_custom_install_spec_path 64 ;;
esac
# Same Java-getCanonicalPath()-derived convention as install_custom_rootfs_component.sh.
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

# Same fixed path start_termux_box_game.sh resolves WINE_PATH against -- not derived from
# TERMUX_FILES_ROOT, since the glibc prefix always lives at this one absolute location regardless
# of how Java computed PRIVATE_ROOT (see that script's own "Fixed paths" header comment).
TERMUX_GLIBC_DIR="/data/data/com.termux/files/usr/glibc"
STAGE_DIR="$TERMUX_GLIBC_DIR/.custom-install-stage/$COMPONENT_ID"

[ -f "$PAYLOAD_PATH" ] || fail custom_component_payload_missing 70

progress '==> Checking the Termux glibc runtime is installed'
[ -x "$TERMUX_GLIBC_DIR/bin/box64" ] || fail glibc_runtime_not_installed 70

printf '{"schemaVersion":1,"taskId":"%s","state":"RUNNING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

progress '==> Extracting the custom build'
# Detected by content (magic bytes), not by the payload file's name/extension -- same convention
# install_custom_rootfs_component.sh uses.
MAGIC=$(od -An -tx1 -N 6 "$PAYLOAD_PATH" 2>/dev/null | tr -d ' \n')
case "$MAGIC" in
    fd377a585a00*) run_logged tar -C "$STAGE_DIR" -J -xpf "$PAYLOAD_PATH" ;;
    1f8b*) run_logged tar -C "$STAGE_DIR" -z -xpf "$PAYLOAD_PATH" ;;
    28b52ffd*) run_logged tar -C "$STAGE_DIR" --zstd -xpf "$PAYLOAD_PATH" ;;
    *)
        if ! run_logged tar -C "$STAGE_DIR" -xpf "$PAYLOAD_PATH"; then
            fail custom_archive_format_unsupported 64
        fi
        ;;
esac

WINEBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wine -path '*/bin/*' | head -n 1)
[ -n "$WINEBIN" ] || fail custom_wine_missing_wine_binary 70
WINEBOOTBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wineboot -path '*/bin/*' | head -n 1)
[ -n "$WINEBOOTBIN" ] || fail custom_wine_missing_wineboot_binary 70
# A genuine Wine build ships its PE/builtin DLL tree under lib/wine (or lib64/wine) -- bin/wine
# and bin/wineboot alone can be present in an unrelated archive, so require the DLL tree too
# before trusting this as a real Wine build. All still in staging.
if ! find "$STAGE_DIR" -type d -path '*/lib*/wine' 2>/dev/null | grep -q .; then
    fail custom_wine_not_a_wine_build 64
fi
SRCROOT=$(dirname "$(dirname "$WINEBIN")")

progress "==> Installing $COMPONENT_ID into the Termux glibc prefix"
DEST="$TERMUX_GLIBC_DIR/$COMPONENT_ID"
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -a "$SRCROOT" "$DEST"
chmod +x "$DEST/bin/wine" "$DEST/bin/wineboot"

rm -rf "$STAGE_DIR"
progress '==> Custom component install completed'

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
COMPLETED=true
