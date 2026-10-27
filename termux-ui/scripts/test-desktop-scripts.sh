#!/bin/sh
# Contract test of desktop_start.sh / desktop_stop.sh with fake start-plasma commands.

set -eu

PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ASSETS="$PROJECT_ROOT/termux-ui/src/main/assets/local-games"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/desktop-scripts.XXXXXX")
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
SOCKET_PID=""
cleanup() {
    [ -z "$SOCKET_PID" ] || kill "$SOCKET_PID" 2>/dev/null || true
    for pid_file in "$TEST_ROOT"/state/*.pid; do
        [ -f "$pid_file" ] && kill "$(cat "$pid_file")" 2>/dev/null || true
    done
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3: expected '$2' got '$1'"; }

export PREFIX="$TEST_ROOT/usr"
export HOME="$TEST_ROOT/home"
mkdir -p "$PREFIX/bin" "$HOME" "$TEST_ROOT/state" "$TEST_ROOT/logs"
cp "$ASSETS"/desktop_*.sh "$TEST_ROOT/"

write_spec() { # mode [distro] [de]
    cat > "$TEST_ROOT/spec" <<SPEC
DESKTOP_ID=test
DESKTOP_DE=${3:-kwin_plasma}
DESKTOP_MODE=$1
DESKTOP_GL_MODE=zink
DESKTOP_PROOT_DISTRO=$2
DESKTOP_RESOLUTION=1280x720
DESKTOP_WIDTH=1280
DESKTOP_HEIGHT=720
DESKTOP_REFRESH_RATE=45
DESKTOP_PID_FILE=$TEST_ROOT/state/test.pid
DESKTOP_STATUS_FILE=$TEST_ROOT/state/test.status
DESKTOP_LOG_FILE=$TEST_ROOT/logs/test.log
DESKTOP_RENDER_SOCKET=$TEST_ROOT/termux-render
DESKTOP_RELEASE_TAG=termux-hwaccel-6.6-r19
SPEC
}

fake_command() { # name exit-after
    cat > "$PREFIX/bin/$1" <<FAKE
#!/bin/sh
{
    printf 'GL=%s\n' "\$KWIN_ANDROID_GL_MODE"
    printf 'RATE=%s\n' "\$KWIN_ANDROID_REFRESH_RATE"
    printf 'SIZE=%sx%s\n' "\$KWIN_ANDROID_WIDTH" "\$KWIN_ANDROID_HEIGHT"
    printf 'ROOT=%s\n' "\$KWIN_ANDROID_RUNTIME_ROOT"
    printf 'DISTRO=%s\n' "\${PLASMA_CONTAINER_DISTRO:-}"
    printf 'LOG=%s\n' "\$PLASMA_LOG"
} > "$TEST_ROOT/capture"
printf 'kwin.android.egl: Android EGL backend initialized\n' >> "\$PLASMA_LOG"
$2
FAKE
    chmod +x "$PREFIX/bin/$1"
}

wait_for() { # description command...
    description=$1; shift
    tries=0
    until "$@"; do
        tries=$((tries + 1))
        [ "$tries" -le 100 ] || fail "timed out waiting for $description"
        sleep 0.1
    done
}

start_socket() {
    python3 -I - "$TEST_ROOT/termux-render" <<'PY' &
import socket, sys, time, os
path = sys.argv[1]
if os.path.exists(path): os.unlink(path)
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind(path)
s.listen(1)
time.sleep(120)
PY
    SOCKET_PID=$!
    wait_for "render socket" test -S "$TEST_ROOT/termux-render"
}

pid_running() { [ -f "$TEST_ROOT/state/test.pid" ] && kill -0 "$(cat "$TEST_ROOT/state/test.pid")" 2>/dev/null; }
status() { tr -d '\n' < "$TEST_ROOT/state/test.status"; }

# --- 1. native: start, observe, stop ------------------------------------------------------
write_spec native ""
fake_command start-plasma "exec sleep 300"
start_socket
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" &
SUPERVISOR=$!
wait_for "pid file" pid_running
assert_eq "$(sed -n 's/^GL=//p' "$TEST_ROOT/capture")" zink "GL mode"
assert_eq "$(sed -n 's/^RATE=//p' "$TEST_ROOT/capture")" 45 "refresh rate"
assert_eq "$(sed -n 's/^SIZE=//p' "$TEST_ROOT/capture")" 1280x720 "size"
assert_eq "$(sed -n 's/^ROOT=//p' "$TEST_ROOT/capture")" "$HOME/.termux/kwin-runtime" "runtime root"
assert_eq "$(sed -n 's/^LOG=//p' "$TEST_ROOT/capture")" "$TEST_ROOT/logs/test.log" "plasma log"
assert_eq "$(sed -n 's/^DISTRO=//p' "$TEST_ROOT/capture")" "" "native has no distro"
assert_eq "$(status)" starting "status while running"
grep -q "Android EGL backend initialized" "$TEST_ROOT/logs/test.log" || fail "ready marker missing"

# A second start while running is a no-op.
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" || fail "second start must succeed"
pid_running || fail "second start killed the desktop"

sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || true
pid_running && fail "desktop still running after stop"
[ ! -f "$TEST_ROOT/state/test.pid" ] || fail "pid file left behind"
assert_eq "$(status)" stopped "status after stop"

# --- 2. proot: compositor-only + container session ------------------------------------------
cp "$ASSETS/desktop_container_session.sh" "$TEST_ROOT/"
RUNTIME_DIR="$HOME/.termux/kwin-runtime/runtime-$(id -u)"
write_spec proot debian
# The compositor stand-in publishes the Wayland socket the launcher waits for.
cat > "$PREFIX/bin/start-plasma" <<FAKE
#!/bin/sh
printf '%s\n' "\$*" > "$TEST_ROOT/compositor-args"
printf 'GL=%s\n' "\$KWIN_ANDROID_GL_MODE" > "$TEST_ROOT/capture"
printf 'kwin.android.egl: Android EGL backend initialized\n' >> "\$PLASMA_LOG"
mkdir -p "$RUNTIME_DIR"
exec python3 -I - "$RUNTIME_DIR/wayland-0" <<'PY'
import socket, sys, time, os
path = sys.argv[1]
if os.path.exists(path): os.unlink(path)
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind(path)
s.listen(1)
time.sleep(300)
PY
FAKE
chmod +x "$PREFIX/bin/start-plasma"
proot_fake() { # body
    cat > "$PREFIX/bin/proot-distro" <<FAKE
#!/bin/sh
printf '%s\n' "\$*" > "$TEST_ROOT/proot-args"
$1
FAKE
    chmod +x "$PREFIX/bin/proot-distro"
}
proot_fake "exec sleep 300"
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" &
SUPERVISOR=$!
wait_for "proot container pid" test -f "$TEST_ROOT/state/test.pid.container"
assert_eq "$(cat "$TEST_ROOT/compositor-args")" "--compositor-only" "compositor flag"
grep -q "login debian" "$TEST_ROOT/proot-args" || fail "proot-distro login debian missing: $(cat "$TEST_ROOT/proot-args")"
grep -q -- "--bind $RUNTIME_DIR:/run/wl" "$TEST_ROOT/proot-args" || fail "runtime dir bind missing"
grep -q "/tmp/plasma-container-session.sh" "$TEST_ROOT/proot-args" || fail "session bind missing"
pid_running || fail "compositor pid not running"
container_pid=$(cat "$TEST_ROOT/state/test.pid.container")
kill -0 "$container_pid" 2>/dev/null || fail "container not running"

sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || true
pid_running && fail "compositor still running after stop"
kill -0 "$container_pid" 2>/dev/null && fail "container still running after stop"
[ ! -f "$TEST_ROOT/state/test.pid.container" ] || fail "container pid file left behind"
assert_eq "$(status)" stopped "proot status after stop"

# --- 2b. a failing container session tears the compositor down and reports its status ---------
proot_fake "printf 'ERROR: Turnip Vulkan ICD not found\n' > \"$RUNTIME_DIR/plasma-container.log\"; exit 1"
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec"
code=$?
set -e
assert_eq "$code" 1 "container failure exit code"
assert_eq "$(status)" exit:1 "container failure status"
grep -q "Turnip Vulkan ICD not found" "$TEST_ROOT/logs/test.log" || fail "container log tail not copied into the desktop log"
pid_running && fail "compositor left running after container failure"
[ ! -f "$TEST_ROOT/state/test.pid.container" ] || fail "container pid file left after failure"

# --- 3. a desktop that dies on its own is reported as exit:<code> --------------------------
write_spec native ""
fake_command start-plasma "exit 7"
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec"
code=$?
set -e
assert_eq "$code" 7 "exit code"
assert_eq "$(status)" exit:7 "status after crash"
[ ! -f "$TEST_ROOT/state/test.pid" ] || fail "pid file left after crash"

# --- 4. missing command -------------------------------------------------------------------
rm -f "$PREFIX/bin/start-plasma"
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec"
code=$?
set -e
assert_eq "$code" 127 "missing command exit code"
assert_eq "$(status)" exit:127 "missing command status"

# --- 5. no display bridge socket ----------------------------------------------------------
kill "$SOCKET_PID" 2>/dev/null || true
SOCKET_PID=""
rm -f "$TEST_ROOT/termux-render"
fake_command start-plasma "exec sleep 300"
set +e
DESKTOP_SOCKET_WAIT_SECONDS=1 sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec"
code=$?
set -e
assert_eq "$code" 66 "no socket exit code"
assert_eq "$(status)" exit:66 "no socket status"


# --- 7. install smoke: the script runs to completion and provisions the instance alias --------
INST_ROOT="$TEST_ROOT/inst"
mkdir -p "$INST_ROOT/bin" "$INST_ROOT/usr/var/lib/proot-distro/containers" "$TEST_ROOT/tmp"
cat > "$INST_ROOT/bin/uname" <<'FAKE'
#!/bin/sh
[ "$1" = "-m" ] && { echo aarch64; exit 0; }
exec /usr/bin/uname "$@"
FAKE
cat > "$INST_ROOT/bin/pkg" <<'FAKE'
#!/bin/sh
echo "pkg $*" >> "$INST_LOG"
exit 0
FAKE
cat > "$INST_ROOT/bin/apt" <<'FAKE'
#!/bin/sh
echo "apt $*" >> "$INST_LOG"
exit 0
FAKE
cat > "$INST_ROOT/bin/dpkg" <<'FAKE'
#!/bin/sh
exit 0
FAKE
cat > "$INST_ROOT/bin/curl" <<'FAKE'
#!/bin/sh
while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) shift; printf 'fake-deb-content' > "$1";;
    esac
    shift
done
exit 0
FAKE
cat > "$INST_ROOT/bin/sha256sum" <<'FAKE'
#!/bin/sh
# Test double: answers with the digest the install manifest pins for this file name.
target="$1"
name=$(basename "$target")
manifest=$(dirname "$target")/manifest
if [ -f "$manifest" ]; then
    digest=$(awk -v n="$name" '$1 == n { print $2 }' "$manifest")
    [ -n "$digest" ] && { echo "$digest  $target"; exit 0; }
fi
exec /usr/bin/sha256sum "$@"
FAKE
cat > "$INST_ROOT/bin/proot-distro" <<'FAKE'
#!/bin/sh
if [ "$#" -eq 0 ] || { [ "$1" = "install" ] && [ "$#" -eq 1 ]; }; then
    echo "  -n, --name [NAME]  Set a custom name for the container."
    exit 1
fi
echo "proot-distro $*" >> "$INST_LOG"
case "$1" in
    install)
        shift
        name=""
        while [ "$#" -gt 0 ]; do
            case "$1" in
                -n|--name) shift; name="$1";;
            esac
            shift
        done
        [ -n "$name" ] || name=debian
        mkdir -p "$INST_CONTAINERS/$name"
        ;;
esac
exit 0
FAKE
chmod +x "$INST_ROOT/bin/"*
cat > "$TEST_ROOT/inst-spec" <<SPEC
DESKTOP_SPEC_VERSION=2
DESKTOP_ID=inst
DESKTOP_MODE=proot
DESKTOP_DE=xfce4
DESKTOP_GL_MODE=auto
DESKTOP_PROOT_DISTRO=debian-xfce4-1
DESKTOP_RESOLUTION=1280x720
DESKTOP_WIDTH=1280
DESKTOP_HEIGHT=720
DESKTOP_REFRESH_RATE=30
DESKTOP_PID_FILE=$TEST_ROOT/state/inst.pid
DESKTOP_STATUS_FILE=$TEST_ROOT/state/inst.status
DESKTOP_LOG_FILE=$TEST_ROOT/logs/inst.log
DESKTOP_RENDER_SOCKET=$TEST_ROOT/termux-render
DESKTOP_RELEASE_TAG=termux-hwaccel-6.6-r19
SPEC
INST_LOG="$TEST_ROOT/inst-calls"
INST_CONTAINERS="$INST_ROOT/usr/var/lib/proot-distro/containers"
export INST_LOG INST_CONTAINERS
PATH="$INST_ROOT/bin:$PATH" PREFIX="$INST_ROOT/usr" TMPDIR="$TEST_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/inst-spec" > "$TEST_ROOT/inst-stdout" 2>&1 \
    || { tail -n 8 "$TEST_ROOT/inst-stdout"; tail -n 8 "$TEST_ROOT/logs/inst.log" 2>/dev/null; fail "desktop_install.sh did not complete"; }
grep -q "Desktop install finished" "$TEST_ROOT/logs/inst.log" || fail "install log lacks completion"
grep -q -- "-n debian-xfce4-1" "$TEST_ROOT/inst-calls" || fail "instance alias not passed: $(cat "$TEST_ROOT/inst-calls")"
grep -q "apt install -y .*xfce4" "$TEST_ROOT/inst-calls" || fail "host xfce4 packages missing"
grep -q "login debian-xfce4-1" "$TEST_ROOT/inst-calls" || fail "container session install missing"
[ -d "$INST_CONTAINERS/debian-xfce4-1" ] || fail "container instance directory missing"
[ -f "$INST_CONTAINERS/debian-xfce4-1/.desktop-de-xfce4" ] || fail "container DE marker missing"
# A second run must skip the container apt work (marker present) and still finish.
: > "$TEST_ROOT/inst-calls"
INST_LOG="$TEST_ROOT/inst-calls" INST_CONTAINERS="$INST_ROOT/usr/var/lib/proot-distro/containers" \
    PATH="$INST_ROOT/bin:$PATH" PREFIX="$INST_ROOT/usr" TMPDIR="$TEST_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/inst-spec" > "$TEST_ROOT/inst-stdout2" 2>&1 \
    || { tail -n 8 "$TEST_ROOT/inst-stdout2"; fail "second install run did not complete"; }
grep -q "already present; skipping" "$TEST_ROOT/logs/inst.log" || fail "second run redo container apt"
grep -q "login debian-xfce4-1" "$TEST_ROOT/inst-calls" && fail "second run re-ran container apt"

# Termux has no dbus-x11 package (that is Debian's); a native apt install naming it fails outright.
grep -n 'run apt install -y \$DEB_PATHS xfce4' "$ASSETS/desktop_install.sh" \
    | grep -q 'dbus-x11' && fail "native xfce4 install must not request dbus-x11"

printf 'desktop scripts contract test passed\n'
