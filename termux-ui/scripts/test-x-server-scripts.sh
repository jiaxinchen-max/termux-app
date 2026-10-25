#!/bin/sh
# Contract test of x_server_common.sh / x_server_legacy.sh / x_server_xlorie.sh with fake servers,
# plus a static check that the launch scripts go through the helper.

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ASSETS="$PROJECT_ROOT/termux-ui/src/main/assets/local-games"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/x-server-scripts.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
cleanup() {
    for proc in /proc/[0-9]*; do
        case "$(cat "$proc/comm" 2>/dev/null)" in
            Xlorie|termux-x11) kill -9 "${proc#/proc/}" 2>/dev/null || true ;;
        esac
    done
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3: expected '$2' got '$1'"; }

DIR="$TEST_ROOT/runtime"
FAKE_BIN="$TEST_ROOT/bin"
PFX="$TEST_ROOT/files/usr"
mkdir -p "$DIR" "$FAKE_BIN" "$PFX/tmp/.X11-unix" "$PFX/share/X11/xkb/rules"
cp "$ASSETS"/x_server_*.sh "$DIR/"
: > "$PFX/share/X11/xkb/rules/evdev"

# Each case runs in a fresh shell so the helper's state cannot leak between them.
run_case() { # body...
    env -i PATH="$FAKE_BIN:/usr/bin:/bin" PREFIX="$PFX" X_SERVER_DIR="$DIR" \
        X_SERVER_RENDER_SOCKET="$TEST_ROOT/termux-render" XLORIE_WAIT_RENDER=0 \
        "$@" sh -c ". \"$DIR/x_server_common.sh\"; $CASE"
}

fake() { # name
    cat > "$FAKE_BIN/$1" <<FAKE
#!/bin/sh
{
    printf 'ARGS=%s\n' "\$*"
    printf 'AHB=%s\n' "\${TERMUX_RENDER_AHB_RECEIVE:-}"
    printf 'GLMODE=%s\n' "\${XLORIE_GL_MODE:-}"
    printf 'XKB=%s\n' "\${XKB_CONFIG_ROOT:-}"
} > "$TEST_ROOT/$1.capture"
trap 'exit 0' TERM INT
while :; do sleep 1; done
FAKE
    chmod +x "$FAKE_BIN/$1"
}

wait_for_file() {
    tries=0
    until [ -f "$1" ]; do
        tries=$((tries + 1))
        [ "$tries" -le 50 ] || fail "timed out waiting for $1"
        sleep 0.1
    done
}

# --- 1. default and marker selection -------------------------------------------------------
CASE='x_server_backend'
assert_eq "$(run_case)" legacy "no marker means legacy"
printf 'xlorie\n' > "$DIR/x-server-backend"
assert_eq "$(run_case)" xlorie "marker xlorie"
printf 'wayland\n' > "$DIR/x-server-backend"
assert_eq "$(run_case)" legacy "unknown marker means legacy"
printf 'legacy\n' > "$DIR/x-server-backend"
assert_eq "$(run_case X_SERVER_BACKEND=xlorie)" xlorie "environment overrides the marker"
rm -f "$DIR/x-server-backend"

# --- 2. legacy: exactly the old command ------------------------------------------------------
CASE='x_server_require; echo "id=$X_SERVER_MISSING_ID"'
assert_eq "$(run_case || true)" "id=termux_x11_missing" "legacy reports termux_x11_missing"
fake termux-x11
CASE='x_server_require && echo present'
assert_eq "$(run_case)" present "legacy present"
CASE='x_server_start :0 "$TEST_LOG"; echo "pid=$X_SERVER_PID attempts=$X_SERVER_READY_ATTEMPTS"; sleep 1; kill $X_SERVER_PID'
out=$(run_case TEST_LOG="$TEST_ROOT/legacy.log")
wait_for_file "$TEST_ROOT/termux-x11.capture"
assert_eq "$(sed -n 's/^ARGS=//p' "$TEST_ROOT/termux-x11.capture")" ":0" "legacy argv is just the display"
case "$out" in *"attempts=200"*) ;; *) fail "legacy gets the long ready window for cold starts: $out" ;; esac

# --- 3. xlorie: arguments, environment, wait window ------------------------------------------
rm -f "$DIR/x-server-backend"
CASE='x_server_require; echo "id=$X_SERVER_MISSING_ID"'
assert_eq "$(run_case X_SERVER_BACKEND=xlorie || true)" "id=xlorie_missing" "xlorie reports xlorie_missing"
fake Xlorie
: > "$PFX/tmp/.X11-unix/X0"
: > "$PFX/tmp/.X0-lock"
CASE='x_server_start :0 "$TEST_LOG"; echo "attempts=$X_SERVER_READY_ATTEMPTS"; sleep 1; kill $X_SERVER_PID'
out=$(run_case X_SERVER_BACKEND=xlorie X_SERVER_SCREEN=1920x1080x24 X_SERVER_FRAMERATE=45 \
    TEST_LOG="$TEST_ROOT/xlorie.log")
wait_for_file "$TEST_ROOT/Xlorie.capture"
assert_eq "$(sed -n 's/^ARGS=//p' "$TEST_ROOT/Xlorie.capture")" \
    ":0 -noreset -screen 0 1920x1080x24 -framerate 45 -render-socket $TEST_ROOT/termux-render -xkbdir $PFX/share/X11/xkb" \
    "xlorie argv"
assert_eq "$(sed -n 's/^AHB=//p' "$TEST_ROOT/Xlorie.capture")" native "AHB receive mode for system GL"
assert_eq "$(sed -n 's/^GLMODE=//p' "$TEST_ROOT/Xlorie.capture")" system "GL mode"
assert_eq "$(sed -n 's/^XKB=//p' "$TEST_ROOT/Xlorie.capture")" "$PFX/share/X11/xkb" "XKB_CONFIG_ROOT"
[ ! -e "$PFX/tmp/.X11-unix/X0" ] || fail "stale X socket was not removed"
[ ! -e "$PFX/tmp/.X0-lock" ] || fail "stale X lock was not removed"
case "$out" in *"attempts=600"*) ;; *) fail "xlorie gets the long ready window: $out" ;; esac
grep -q "termux-render socket not found" "$TEST_ROOT/xlorie.log" || fail "missing render socket is logged"

# Defaults when the caller passes nothing.
rm -f "$TEST_ROOT/Xlorie.capture"
CASE='x_server_start :1 "$TEST_LOG"; sleep 1; kill $X_SERVER_PID'
run_case X_SERVER_BACKEND=xlorie TEST_LOG="$TEST_ROOT/xlorie2.log" >/dev/null
wait_for_file "$TEST_ROOT/Xlorie.capture"
assert_eq "$(sed -n 's/^ARGS=//p' "$TEST_ROOT/Xlorie.capture" | cut -d' ' -f1-7)" \
    ":1 -noreset -screen 0 1280x720x24 -framerate 60" "xlorie defaults"

# Software GL mode must not ask for native AHB receive.
rm -f "$TEST_ROOT/Xlorie.capture"
CASE='x_server_start :0 "$TEST_LOG"; sleep 1; kill $X_SERVER_PID'
run_case X_SERVER_BACKEND=xlorie XLORIE_GL_MODE=llvmpipe TEST_LOG="$TEST_ROOT/xlorie3.log" >/dev/null
wait_for_file "$TEST_ROOT/Xlorie.capture"
assert_eq "$(sed -n 's/^AHB=//p' "$TEST_ROOT/Xlorie.capture")" "" "no AHB override outside system mode"
assert_eq "$(sed -n 's/^GLMODE=//p' "$TEST_ROOT/Xlorie.capture")" llvmpipe "explicit GL mode is kept"

# --- 4. kill_all stops only the selected implementation ---------------------------------------
fake Xlorie
CASE='x_server_start :0 "$TEST_LOG"; sleep 0.5; x_server_kill_all; sleep 0.5; kill -0 $X_SERVER_PID 2>/dev/null && echo alive || echo dead'
assert_eq "$(run_case X_SERVER_BACKEND=xlorie TEST_LOG="$TEST_ROOT/k.log")" dead "xlorie kill_all"

# --- 4b. supervisor: restart while the render bridge refuses, stay down once killed ----------
cat > "$FAKE_BIN/Xlorie" <<FAKE
#!/bin/sh
n=\$(cat "$TEST_ROOT/tries" 2>/dev/null || echo 0)
echo \$((n + 1)) > "$TEST_ROOT/tries"
[ "\$n" -ge 2 ] || exit 1
exec python3 -c 'import socket,sys,time; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1]); time.sleep(30)' "$PFX/tmp/.X11-unix/X0"
FAKE
chmod +x "$FAKE_BIN/Xlorie"
rm -f "$TEST_ROOT/tries" "$PFX/tmp/.X11-unix/X0"
CASE='x_server_start :0 "$TEST_LOG"; x_server_wait_ready :0 && echo ready || echo notready; kill $X_SERVER_PID'
assert_eq "$(run_case X_SERVER_BACKEND=xlorie TEST_LOG="$TEST_ROOT/retry.log")" ready "xlorie retried until it connected"
assert_eq "$(cat "$TEST_ROOT/tries")" 3 "xlorie was started three times"
grep -q "retrying" "$TEST_ROOT/retry.log" || fail "retry is logged"
CASE='x_server_start :0 "$TEST_LOG"; sleep 1; x_server_kill_all; sleep 1.5; cat "$TEST_ROOT/tries"'
rm -f "$TEST_ROOT/tries"
assert_eq "$(run_case X_SERVER_BACKEND=xlorie TEST_ROOT="$TEST_ROOT" TEST_LOG="$TEST_ROOT/retry2.log")" 3 "kill_all is not undone by the supervisor"
CASE='x_server_wait_ready :9 && echo ready || echo notready'
assert_eq "$(run_case X_SERVER_BACKEND=xlorie X_SERVER_READY_ATTEMPTS=2)" notready "wait_ready times out"
# wait_ready is shared: a fresh socket counts, a stale one only once the launcher is gone.
mksock() { python3 -c 'import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1])' "$1"; }
sock0="$PFX/tmp/.X11-unix/X0"
rm -f "$sock0"
CASE='x_server_wait_ready :0 && echo ready || echo notready'
assert_eq "$(run_case X_SERVER_BACKEND=legacy X_SERVER_READY_ATTEMPTS=2)" notready "legacy: no socket times out"
CASE='x_server_start :0 "$TEST_LOG"; (sleep 0.5; python3 -c "import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1])" "$PREFIX/tmp/.X11-unix/X0") & x_server_wait_ready :0 && echo ready || echo notready; kill $X_SERVER_PID'
fake termux-x11
assert_eq "$(run_case X_SERVER_BACKEND=legacy TEST_LOG="$TEST_ROOT/w.log")" ready "legacy: socket created after start is ready"
rm -f "$sock0"; mksock "$sock0"; touch -d '2 minutes ago' "$sock0"
CASE='x_server_start :0 "$TEST_LOG"; X_SERVER_READY_ATTEMPTS=3; x_server_wait_ready :0 && echo ready || echo notready; kill $X_SERVER_PID'
assert_eq "$(run_case X_SERVER_BACKEND=legacy TEST_LOG="$TEST_ROOT/w.log")" notready "stale socket + live launcher is not ready"
CASE='X_SERVER_STARTED_AT=$(date +%s); X_SERVER_PID=999999; x_server_wait_ready :0 && echo ready || echo notready'
assert_eq "$(run_case X_SERVER_BACKEND=legacy)" ready "stale socket + launcher gone (server already running) is ready"
rm -f "$sock0"


# --- 5. the launch scripts use the helper and never call termux-x11 :0 directly ----------------
for launcher in start_rootfs_game.sh start_termux_box_game.sh; do
    sh -n "$ASSETS/$launcher" || fail "$launcher has a syntax error"
    grep -q 'x_server_common.sh' "$ASSETS/$launcher" || fail "$launcher does not source the helper"
    grep -q 'x_server_start' "$ASSETS/$launcher" || fail "$launcher does not start the X server through the helper"
    if grep -nE '^[[:space:]]*termux-x11 :0' "$ASSETS/$launcher"; then
        fail "$launcher still starts termux-x11 directly"
    fi
done
# Maintenance X servers run without a display view, so they must stay on termux-x11.
for maintenance in setup_rootfs_runtime.sh rootfs_prefix_warmup.sh; do
    grep -q 'x_server_' "$ASSETS/$maintenance" && fail "$maintenance must keep using termux-x11"
done

printf 'x server scripts contract test passed\n'
