#!/bin/sh

set -u

SPEC_PATH="${1:?Usage: $0 <rootfs_reset_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"

emit() {
    printf '%s\n' "$1" > "$RESET_EVENT_FILE"
}

run() {
    status_file="$RESET_LOG_FILE.status.$$"
    rm -f "$status_file"
    (
        "$@"
        command_status=$?
        printf '%s\n' "$command_status" > "$status_file"
    ) 2>&1 | tee -a "$RESET_LOG_FILE"
    status=$(cat "$status_file" 2>/dev/null || printf '1')
    rm -f "$status_file"
    return "$status"
}

mkdir -p "$(dirname "$RESET_LOG_FILE")" "$(dirname "$RESET_EVENT_FILE")"

for path in "$RESET_CONTAINER_DIR" "$RESET_METADATA_DIR"; do
    [ -n "$path" ] || continue
    case "$path" in
        /data/data/com.termux/files/*) ;;
        /data/user/[0-9]*/com.termux/files/*) ;;
        *) printf '%s\n' "Refusing to remove path outside private storage: $path" \
               | tee -a "$RESET_LOG_FILE"
           emit FAILED
           exit 1 ;;
    esac
done

printf '%s\n' "Resetting RootFS container: $RESET_CONTAINER_ID" | tee -a "$RESET_LOG_FILE"

# The Debian rootfs proot-distro built for this container (or the shared template, when
# resetting that instead of a specific game's already-cloned container).
run rm -rf "$RESET_CONTAINER_DIR" || { emit FAILED; exit 1; }
# This container's own activation metadata (active/previous rootfs package pointers).
# The shared template has no metadata directory of its own, so this is skipped when unset.
if [ -n "$RESET_METADATA_DIR" ]; then
    run rm -rf "$RESET_METADATA_DIR" || { emit FAILED; exit 1; }
fi

if [ -e "$RESET_CONTAINER_DIR" ] || { [ -n "$RESET_METADATA_DIR" ] && [ -e "$RESET_METADATA_DIR" ]; }; then
    printf '%s\n' 'RootFS reset verification failed.' | tee -a "$RESET_LOG_FILE"
    emit FAILED
    exit 1
fi

printf '%s\n' 'RootFS container reset to not-installed.' | tee -a "$RESET_LOG_FILE"
emit SUCCEEDED
