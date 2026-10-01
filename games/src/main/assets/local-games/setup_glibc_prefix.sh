#!/bin/sh

set -u
PS4='+ '
set -x

SPEC_PATH="${1:?Usage: $0 <prefix_provision_spec>}"
[ -f "$SPEC_PATH" ] || exit 64
. "$SPEC_PATH"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BOOTSTRAP_SCRIPT="$SCRIPT_DIR/bootstrap_termux_box.sh"

emit() {
    _state=$1
    _error=${2:-}
    mkdir -p "$(dirname "$TERMUX_BOX_EVENT_FILE")"
    printf '{"taskId":"%s","state":"%s","error":"%s","time":%s}\n' \
        "$TERMUX_BOX_TASK_ID" "$_state" "$_error" "$(date +%s)" \
        >> "$TERMUX_BOX_EVENT_FILE"
}

mkdir -p "$(dirname "$TERMUX_BOX_LOG_FILE")"
emit BUILDING ''
status_file="$TERMUX_BOX_LOG_FILE.status.$$"
rm -f "$status_file"
(
    set +e
    "$BOOTSTRAP_SCRIPT" "$SPEC_PATH"
    command_status=$?
    printf '%s\n' "$command_status" > "$status_file"
) 2>&1 | tee -a "$TERMUX_BOX_LOG_FILE"
status=$(cat "$status_file" 2>/dev/null || printf '1')
rm -f "$status_file"
if [ "$status" -eq 0 ]; then
    emit SUCCEEDED ''
    exit 0
fi
emit FAILED prefix_bootstrap_failed
exit "$status"
