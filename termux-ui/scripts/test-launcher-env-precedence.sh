#!/usr/bin/env sh
# Verifies start_termux_box_game.sh keeps the environment variables a user sets in the game's Runtime > Environment
# variables: the DEBUG_MODE presets (WINEDEBUG, MESA_NO_ERROR, BOX64_*) must not replace them, and a variable the user
# did not set must still get the preset value. The app exports those variables into the launcher's process environment
# and lists their names in TERMUX_BOX_INHERITED_ENV_KEYS; the presets overwrite that same environment. The functions and
# the main-flow call order are taken from the launcher. LAUNCHER=<path> checks another copy of the launcher.

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
LAUNCHER=${LAUNCHER:-"$PROJECT_ROOT/termux-ui/src/main/assets/local-games/start_termux_box_game.sh"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT INT TERM

fail() { echo "FAIL: $*" >&2; exit 1; }

FUNCS="$WORK/functions.sh"
SEQUENCE="$WORK/sequence.sh"
sed -n '/^apply_container_env_vars() {/,/^}/p;/^apply_debug_mode() {/,/^}/p;/^snapshot_user_env() {/,/^}/p;/^restore_user_env() {/,/^}/p' "$LAUNCHER" > "$FUNCS"
# The real order of the main flow: everything from load_configs up to (not including) ensure_launch_log.
sed -n '/^load_configs$/,/^ensure_launch_log/p' "$LAUNCHER" | sed '$d' > "$SEQUENCE"
grep -q '^apply_container_env_vars() {' "$FUNCS" || fail "apply_container_env_vars not found in the launcher"
grep -q '^apply_debug_mode() {' "$FUNCS" || fail "apply_debug_mode not found in the launcher"
grep -q '^apply_debug_mode$' "$SEQUENCE" || fail "apply_debug_mode is not part of the launcher main flow"

KEYS="MESA_NO_ERROR WINEDEBUG BOX64_LOG BOX64_NOBANNER BOX64_SHOWSEGV BOX64_DLSYM_ERROR BOX64_DYNAREC_MISSING BOX64_TRACE_FILE"

# snapshot: the preset-controlled variables as KEY=value lines, <unset> when absent.
snapshot() {
    for _key in $KEYS; do
        _value=$(printenv "$_key") || _value='<unset>'
        printf '%s=%s\n' "$_key" "$_value"
    done
}

# preset_only <mode>: what apply_debug_mode alone exports for a clean environment.
preset_only() {
    (
        # shellcheck disable=SC2086
        unset $KEYS TERMUX_BOX_ENV_VARS
        DEBUG_MODE=$1
        TERMUX_BOX_TRACE_DIR=/trace
        . "$FUNCS"
        apply_debug_mode
        snapshot
    )
}

# main_flow <mode> <TERMUX_BOX_ENV_VARS> [NAME=value ...]: the launcher's own call order, with load_configs reduced to the
# step that applies the container environment. The NAME=value arguments are what the app exports into the process
# environment and lists in TERMUX_BOX_INHERITED_ENV_KEYS.
main_flow() {
    (
        # shellcheck disable=SC2086
        unset $KEYS
        DEBUG_MODE=$1
        TERMUX_BOX_TRACE_DIR=/trace
        TERMUX_BOX_ENV_VARS=$2
        shift 2
        TERMUX_BOX_INHERITED_ENV_KEYS=
        for _assignment in "$@"; do
            export "$_assignment"
            TERMUX_BOX_INHERITED_ENV_KEYS="$TERMUX_BOX_INHERITED_ENV_KEYS ${_assignment%%=*}"
        done
        export TERMUX_BOX_ENV_VARS TERMUX_BOX_INHERITED_ENV_KEYS
        . "$FUNCS"
        load_configs() { apply_container_env_vars; }
        normalize_cpu_affinity() { :; }
        . "$SEQUENCE"
        snapshot
    )
}

USER_DEBUG='WINEDEBUG=err+all,+loaddll'
TRACE_VALUE="BOX64_TRACE_FILE=/tmp/it's here/trace %pid.txt"
for mode in 0 1 2 3; do
    PRESET=$(preset_only "$mode")
    [ -n "$PRESET" ] || fail "mode $mode: no preset snapshot"

    # Nothing set by the user: the launcher ends up with exactly the preset.
    [ "$(main_flow "$mode" '')" = "$PRESET" ] || fail "mode $mode: the presets changed when the user set nothing"

    # The user's WINEDEBUG (exported by the app, listed in the inherited keys) survives, and every other variable keeps
    # its preset value.
    OUT=$(main_flow "$mode" '' "$USER_DEBUG")
    printf '%s\n' "$OUT" | grep -qx "$USER_DEBUG" || fail "mode $mode: the user's WINEDEBUG was replaced by the preset"
    [ "$(printf '%s\n' "$OUT" | grep -v '^WINEDEBUG=')" = "$(printf '%s\n' "$PRESET" | grep -v '^WINEDEBUG=')" ] \
        || fail "mode $mode: variables the user did not set lost their preset value"

    # A value with a single quote and spaces survives the save and restore, also where the preset unsets the variable.
    OUT=$(main_flow "$mode" '' "$TRACE_VALUE")
    printf '%s\n' "$OUT" | grep -qxF "$TRACE_VALUE" || fail "mode $mode: a value with quotes and spaces was not kept"

    # The variables handed over through TERMUX_BOX_ENV_VARS are kept as well.
    OUT=$(main_flow "$mode" "$USER_DEBUG")
    printf '%s\n' "$OUT" | grep -qx "$USER_DEBUG" || fail "mode $mode: WINEDEBUG from TERMUX_BOX_ENV_VARS was replaced by the preset"
done

# Several user variables at once, including ones the presets also export.
OUT=$(main_flow 0 '' "WINEDEBUG=-fixme" "BOX64_LOG=2" "WINEESYNC=1")
printf '%s\n' "$OUT" | grep -qx 'WINEDEBUG=-fixme' || fail "user WINEDEBUG=-fixme was replaced"
printf '%s\n' "$OUT" | grep -qx 'BOX64_LOG=2' || fail "user BOX64_LOG was replaced by the preset"
printf '%s\n' "$OUT" | grep -qx 'MESA_NO_ERROR=1' || fail "MESA_NO_ERROR lost its preset value"

echo "launcher environment precedence checks passed"
