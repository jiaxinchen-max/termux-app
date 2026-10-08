#!/data/data/com.termux/files/usr/bin/sh
# Installs a user-picked local Wine or Box64 build into the already-published shared RootFS --
# an incremental, single-component add, distinct from setup_rootfs_runtime.sh's full base-image
# build/publish pipeline. Spec format (see CustomComponentInstallSpecCodec):
#   schemaVersion=1
#   taskId=<id>
#   kind=wine|box64
#   componentId=<custom-wine-<slug>|custom-box64-<slug>>
#   payloadPath=<app-private staged copy of the SAF-picked file, canonical>
#   payloadKind=wine-tree|deb|raw-binary
#   eventsPath=<private events jsonl path, under .../games/runtime/custom-install/events/>
#   logPath=<private log path, under .../games/runtime/custom-install/logs/>
#
# wine:wine-tree  -- payload is a tar(.xz/.gz/.zst/.tar) archive containing bin/wine+bin/wineboot
#                     at some depth (same content-detection runtime-rootfs/setup-container.sh
#                     already uses for its own portable-Wine-build loop). Copied whole to
#                     /opt/custom-wine/<componentId>/ inside the shared RootFS; no apt/proot-distro
#                     involved, this is plain file extraction onto a host path.
# box64:deb       -- payload is a single .deb. Installed via a raw `proot` session chrooted into
#                     the shared RootFS (same invocation shape as rootfs_prefix_warmup.sh's
#                     run_rootfs_maintenance_command -- NOT `proot-distro login`, which only
#                     resolves registered container *names* under proot-distro's own containers
#                     directory; the shared RootFS is deliberately NOT registered there, see
#                     setup_rootfs_runtime.sh's own comment on why it is a plain mv'd directory).
#                     apt resolves dependencies from the same Debian snapshot repo configured
#                     inside the RootFS at base-build time (unchanged since publish).
# box64:raw-binary -- payload is a bare ELF executable. Copied + chmod'd to
#                     /opt/custom-box64/<componentId>/box64, no apt/proot needed.

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
case "$PAYLOAD_KIND" in wine-tree|deb|raw-binary) ;; *) fail invalid_custom_install_spec 64 ;; esac
case "$KIND:$PAYLOAD_KIND" in
    wine:wine-tree|box64:deb|box64:raw-binary) ;;
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
PROOT_BIN="$PREFIX/bin/proot"
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

case "$KIND:$PAYLOAD_KIND" in
    wine:wine-tree)
        progress '==> Extracting the custom Wine build'
        case "$PAYLOAD_PATH" in
            *.tar.zst) run_logged tar -C "$STAGE_DIR" --zstd -xpf "$PAYLOAD_PATH" ;;
            *.tar.xz) run_logged tar -C "$STAGE_DIR" -J -xpf "$PAYLOAD_PATH" ;;
            *.tar.gz|*.tgz) run_logged tar -C "$STAGE_DIR" -z -xpf "$PAYLOAD_PATH" ;;
            *.tar) run_logged tar -C "$STAGE_DIR" -xpf "$PAYLOAD_PATH" ;;
            *) fail custom_wine_archive_format_unsupported 64 ;;
        esac

        WINEBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wine -path '*/bin/*' | head -n 1)
        [ -n "$WINEBIN" ] || fail custom_wine_missing_wine_binary 70
        WINEBOOTBIN=$(find "$STAGE_DIR" \( -type f -o -type l \) -name wineboot -path '*/bin/*' | head -n 1)
        [ -n "$WINEBOOTBIN" ] || fail custom_wine_missing_wineboot_binary 70
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
    box64:deb)
        progress '==> Installing the custom Box64 .deb'
        [ -x "$PROOT_BIN" ] || fail proot_runtime_missing 69
        cp -a "$PAYLOAD_PATH" "$STAGE_DIR/component.deb"
        # Raw proot chroot into the shared RootFS itself -- same invocation shape as
        # rootfs_prefix_warmup.sh's run_rootfs_maintenance_command (-0 for root, /dev /proc /sys
        # bound). The RootFS's own /etc/apt/sources.list, resolv.conf and certs are whatever
        # setup-container.sh wrote at base-build time -- untouched since publish, so apt-get can
        # resolve dependencies from the same Debian snapshot repo again without any extra setup.
        if ! run_logged_watchdog "$PROOT_BIN" --kill-on-exit --link2symlink --sysvipc -0 \
            -r "$SHARED_ROOTFS" \
            -b /dev -b /proc -b /sys \
            -b "$TERMUX_FILES_ROOT/usr/tmp:/tmp" \
            -b "$STAGE_DIR:/run/custom-install" \
            -w /run/custom-install \
            /usr/bin/env -i HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 LC_ALL=C.UTF-8 \
            PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
            DEBIAN_FRONTEND=noninteractive \
            /bin/sh -c 'apt-get install -y --no-install-recommends /run/custom-install/component.deb'; then
            fail custom_box64_deb_install_failed 70
        fi
        # Box64's .deb installs to /usr/local/bin; mirror setup-container.sh's own PATH symlink.
        [ -x "$SHARED_ROOTFS/usr/bin/box64" ] || \
            ln -sf /usr/local/bin/box64 "$SHARED_ROOTFS/usr/bin/box64" 2>/dev/null || true
        ;;
    box64:raw-binary)
        progress '==> Installing the custom Box64 binary'
        DEST="$SHARED_ROOTFS/opt/custom-box64/$COMPONENT_ID/box64"
        mkdir -p "$(dirname "$DEST")"
        cp "$PAYLOAD_PATH" "$DEST"
        chmod 755 "$DEST"
        ;;
esac

rm -rf "$STAGE_DIR"
progress '==> Custom component install completed'

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
COMPLETED=true
