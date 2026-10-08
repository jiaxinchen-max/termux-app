#!/data/data/com.termux/files/usr/bin/sh
# Backs up or restores the shared RootFS base + per-translator Wine prefix templates to/from a
# single external-storage archive -- a disaster-recovery feature distinct from
# setup_rootfs_runtime.sh's own build pipeline (which now mv's straight into place at publish
# time, no archive/compress round trip -- see that script's runtime_complete/mv gate). Spec
# format (see RootfsBackupSpecCodec):
#   schemaVersion=1
#   taskId=<id>
#   mode=backup|restore
#   backupArchivePath=<absolute external-storage path, read for restore / written for backup>
#   eventsPath=<private events jsonl path, under .../games/runtime/backup/events/>
#   logPath=<private log path, under .../games/runtime/backup/logs/>

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: backup_restore_rootfs.sh <backup-spec>}
SCRIPT_PATH=$(realpath "$0" 2>/dev/null) || {
    printf '%s\n' backup_script_unreadable >&2
    exit 2
}

TASK_ID=
MODE=
BACKUP_ARCHIVE_PATH=
EVENTS_PATH=
LOG_PATH=
COMPLETED=false
FAILURE_CODE=backup_script_failed

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
    [ -z "${STAGING_DIRECTORY:-}" ] || rm -rf "$STAGING_DIRECTORY" 2>/dev/null || true
}
trap on_exit EXIT

fail() {
    FAILURE_CODE=$1
    exit "${2:-1}"
}

while IFS='=' read -r key value; do
    case "$key" in
        schemaVersion) [ "$value" = 1 ] || fail unsupported_backup_spec 64 ;;
        taskId) TASK_ID=$value ;;
        mode) MODE=$value ;;
        backupArchivePath) BACKUP_ARCHIVE_PATH=$value ;;
        eventsPath) EVENTS_PATH=$value ;;
        logPath) LOG_PATH=$value ;;
        '') ;;
        *) fail invalid_backup_spec 64 ;;
    esac
done < "$SPEC_PATH"

case "$MODE" in backup|restore) ;; *) fail invalid_backup_spec 64 ;; esac
case "$TASK_ID" in ''|*[!A-Za-z0-9._-]*) fail invalid_backup_identifier 64 ;; esac
# Deliberately NOT required to live under the app's own private storage (PRIVATE_ROOT below) --
# this is the one path in the whole RootFS pipeline that is expected to point at external/shared
# storage, so the backup survives an app data clear or uninstall. Still validated for basic path
# safety (absolute, no traversal).
case "$BACKUP_ARCHIVE_PATH" in /*) ;; *) fail invalid_backup_archive_path 64 ;; esac
case "$BACKUP_ARCHIVE_PATH" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_backup_archive_path 64 ;; esac

PRIVATE_ROOT=${SPEC_PATH%/runtime/backup/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || \
    fail invalid_backup_spec_path 64
case "$SPEC_PATH" in "$PRIVATE_ROOT"/runtime/backup/specs/*.backupspec) ;; *)
    fail invalid_backup_spec_path 64 ;;
esac
# Same Java-getCanonicalPath()-derived convention as setup_rootfs_runtime.sh -- see that script's
# own comment on why this must be string-derived from a path Java actually produced, not a bare
# hardcoded guess or a realpath of this script's own (different) install location.
TERMUX_FILES_ROOT=${PRIVATE_ROOT%/games}
[ -n "$TERMUX_FILES_ROOT" ] && [ "$TERMUX_FILES_ROOT" != "$PRIVATE_ROOT" ] || \
    fail invalid_backup_spec_path 64
for path in "$EVENTS_PATH" "$LOG_PATH"; do
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail backup_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_backup_private_path 64 ;; esac
done

events_directory=${EVENTS_PATH%/*}
logs_directory=${LOG_PATH%/*}
[ "$events_directory" != "$EVENTS_PATH" ] && [ "$logs_directory" != "$LOG_PATH" ] || \
    fail invalid_backup_output_path 64
mkdir -p "$events_directory" "$logs_directory" || fail backup_output_directory_failed 70
: >> "$LOG_PATH"

# Co-located with this script by LaunchScriptInstaller (same runtimeDirectory) -- sibling-path
# convention, same as start_rootfs_game.sh finding rootfs_prefix_warmup.sh. Defines progress(),
# run_logged(), run_logged_watchdog().
. "$(dirname "$SCRIPT_PATH")/rootfs_script_common.sh"

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
SHARED_ROOTFS_CONTAINER_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY/rootfs"
TEMPLATE_CACHE_DIR="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-template-cache"
TEMPLATE_RECIPE="$TEMPLATE_CACHE_DIR/games-rootfs-base.recipe"
STAGING_DIRECTORY="$TEMPLATE_CACHE_DIR/backup-staging"
if command -v zstd >/dev/null 2>&1; then
    ARCHIVE_COMPRESSOR=zstd
else
    ARCHIVE_COMPRESSOR=gzip
fi

# Same completeness check setup_rootfs_runtime.sh uses to decide a RootFS tree is actually usable.
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

printf '{"schemaVersion":1,"taskId":"%s","state":"RUNNING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

if [ "$MODE" = backup ]; then
    progress '==> Checking the shared base environment is built'
    runtime_complete "$SHARED_ROOTFS" || fail rootfs_not_built_yet 70

    # The shared RootFS is conceptually read-only once published (every container mounts it via
    # proot -r; nothing ever writes into it directly -- see setup_rootfs_runtime.sh's own comment
    # on this), so copying it is a plain, consistent snapshot even while other tasks are running.
    # Staged under one directory (rather than tar'd straight from multiple source directories with
    # per-source path rewriting) so the final archive always has one flat, predictable layout for
    # restore to parse, and so tar's own --numeric-owner/--transform semantics never have to be
    # reasoned about across more than one source tree at a time.
    rm -rf "$STAGING_DIRECTORY"
    mkdir -p "$STAGING_DIRECTORY/shared-rootfs"
    progress '==> Copying the shared RootFS into a staging area'
    if ! run_logged cp -a "$SHARED_ROOTFS_CONTAINER_DIRECTORY"/. "$STAGING_DIRECTORY/shared-rootfs"/; then
        fail rootfs_backup_stage_failed 70
    fi
    # The prefix templates are already independently compressed (built by
    # setup_rootfs_runtime.sh's per-translator loop) -- copy them in as-is rather than
    # decompressing and recompressing, which would just burn time for no size benefit.
    for prefix_archive in "$TEMPLATE_CACHE_DIR"/games-rootfs-base-prefix-*.tar.*; do
        [ -f "$prefix_archive" ] || continue
        cp -a "$prefix_archive" "$STAGING_DIRECTORY/"
    done
    # Carries forward the exact recipe hash the backed-up RootFS was built from, so a restore can
    # tell whether the restored content still matches the current recipe (see the restore branch
    # below) instead of either always forcing a redundant rebuild or trusting stale content
    # forever.
    [ ! -f "$TEMPLATE_RECIPE" ] || cp -a "$TEMPLATE_RECIPE" "$STAGING_DIRECTORY/games-rootfs-base.recipe"
    printf 'games-base-backup\nschemaVersion=1\n' > "$STAGING_DIRECTORY/backup-manifest.txt"

    backup_directory=${BACKUP_ARCHIVE_PATH%/*}
    mkdir -p "$backup_directory" || fail backup_destination_directory_failed 70
    rm -f "$BACKUP_ARCHIVE_PATH.tmp"
    progress '==> Archiving the backup (this can take a few minutes for a multi-GB base)'
    if ! run_logged_watchdog tar -C "$STAGING_DIRECTORY" --use-compress-program "$ARCHIVE_COMPRESSOR" \
        --numeric-owner -cf "$BACKUP_ARCHIVE_PATH.tmp" .; then
        rm -f "$BACKUP_ARCHIVE_PATH.tmp"
        fail rootfs_backup_archive_failed 70
    fi
    if ! mv "$BACKUP_ARCHIVE_PATH.tmp" "$BACKUP_ARCHIVE_PATH"; then
        rm -f "$BACKUP_ARCHIVE_PATH.tmp"
        fail rootfs_backup_archive_failed 70
    fi
    rm -rf "$STAGING_DIRECTORY"
    progress '==> Backup completed'
else
    [ -f "$BACKUP_ARCHIVE_PATH" ] || fail backup_archive_missing 70
    progress '==> Verifying the backup archive'
    tar -tf "$BACKUP_ARCHIVE_PATH" >/dev/null 2>&1 || fail backup_archive_unreadable 70

    rm -rf "$STAGING_DIRECTORY"
    mkdir -p "$STAGING_DIRECTORY"
    progress '==> Extracting the backup (this can take a few minutes for a multi-GB base)'
    if ! run_logged_watchdog tar -C "$STAGING_DIRECTORY" --numeric-owner -xpf "$BACKUP_ARCHIVE_PATH"; then
        fail backup_extract_failed 70
    fi
    runtime_complete "$STAGING_DIRECTORY/shared-rootfs/rootfs" || fail backup_contents_invalid 70

    progress '==> Publishing the restored RootFS image'
    rm -rf "$SHARED_ROOTFS_CONTAINER_DIRECTORY"
    mv "$STAGING_DIRECTORY/shared-rootfs" "$SHARED_ROOTFS_CONTAINER_DIRECTORY" || \
        fail rootfs_restore_publish_failed 70
    runtime_complete "$SHARED_ROOTFS" || fail rootfs_restore_publish_invalid 70

    mkdir -p "$TEMPLATE_CACHE_DIR"
    for prefix_archive in "$STAGING_DIRECTORY"/games-rootfs-base-prefix-*.tar.*; do
        [ -f "$prefix_archive" ] || continue
        mv "$prefix_archive" "$TEMPLATE_CACHE_DIR/" || fail rootfs_restore_prefix_failed 70
    done
    # Restore the exact recipe hash recorded at backup time, so a subsequent per-container setup
    # call correctly skips rebuilding when the restored content still matches the app's current
    # recipe, and correctly triggers a real rebuild when it does not (an old backup restored
    # after the recipe changed) -- same "no rebuild unless the recipe actually changed" semantics
    # setup_rootfs_runtime.sh's own gate uses, now also honored across a restore. A missing or
    # malformed recorded hash is not fatal: it just means the next setup call always rebuilds,
    # identical to the behavior before per-build recipe tracking existed.
    rm -f "$TEMPLATE_RECIPE"
    if [ -f "$STAGING_DIRECTORY/games-rootfs-base.recipe" ]; then
        restored_recipe=$(cat "$STAGING_DIRECTORY/games-rootfs-base.recipe" 2>/dev/null || true)
        case "$restored_recipe" in
            *[!0-9a-f]*|'') ;;
            *) [ "${#restored_recipe}" -ne 64 ] || printf '%s\n' "$restored_recipe" > "$TEMPLATE_RECIPE" ;;
        esac
    fi
    rm -rf "$STAGING_DIRECTORY"
    progress '==> Restore completed'
fi

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
COMPLETED=true
