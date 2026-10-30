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
mkdir -p "$PREFIX/bin" "$PREFIX/tmp" "$HOME" "$TEST_ROOT/state" "$TEST_ROOT/logs"
cp "$ASSETS"/desktop_*.sh "$TEST_ROOT/"
cp "$ASSETS/desktop_container_statx_aarch64.bin" "$TEST_ROOT/"

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
DESKTOP_RELEASE_TAG=termux-hwaccel-6.6-r27
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
wait_for "backend environment capture" test -f "$TEST_ROOT/capture"
assert_eq "$(sed -n 's/^GL=//p' "$TEST_ROOT/capture")" zink "GL mode"
assert_eq "$(sed -n 's/^RATE=//p' "$TEST_ROOT/capture")" 45 "refresh rate"
assert_eq "$(sed -n 's/^SIZE=//p' "$TEST_ROOT/capture")" 1280x720 "size"
assert_eq "$(sed -n 's/^ROOT=//p' "$TEST_ROOT/capture")" "$HOME/.termux/kwin-runtime" "runtime root"
assert_eq "$(sed -n 's/^LOG=//p' "$TEST_ROOT/capture")" "$TEST_ROOT/logs/test.log" "plasma log"
assert_eq "$(sed -n 's/^DISTRO=//p' "$TEST_ROOT/capture")" "" "native has no distro"
assert_eq "$(status)" starting "status while running"
grep -q "Android EGL backend initialized" "$TEST_ROOT/logs/test.log" || fail "ready marker missing"

# A second start while running is a no-op.
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec"
code=$?
set -e
assert_eq "$code" 73 "existing desktop must not implicitly reuse its renderer"
pid_running || fail "second start killed the desktop"

sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "requested desktop stop returned a failure"
pid_running && fail "desktop still running after stop"
[ ! -f "$TEST_ROOT/state/test.pid" ] || fail "pid file left behind"
assert_eq "$(status)" stopped "status after stop"

# --- 1b. a session left behind by another desktop is stopped before this one starts ----------
sleep 300 &
LEFTOVER=$!
printf '%s\n' "$LEFTOVER" > "$TEST_ROOT/state/other.pid"
printf '%s\n' "999999" > "$TEST_ROOT/state/dead.pid"
write_spec native ""
fake_command start-plasma "exec sleep 300"
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" &
SUPERVISOR=$!
wait_for "pid file" pid_running
kill -0 "$LEFTOVER" 2>/dev/null && fail "another desktop's leftover process was not stopped"
[ ! -f "$TEST_ROOT/state/other.pid" ] || fail "leftover pid file not removed or process survived"
[ ! -f "$TEST_ROOT/state/dead.pid" ] || fail "stale pid file of a dead desktop not removed"
grep -q "left over from another desktop" "$TEST_ROOT/logs/test.log" || fail "leftover stop not logged"
sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "requested desktop stop returned a failure"

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
wait_for "proot arguments capture" test -f "$TEST_ROOT/proot-args"
assert_eq "$(cat "$TEST_ROOT/compositor-args")" "--compositor-only" "compositor flag"
grep -q "login debian" "$TEST_ROOT/proot-args" || fail "proot-distro login debian missing: $(cat "$TEST_ROOT/proot-args")"
grep -q -- "--bind $RUNTIME_DIR:/run/wl" "$TEST_ROOT/proot-args" || fail "runtime dir bind missing"
grep -q "/tmp/plasma-container-session.sh" "$TEST_ROOT/proot-args" || fail "session bind missing"
grep -q "env DESKTOP_GL_MODE=zink" "$TEST_ROOT/proot-args" || fail "container renderer parameter missing"
pid_running || fail "compositor pid not running"
container_pid=$(cat "$TEST_ROOT/state/test.pid.container")
kill -0 "$container_pid" 2>/dev/null || fail "container not running"

sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "requested desktop stop returned a failure"
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
cat > "$INST_ROOT/bin/apt-mark" <<'FAKE'
#!/bin/sh
echo "apt-mark $*" >> "$INST_LOG"
exit "${FAKE_HOLD_STATUS:-0}"
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
DESKTOP_GL_MODE=system
DESKTOP_PROOT_DISTRO=debian-xfce4-1
DESKTOP_RESOLUTION=1280x720
DESKTOP_WIDTH=1280
DESKTOP_HEIGHT=720
DESKTOP_REFRESH_RATE=30
DESKTOP_PID_FILE=$TEST_ROOT/state/inst.pid
DESKTOP_STATUS_FILE=$TEST_ROOT/state/inst.status
DESKTOP_LOG_FILE=$TEST_ROOT/logs/inst.log
DESKTOP_RENDER_SOCKET=$TEST_ROOT/termux-render
DESKTOP_RELEASE_TAG=termux-hwaccel-6.6-r27
SPEC
INST_LOG="$TEST_ROOT/inst-calls"
INST_CONTAINERS="$INST_ROOT/usr/var/lib/proot-distro/containers"
export INST_LOG INST_CONTAINERS
# The container install stages the apt helper into $PREFIX/tmp, from beside the install script.
mkdir -p "$INST_ROOT/usr/tmp"
cp "$ASSETS/desktop_container_plasma_apt.sh" "$TEST_ROOT/desktop_container_plasma_apt.sh"
PATH="$INST_ROOT/bin:$PATH" PREFIX="$INST_ROOT/usr" TMPDIR="$TEST_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/inst-spec" --operation=install > "$TEST_ROOT/inst-stdout" 2>&1 \
    || { tail -n 8 "$TEST_ROOT/inst-stdout"; tail -n 8 "$TEST_ROOT/logs/inst.log" 2>/dev/null; fail "desktop_install.sh did not complete"; }
grep -q "Desktop install finished" "$TEST_ROOT/logs/inst.log" || fail "install log lacks completion"
assert_eq "$(cat "$TEST_ROOT/state/inst.status")" exit:0 "completed install status"
grep -q -- "-n debian-xfce4-1" "$TEST_ROOT/inst-calls" || fail "instance alias not passed: $(cat "$TEST_ROOT/inst-calls")"
grep -q "desktop_container_plasma_apt.sh --statx-backend=proc-fdinfo xfce4 xfce4-terminal dbus-x11" "$TEST_ROOT/inst-calls" || fail "container xfce4 packages missing: $(cat "$TEST_ROOT/inst-calls")"
grep -q "login debian-xfce4-1" "$TEST_ROOT/inst-calls" || fail "container session install missing"
[ -d "$INST_CONTAINERS/debian-xfce4-1" ] || fail "container instance directory missing"
[ -f "$INST_CONTAINERS/debian-xfce4-1/.desktop-de-xfce4" ] || fail "container DE marker missing"
# A second run must skip the container apt work (marker present) and still finish.
: > "$TEST_ROOT/inst-calls"
INST_LOG="$TEST_ROOT/inst-calls" INST_CONTAINERS="$INST_ROOT/usr/var/lib/proot-distro/containers" \
    PATH="$INST_ROOT/bin:$PATH" PREFIX="$INST_ROOT/usr" TMPDIR="$TEST_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/inst-spec" --operation=install > "$TEST_ROOT/inst-stdout2" 2>&1 \
    || { tail -n 8 "$TEST_ROOT/inst-stdout2"; fail "second install run did not complete"; }
grep -q "already present; skipping" "$TEST_ROOT/logs/inst.log" || fail "second run redo container apt"
grep -q "login debian-xfce4-1" "$TEST_ROOT/inst-calls" && fail "second run re-ran container apt"

# --- 8. Turnip ICD provisioning for the kwin_plasma container -----------------------------------
TURNIP_ROOT="$TEST_ROOT/turnip"
ROOTFS="$TURNIP_ROOT/prefix/var/lib/proot-distro/containers/debian-kwin-plasma-1/rootfs"
mkdir -p "$TURNIP_ROOT/bin" "$ROOTFS/etc" "$TURNIP_ROOT/payload/usr/share/vulkan/icd.d" \
    "$TURNIP_ROOT/payload/usr/lib/aarch64-linux-gnu" "$TURNIP_ROOT/tmp"
printf 'ID=debian\nVERSION_CODENAME=trixie\n' > "$ROOTFS/etc/os-release"
printf '{}' > "$TURNIP_ROOT/payload/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
: > "$TURNIP_ROOT/payload/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
COPYFILE_DISABLE=1 tar -czf "$TURNIP_ROOT/turnip.tar.gz" -C "$TURNIP_ROOT/payload" .
# Fake curl: the releases API answers with one turnip tag; downloads copy the payload and log the URL.
cat > "$TURNIP_ROOT/bin/curl" <<'FAKE_CURL'
#!/bin/sh
out=""; url=""
while [ $# -gt 0 ]; do
    case "$1" in -o) out="$2"; shift;; -m) shift;; -*) ;; *) url="$1";; esac
    shift
done
case "$url" in
    *api.github.com*) echo '"tag_name": "mesa-9.9.9-devel-20300101"';;
    *) echo "$url" >> "$FAKE_CURL_URLS"; cp "$FAKE_CURL_PAYLOAD" "$out";;
esac
FAKE_CURL
chmod +x "$TURNIP_ROOT/bin/curl"
cat > "$TURNIP_ROOT/bin/proot-distro" <<'FAKE'
#!/bin/sh
echo "container hold: $*"
exit "${FAKE_TURNIP_HOLD_STATUS:-0}"
FAKE
chmod +x "$TURNIP_ROOT/bin/proot-distro"
{
    printf 'PREFIX=%s\nTMPDIR=%s\nlog() { :; }\nfail() { echo "FAIL: $*" >&2; exit 1; }\nrun() { "$@"; }\n' \
        "$TURNIP_ROOT/prefix" "$TURNIP_ROOT/tmp"
    sed -n '/^TURNIP_REPO=/,/^}/p' "$ASSETS/desktop_install.sh"
    printf 'ensure_turnip_icd debian-kwin-plasma-1\nensure_turnip_icd debian-kwin-plasma-1\n'
} > "$TURNIP_ROOT/run.sh"
FAKE_CURL_URLS="$TURNIP_ROOT/urls" FAKE_CURL_PAYLOAD="$TURNIP_ROOT/turnip.tar.gz" \
    PATH="$TURNIP_ROOT/bin:$PATH" sh "$TURNIP_ROOT/run.sh" || fail "ensure_turnip_icd did not complete"
[ -r "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" ] || fail "Turnip ICD json was not unpacked"
[ -e "$ROOTFS/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so" ] || fail "libvulkan_freedreno.so was not unpacked"
grep -q 'mesa-27.0.0-devel-20261025/mesa-for-android-container_27.0.0-devel-20261025_debian_trixie_arm64.tar.gz' "$TURNIP_ROOT/urls" \
    || fail "Turnip asset URL does not use the pinned tag and container distro"
[ "$(wc -l < "$TURNIP_ROOT/urls")" -eq 1 ] || fail "second run downloaded the Turnip tarball again"
grep -q 'ensure_turnip_icd "\$DESKTOP_PROOT_DISTRO"' "$ASSETS/desktop_install.sh" \
    || fail "proot Plasma install does not provision the ICD"

# forky/sid containers use the trixie Turnip asset, and the Plasma container is created from forky.
printf 'ID=debian\nVERSION_CODENAME=forky\n' > "$ROOTFS/etc/os-release"
rm -f "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json" "$ROOTFS/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
FAKE_CURL_URLS="$TURNIP_ROOT/urls" FAKE_CURL_PAYLOAD="$TURNIP_ROOT/turnip.tar.gz" \
    PATH="$TURNIP_ROOT/bin:$PATH" sh "$TURNIP_ROOT/run.sh" || fail "ensure_turnip_icd failed for a forky container"
tail -n 1 "$TURNIP_ROOT/urls" | grep -q 'debian_trixie_arm64.tar.gz' || fail "forky container did not map to the trixie Turnip asset"
grep -q 'default_base="debian:forky"' "$ASSETS/desktop_install.sh" || fail "Plasma container is not created from forky"
grep -q 'KWIN_WAYLAND_NO_PERMISSION_CHECKS=1 start-plasma --compositor-only' "$ASSETS/desktop_start.sh" \
    || fail "container Plasma must start the compositor with the permission checks off"

# --- 9. Plasma container apt helper: preserve failed maintainer scripts -----------------------
APT_ROOT="$TEST_ROOT/plasma-apt"
mkdir -p "$APT_ROOT/bin" "$APT_ROOT/usr/sbin" "$APT_ROOT/info"
printf '#!/bin/sh\necho real postinst\n' > "$APT_ROOT/info/systemd.postinst"
printf '#!/bin/sh\necho real postinst\n' > "$APT_ROOT/info/udev.postinst"
printf '#!/bin/sh\necho real postinst\n' > "$APT_ROOT/info/dbus.postinst"
cat > "$APT_ROOT/bin/apt-get" <<'FAKE_APT'
#!/bin/sh
echo "apt-get $*" >> "$APT_LOG"
case "$*" in
    update*) exit 0 ;;
    *" -f "*) exit 0 ;;   # repairs broken packages only; never installs the requested ones
    *install*) [ -f "$APT_STATE/first-failed" ] || { : > "$APT_STATE/first-failed"; exit 100; }
               # a retry only succeeds once systemd's postinst no longer fails
               grep -q 'exit 0' "$APT_INFO/systemd.postinst" || exit 100
               : > "$APT_STATE/installed"; exit 0 ;;
esac
FAKE_APT
for tool in getent groupadd useradd; do
    printf '#!/bin/sh\necho "%s $*" >> "$APT_LOG"\n[ "%s" = getent ] && exit 2\nexit 0\n' "$tool" "$tool" > "$APT_ROOT/bin/$tool"
    chmod +x "$APT_ROOT/bin/$tool"
done
printf '#!/bin/sh\necho 998\n' > "$APT_ROOT/bin/id"
chmod +x "$APT_ROOT/bin/id"
cat > "$APT_ROOT/bin/dpkg" <<'FAKE_DPKG'
#!/bin/sh
case "$1" in
    --audit)
        # Only systemd is half configured; udev and dbus are merely waiting on it.
        [ -f "$APT_INFO/systemd.postinst" ] && grep -q 'exit 0' "$APT_INFO/systemd.postinst" && exit 0
        printf 'The following packages have been unpacked but not yet configured.\nThey must be configured using dpkg --configure or the configure\nmenu option in dselect for them to work:\n udev                 /dev/ and hotplug management daemon\n dbus                 simple interprocess messaging system\n\nThe following packages are only half configured, probably due to problems\nconfiguring them the first time. The configuration should be retried using\ndpkg --configure <package> or the configure menu option in dselect:\n systemd              system and service manager\n\n'
        ;;
    --configure) echo "dpkg $*" >> "$APT_LOG" ;;
    -s) [ -f "$APT_STATE/installed" ] && { echo "Status: install ok installed"; exit 0; }; exit 1 ;;
esac
exit 0
FAKE_DPKG
chmod +x "$APT_ROOT/bin/apt-get" "$APT_ROOT/bin/dpkg"
cp "$ASSETS/desktop_container_statx_setup.sh" "$APT_ROOT/desktop_container_statx_setup.sh"
cat >> "$APT_ROOT/desktop_container_statx_setup.sh" <<'MOCK'
configure_proot_statx() {
    [ "$1" = proc-fdinfo ] || return 64
    echo "configured explicit proc-fdinfo" >> "$APT_LOG"
}
restore_legacy_postinsts() { return 0; }
run_proot_config() { [ "$1" = proc-fdinfo ] || return 64; shift; "$@"; }
MOCK
# Run the helper with its in-container paths redirected into the fixture.
sed -e "s#/usr/sbin/policy-rc.d#$APT_ROOT/usr/sbin/policy-rc.d#g" \
    -e "s#^export PATH=#export PATH=$APT_ROOT/bin:#" \
    -e "s#/var/lib/dpkg/info#$APT_ROOT/info#g" \
    "$ASSETS/desktop_container_plasma_apt.sh" > "$APT_ROOT/helper.sh"
set +e
APT_LOG="$APT_ROOT/log" APT_STATE="$APT_ROOT" APT_INFO="$APT_ROOT/info" PATH="$APT_ROOT/bin:$PATH" \
    sh "$APT_ROOT/helper.sh" --statx-backend=proc-fdinfo plasma-desktop dbus-x11 > "$APT_ROOT/out" 2>&1
code=$?
set -e
assert_eq "$code" 100 "container apt exit code"
for pkg in systemd udev dbus; do
    grep -q 'real postinst' "$APT_ROOT/info/$pkg.postinst" || fail "$pkg postinst was changed after failure"
done
[ -x "$APT_ROOT/usr/sbin/policy-rc.d" ] || fail "policy-rc.d was not installed"
grep -q 'dpkg --configure' "$APT_ROOT/log" && fail "apt failure attempted automatic dpkg repair"
[ ! -f "$APT_ROOT/installed" ] || fail "apt failure continued installation"
[ "$(grep -c 'apt-get install -y --no-remove -o' "$APT_ROOT/log")" = 1 ] || fail "apt failure retried installation"
grep -q 'plasma-desktop' "$APT_ROOT/log" || fail "the requested packages were not passed to apt-get"
grep -q 'groupadd --system messagebus' "$APT_ROOT/log" || fail "the messagebus group is not created before dbus is configured"
grep -q 'useradd --system .*messagebus' "$APT_ROOT/log" || fail "the messagebus user is not created before dbus is configured"
grep -q 'sh /tmp/desktop_container_plasma_apt.sh' "$ASSETS/desktop_install.sh" || fail "install script does not run the apt helper"

# Termux has no dbus-x11 package (that is Debian's); a native apt install naming it fails outright.
grep -n 'run apt install -y \$DEB_PATHS xfce4' "$ASSETS/desktop_install.sh" \
    | grep -q 'dbus-x11' && fail "native xfce4 install must not request dbus-x11"


# --- 10. Re-installing over a held xorg-server -------------------------------------------------
# The Xlorie xorg-server is held (apt-mark hold) so `pkg upgrade` cannot replace it; a second desktop
# install must still be able to change it to the pinned build, or apt refuses with "Held packages
# were changed and -y was used without --allow-change-held-packages".
for script in desktop_install.sh xlorie_install.sh; do
    grep -n 'apt install -y --allow-downgrades' "$ASSETS/$script" | grep -v -- '--allow-change-held-packages' \
        && fail "$script has an apt install of the pinned debs that a held xorg-server would block"
done

# --- 11. XFCE4 in a forky container goes through the same apt helper as Plasma -------------------
grep -q 'xfce4) container_packages="xfce4 xfce4-terminal dbus-x11"' "$ASSETS/desktop_install.sh" \
    || fail "XFCE4 container install does not use the systemd-tolerant apt helper"

# --- 12. The install log names the desktop being installed ---------------------------------------
grep -q 'Installing XFCE4 desktop packages' "$TEST_ROOT/logs/inst.log" \
    || fail "XFCE4 install log does not say it installs XFCE4: $(grep Installing "$TEST_ROOT/logs/inst.log")"
grep -q 'Installing KWin' "$TEST_ROOT/logs/inst.log" && fail "XFCE4 install log mentions KWin"

# --- 13. native XFCE4 starts even when start-plasma left $PREFIX/tmp/.X11-unix a symlink ----------
# An X server will not create its file socket (X0) through a symlink; it keeps running on the abstract
# socket only, and desktop_start.sh would wait for X0 until it gave up ("Xlorie did not become ready").
write_spec native "" xfce4
mkdir -p "$TEST_ROOT/kwin-tmp/.X11-unix"
rm -rf "$PREFIX/tmp/.X11-unix"
ln -s "$TEST_ROOT/kwin-tmp/.X11-unix" "$PREFIX/tmp/.X11-unix"
cat > "$PREFIX/bin/Xlorie" <<'FAKE'
#!/bin/sh
exec python3 -I - <<'PY'
import os, socket, time
d = os.path.join(os.environ["PREFIX"], "tmp", ".X11-unix")
if not os.path.islink(d):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(os.path.join(d, "X0"))
    s.listen(1)
time.sleep(300)
PY
FAKE
printf '#!/bin/sh\nexec sleep 300\n' > "$PREFIX/bin/dbus-run-session"
chmod +x "$PREFIX/bin/Xlorie" "$PREFIX/bin/dbus-run-session"
cp "$PREFIX/bin/Xlorie" "$TEST_ROOT/live-Xlorie"
start_socket
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" &
SUPERVISOR=$!
wait_for "the XFCE session to start" grep -q "Starting the native XFCE4 session" "$TEST_ROOT/logs/test.log"
[ -S "$PREFIX/tmp/.X11-unix/X0" ] || fail "Xlorie got no X0 file socket: .X11-unix was left a symlink"
[ ! -L "$PREFIX/tmp/.X11-unix" ] || fail ".X11-unix is still a symlink"
grep -q "did not become ready" "$TEST_ROOT/logs/test.log" && fail "Xlorie was reported not ready"
sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "requested desktop stop returned a failure"

# --- 14. Plasma starts from a clean .X11-unix, so start-plasma can link it to KWin's sockets --------
# After an XFCE session the directory is a real one holding Xlorie's X0; start-plasma only relinks an
# empty directory, so desktop_start.sh has to clear that socket first.
rm -rf "$PREFIX/tmp/.X11-unix"
mkdir -p "$PREFIX/tmp/.X11-unix"
python3 -I -c 'import socket,sys; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1])' "$PREFIX/tmp/.X11-unix/X0"
[ -S "$PREFIX/tmp/.X11-unix/X0" ] || fail "could not stage a stale Xlorie X0 socket"
write_spec native ""
fake_command start-plasma "[ -d \"$PREFIX/tmp/.X11-unix\" ] && ls -A \"$PREFIX/tmp/.X11-unix\" > \"$TEST_ROOT/x11-at-plasma-start\"; exec sleep 300"
start_socket
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" &
SUPERVISOR=$!
wait_for "pid file" pid_running
[ ! -e "$PREFIX/tmp/.X11-unix/X0" ] || fail "stale Xlorie X0 socket still present when start-plasma ran"
sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "requested desktop stop returned a failure"

# --- 15. a failed package download is a failed install --------------------------------------------
# fetch_debs runs inside $(...), so the `exit` of its fail() used to end only that subshell: the install
# went on with an empty package list, apt found everything "already installed" and the script printed
# "Desktop install finished" although the pinned KWin packages had never been downloaded.
FAIL_ROOT="$TEST_ROOT/dlfail"
mkdir -p "$FAIL_ROOT"
cp -a "$INST_ROOT/bin" "$FAIL_ROOT/bin"
cp -a "$INST_ROOT/usr" "$FAIL_ROOT/usr"
cat > "$FAIL_ROOT/bin/curl" <<'FAKE'
#!/bin/sh
exit 35
FAKE
chmod +x "$FAIL_ROOT/bin/curl"
cat > "$TEST_ROOT/dlfail-spec" <<SPEC
DESKTOP_SPEC_VERSION=2
DESKTOP_ID=dlfail
DESKTOP_MODE=native
DESKTOP_DE=kwin_plasma
DESKTOP_GL_MODE=system
DESKTOP_PROOT_DISTRO=
DESKTOP_RESOLUTION=1280x720
DESKTOP_WIDTH=1280
DESKTOP_HEIGHT=720
DESKTOP_REFRESH_RATE=30
DESKTOP_PID_FILE=$TEST_ROOT/state/dlfail.pid
DESKTOP_STATUS_FILE=$TEST_ROOT/state/dlfail.status
DESKTOP_LOG_FILE=$TEST_ROOT/logs/dlfail.log
DESKTOP_RENDER_SOCKET=$TEST_ROOT/termux-render
DESKTOP_RELEASE_TAG=termux-hwaccel-6.6-r27
SPEC
rm -rf "$FAIL_ROOT/usr/tmp"/desktop-debs-* "$TEST_ROOT/tmp"/desktop-debs-* 2>/dev/null || true
: > "$FAIL_ROOT/calls"
set +e
INST_LOG="$FAIL_ROOT/calls" INST_CONTAINERS="$FAIL_ROOT/usr/var/lib/proot-distro/containers" \
    PATH="$FAIL_ROOT/bin:$PATH" PREFIX="$FAIL_ROOT/usr" TMPDIR="$FAIL_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/dlfail-spec" --operation=install > "$FAIL_ROOT/stdout" 2>&1
code=$?
set -e
[ "$code" -ne 0 ] || fail "install reported success although every package download failed"
grep -q "Desktop install finished" "$TEST_ROOT/logs/dlfail.log" && fail "log says finished after a failed download"
grep -q "plasma-desktop" "$FAIL_ROOT/calls" && fail "apt installed the desktop packages after the download failed: $(cat "$FAIL_ROOT/calls")"
grep -q "Desktop install failed" "$TEST_ROOT/logs/dlfail.log" || fail "the failed download was not reported in the log"

# --- 16. downloads retry transfers that break half way ----------------------------------------------
# `curl --retry` alone only retries timeouts and some HTTP codes; a connection cut mid-transfer
# ("(56) SSL_read: unexpected eof", common behind a proxy) failed the whole install on the first try.
grep -q 'curl -fL --retry [0-9]* --retry-all-errors' "$ASSETS/desktop_install.sh" \
    || fail "package downloads do not retry connection errors (--retry-all-errors)"


# --- 17. legacy Auto and unknown renderers fail before invoking any backend -----------------
for mode in auto unknown ''; do
    write_spec native ""
    sed "s/^DESKTOP_GL_MODE=.*/DESKTOP_GL_MODE=$mode/" "$TEST_ROOT/spec" > "$TEST_ROOT/invalid-spec"
    rm -f "$TEST_ROOT/capture"
    set +e
    sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/invalid-spec" > "$TEST_ROOT/invalid-out" 2>&1
    code=$?
    set -e
    assert_eq "$code" 64 "invalid renderer exit"
    assert_eq "$(status)" exit:64 "invalid renderer status"
    [ ! -f "$TEST_ROOT/capture" ] || fail "invalid renderer invoked a backend"
done

# --- 18. apt failure preserves its exit code, command and concrete dependency diagnosis --------
cat > "$FAIL_ROOT/bin/pkg" <<'FAKE'
#!/bin/sh
echo 'E: Unmet dependencies: libtest Depends: libmissing (= 2)'
exit 100
FAKE
chmod +x "$FAIL_ROOT/bin/pkg"
set +e
PATH="$FAIL_ROOT/bin:$PATH" PREFIX="$FAIL_ROOT/usr" TMPDIR="$FAIL_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/dlfail-spec" --operation=install > "$FAIL_ROOT/apt-stdout" 2>&1
code=$?
set -e
assert_eq "$code" 100 "install apt dependency exit"
assert_eq "$(cat "$TEST_ROOT/state/dlfail.status")" exit:100 "failed install status"
grep -q 'Command failed (exit 100): pkg install -y x11-repo' "$FAIL_ROOT/apt-stdout" \
    || fail "apt failure omitted command or exit code"
grep -q 'E: Unmet dependencies: libtest Depends: libmissing (= 2)' "$FAIL_ROOT/apt-stdout" \
    || fail "apt failure omitted dependency diagnosis"

# --- 19. container renderer contracts clear inherited paths and never switch after failure ---
SESSION_FIXTURE="$TEST_ROOT/session"
mkdir -p "$SESSION_FIXTURE/bin" "$SESSION_FIXTURE/usr/bin" "$SESSION_FIXTURE/runtime" \
    "$SESSION_FIXTURE/usr/lib/aarch64-linux-gnu/qt6/plugins/platforms" "$SESSION_FIXTURE/usr/libexec" \
    "$SESSION_FIXTURE/usr/lib/polkit-1" "$SESSION_FIXTURE/usr/lib/sysusers.d" "$SESSION_FIXTURE/info"
for tool in plasmashell; do
    printf '#!/bin/sh\nexit 0\n' > "$SESSION_FIXTURE/bin/$tool"
    chmod +x "$SESSION_FIXTURE/bin/$tool"
done
for tool in dbus-daemon dbus-send; do
    printf '#!/bin/sh\nexit 0\n' > "$SESSION_FIXTURE/usr/bin/$tool"
    chmod +x "$SESSION_FIXTURE/usr/bin/$tool"
done
: > "$SESSION_FIXTURE/usr/lib/aarch64-linux-gnu/qt6/plugins/platforms/libqwayland.so"
printf '#!/bin/sh\nexit 0\n' > "$SESSION_FIXTURE/usr/libexec/packagekitd"
chmod +x "$SESSION_FIXTURE/usr/libexec/packagekitd"
printf '#!/bin/sh\nexit 0\n' > "$SESSION_FIXTURE/usr/lib/polkit-1/polkitd"
chmod +x "$SESSION_FIXTURE/usr/lib/polkit-1/polkitd"
cat > "$SESSION_FIXTURE/bin/getent" <<'FAKE'
#!/bin/sh
[ "$2" = polkitd ] || exit 2
[ -f "$ACCOUNT_STATE/$1" ] || exit 2
case "$1" in
    passwd) echo "polkitd:x:${ACCOUNT_UID:-998}:${ACCOUNT_GID:-998}::${ACCOUNT_HOME:-/}:${ACCOUNT_SHELL:-/sbin/nologin}" ;;
    group) echo "polkitd:x:${ACCOUNT_NAMED_GID:-998}:" ;;
    shadow) echo "polkitd:${ACCOUNT_PASSWORD-!}:1:0:99999:7::${ACCOUNT_EXPIRE-1}:" ;;
esac
FAKE
cat > "$SESSION_FIXTURE/usr/bin/systemd-sysusers" <<'FAKE'
#!/bin/sh
[ "$1" = polkit.conf ] || exit 64
[ "${ACCOUNT_USERADD_FAIL:-0}" -eq 0 ] || exit "$ACCOUNT_USERADD_FAIL"
: > "$ACCOUNT_STATE/passwd"
: > "$ACCOUNT_STATE/group"
: > "$ACCOUNT_STATE/shadow"
FAKE
cat > "$SESSION_FIXTURE/bin/id" <<'FAKE'
#!/bin/sh
case "$1" in -u) echo "${ACCOUNT_UID:-998}" ;; -g) echo "${ACCOUNT_GID:-998}" ;; esac
FAKE
chmod +x "$SESSION_FIXTURE/bin/getent" "$SESSION_FIXTURE/usr/bin/systemd-sysusers" "$SESSION_FIXTURE/bin/id"
printf 'u! polkitd - "User for polkitd"\n' > "$SESSION_FIXTURE/usr/lib/sysusers.d/polkit.conf"
sed "s#/usr/#$SESSION_FIXTURE/usr/#g" "$ASSETS/desktop_container_statx_setup.sh" \
    > "$SESSION_FIXTURE/desktop_container_statx_setup.sh"
cp "$ASSETS/desktop_container_statx_aarch64.bin" "$SESSION_FIXTURE/desktop_container_statx_aarch64.bin"
cp "$ASSETS/desktop_container_run_config.sh" "$SESSION_FIXTURE/desktop_container_run_config.sh"
cat >> "$SESSION_FIXTURE/desktop_container_statx_setup.sh" <<'MOCK'
configure_proot_statx() {
    [ "$1" = proc-fdinfo ] && [ -r "$2" ] || return 64
}
restore_legacy_postinsts() { return 0; }
run_proot_config() { [ "$1" = proc-fdinfo ] || return 64; shift; "$@"; }
MOCK
export ACCOUNT_STATE="$SESSION_FIXTURE" DESKTOP_STATX_BACKEND=proc-fdinfo
export DESKTOP_KDE_SERVICE_POLICY=kwin-wayland QT_QPA_PLATFORM=wayland XDG_SESSION_TYPE=wayland
cat > "$SESSION_FIXTURE/bin/apt-get" <<'FAKE'
#!/bin/sh
echo "unexpected apt-get: $*" >&2
exit 99
FAKE
chmod +x "$SESSION_FIXTURE/bin/apt-get"
# Run only renderer/dependency setup, never launch a real desktop or service in this fixture.
sed '/^# Each desktop owns/,$d' "$ASSETS/desktop_container_session.sh" \
    | sed -e "s#/usr/#$SESSION_FIXTURE/usr/#g" \
          -e "s#/var/lib/dpkg/info#$SESSION_FIXTURE/info#g" \
          -e "s#^export PATH=.*#export PATH=$SESSION_FIXTURE/bin:$PATH#" > "$SESSION_FIXTURE/preflight.sh"
cat >> "$SESSION_FIXTURE/preflight.sh" <<'CAPTURE'
printf 'RHI=%s\nQUICK=%s\nICD=%s\nDRIVER=%s\n' \
    "${QSG_RHI_BACKEND:-unset}" "${QT_QUICK_BACKEND:-unset}" \
    "${VK_ICD_FILENAMES:-unset}" "${GALLIUM_DRIVER:-unset}"
CAPTURE
DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    QSG_RHI_BACKEND=vulkan QT_QUICK_BACKEND=legacy VK_ICD_FILENAMES=/bad/inherited.json \
    PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh" \
    || fail "explicit software mode required Vulkan or failed to clear an inherited renderer"
grep -q 'RHI=unset' "$SESSION_FIXTURE/runtime/plasma-container.log" || fail "software inherited Vulkan RHI"
grep -q 'QUICK=software' "$SESSION_FIXTURE/runtime/plasma-container.log" || fail "software mode did not select Qt software"
grep -q 'ICD=unset' "$SESSION_FIXTURE/runtime/plasma-container.log" || fail "software inherited a Vulkan ICD"
grep -q 'DRIVER=llvmpipe' "$SESSION_FIXTURE/runtime/plasma-container.log" || fail "software Mesa mode is not llvmpipe"
[ -f "$SESSION_FIXTURE/passwd" ] && [ -f "$SESSION_FIXTURE/group" ] || fail "explicit sysusers was not called"
# Provisioning failure and an unsafe root identity stop before any bus or desktop is launched.
rm "$SESSION_FIXTURE/passwd"
set +e
ACCOUNT_USERADD_FAIL=17 DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
code=$?
set -e
assert_eq "$code" 17 "PolicyKit service user creation failure exit"
: > "$SESSION_FIXTURE/passwd"
set +e
ACCOUNT_UID=0 DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
code=$?
set -e
[ "$code" -ne 0 ] || fail "PolicyKit was allowed to run with a root service identity"
grep -q 'PolicyKit uid/gid is invalid or root' "$SESSION_FIXTURE/runtime/plasma-container.log" \
    || fail "unsafe PolicyKit identity did not produce a concrete error"
for bad_identity in ACCOUNT_NAMED_GID=997 ACCOUNT_HOME=/root ACCOUNT_SHELL=/bin/sh ACCOUNT_PASSWORD= ACCOUNT_EXPIRE=; do
    set +e
    env "$bad_identity" DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
        PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
    code=$?
    set -e
    [ "$code" -ne 0 ] || fail "unsafe PolicyKit identity was accepted: $bad_identity"
done
# Debian trixie uses u; forky uses u!. Validate the packaged definition without creating users here.
printf 'u polkitd - "User for polkitd"\n' > "$SESSION_FIXTURE/usr/lib/sysusers.d/polkit.conf"
ACCOUNT_EXPIRE= DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh" \
    || fail "standard trixie u account with empty expiry was rejected"
for definition in unknown duplicate; do
    case "$definition" in
        unknown) printf 'x polkitd - "User for polkitd"\n' ;;
        duplicate) printf 'u polkitd - "User for polkitd"\nu! polkitd - "User for polkitd"\n' ;;
    esac > "$SESSION_FIXTURE/usr/lib/sysusers.d/polkit.conf"
    set +e
    DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
        PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
    code=$?
    set -e
    assert_eq "$code" 65 "invalid standard sysusers definition: $definition"
done
printf 'u! polkitd - "User for polkitd"\n' > "$SESSION_FIXTURE/usr/lib/sysusers.d/polkit.conf"
set +e
DESKTOP_STATX_BACKEND=auto DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
code=$?
set -e
assert_eq "$code" 64 "unspecified proot statx backend"
# The service set is chosen explicitly before any process starts, never after a daemon exits.
for invalid_policy in DESKTOP_KDE_SERVICE_POLICY= DESKTOP_KDE_SERVICE_POLICY=auto QT_QPA_PLATFORM=xcb XDG_SESSION_TYPE=x11; do
    set +e
    env "$invalid_policy" DESKTOP_GL_MODE=llvmpipe XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
        PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh" > "$SESSION_FIXTURE/policy-out" 2>&1
    code=$?
    set -e
    assert_eq "$code" 64 "invalid explicit Wayland service policy: $invalid_policy"
done
# The GPU mode must fail with the requested ICD missing, never use the inherited software setting.
: > "$SESSION_FIXTURE/usr/lib/aarch64-linux-gnu/libvulkan.so"
set +e
DESKTOP_GL_MODE=zink XDG_RUNTIME_DIR="$SESSION_FIXTURE/runtime" \
    QT_QUICK_BACKEND=software PATH="$SESSION_FIXTURE/bin:$PATH" sh "$SESSION_FIXTURE/preflight.sh"
code=$?
set -e
[ "$code" -ne 0 ] || fail "GPU mode succeeded without its Turnip ICD"
grep -q 'Turnip Vulkan ICD not found' "$SESSION_FIXTURE/runtime/plasma-container.log" \
    || fail "GPU failure did not name its missing ICD"
grep -q '^QUICK=software' "$SESSION_FIXTURE/runtime/plasma-container.log" \
    && fail "GPU failure continued with software mode"

# --- 20. a dead/unready X server must never launch XFCE clients ------------------------------
cat > "$PREFIX/bin/dbus-run-session" <<FAKE
#!/bin/sh
: > "$TEST_ROOT/unexpected-xfce-session"
exit 0
FAKE
chmod +x "$PREFIX/bin/dbus-run-session"
for desktop_mode in native proot; do
    write_spec "$desktop_mode" debian xfce4
    cat > "$PREFIX/bin/Xlorie" <<'FAKE'
#!/bin/sh
echo 'ERROR: strict EGL initialization failed'
exit 23
FAKE
    chmod +x "$PREFIX/bin/Xlorie"
    rm -f "$TEST_ROOT/unexpected-xfce-session" "$TEST_ROOT/proot-args"
    set +e
    sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/unready-out" 2>&1
    code=$?
    set -e
    assert_eq "$code" 23 "Xlorie startup failure exit"
    assert_eq "$(status)" exit:23 "Xlorie startup failure status"
    [ ! -f "$TEST_ROOT/unexpected-xfce-session" ] || fail "dead Xlorie launched native XFCE clients"
    [ ! -f "$TEST_ROOT/proot-args" ] || fail "dead Xlorie launched container XFCE clients"
    [ ! -f "$TEST_ROOT/state/test.pid" ] || fail "dead Xlorie pid file survived"
    grep -q 'strict EGL initialization failed' "$TEST_ROOT/logs/test.log" || fail "Xlorie raw error was lost"
    grep -q 'Starting the .* XFCE4 session' "$TEST_ROOT/logs/test.log" && fail "dead Xlorie logged a session start"
done
# A live process that never publishes its socket times out without launching clients.
write_spec native "" xfce4
printf '#!/bin/sh\nexec sleep 300\n' > "$PREFIX/bin/Xlorie"
set +e
DESKTOP_BACKEND_WAIT_SECONDS=1 sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/unready-out" 2>&1
code=$?
set -e
assert_eq "$code" 124 "Xlorie readiness timeout"
assert_eq "$(status)" exit:124 "Xlorie timeout status"
[ ! -f "$TEST_ROOT/unexpected-xfce-session" ] || fail "unready Xlorie launched clients"

# --- 21. a stale Wayland socket cannot make a failed compositor ready -----------------------
write_spec proot debian
mkdir -p "$RUNTIME_DIR"
python3 -I - "$RUNTIME_DIR/wayland-0" <<'PY_SOCKET'
import os, socket, sys
path = sys.argv[1]
if os.path.exists(path): os.unlink(path)
s = socket.socket(socket.AF_UNIX)
s.bind(path)
PY_SOCKET
fake_command start-plasma "echo 'ERROR: strict compositor EGL initialization failed'; exit 31"
rm -f "$TEST_ROOT/proot-args"
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/unready-out" 2>&1
code=$?
set -e
assert_eq "$code" 31 "compositor startup failure exit"
assert_eq "$(status)" exit:31 "compositor startup failure status"
[ ! -f "$TEST_ROOT/proot-args" ] || fail "failed compositor launched container clients through stale socket"
[ ! -f "$TEST_ROOT/state/test.pid" ] || fail "failed compositor pid file survived"
grep -q 'strict compositor EGL initialization failed' "$TEST_ROOT/logs/test.log" || fail "compositor raw error was not copied into app log"

# --- 22. explicitly started authority must be alive and own its bus name --------------------
SERVICE_FIXTURE="$TEST_ROOT/service"
mkdir -p "$SERVICE_FIXTURE/bin"
cat > "$SERVICE_FIXTURE/bin/dbus-send" <<'FAKE'
#!/bin/sh
if [ "${FAKE_DBUS_OWNED:-0}" = 1 ]; then echo 'boolean true'; else echo 'boolean false'; fi
FAKE
chmod +x "$SERVICE_FIXTURE/bin/dbus-send"
sed -n '/^wait_for_system_service()/,/^}/p' "$ASSETS/desktop_container_session.sh" \
    | sed "s#/usr/bin/dbus-send#$SERVICE_FIXTURE/bin/dbus-send#g" > "$SERVICE_FIXTURE/check.sh"
cat >> "$SERVICE_FIXTURE/check.sh" <<'CHECK'
sleep 300 &
pid=$!
FAKE_DBUS_OWNED=1
export FAKE_DBUS_OWNED
wait_for_system_service "$pid" org.freedesktop.PolicyKit1 PolicyKit || exit 1
kill -TERM "$pid"
wait "$pid" 2>/dev/null || true
FAKE_DBUS_OWNED=0
sh -c 'echo "ERROR: authority startup rejected"; exit 27' &
pid=$!
sleep 0.1
wait_for_system_service "$pid" org.freedesktop.PolicyKit1 PolicyKit
code=$?
[ "$code" = 27 ] || exit 1
CHECK
sh "$SERVICE_FIXTURE/check.sh" > "$SERVICE_FIXTURE/out" 2>&1 \
    || fail "authority readiness did not require its process and bus name or lost its error code"
grep -q 'authority startup rejected' "$SERVICE_FIXTURE/out" || fail "authority raw startup error was lost"
grep -q 'PolicyKit exited before acquiring org.freedesktop.PolicyKit1 (status 27)' "$SERVICE_FIXTURE/out" \
    || fail "authority readiness omitted its original exit status"

# Session readiness must connect once to the explicitly started bus and preserve failure status.
BUS_FIXTURE="$TEST_ROOT/session-bus"
mkdir -p "$BUS_FIXTURE/bin" "$BUS_FIXTURE/runtime"
cat > "$BUS_FIXTURE/bin/dbus-daemon" <<'FAKE'
#!/bin/sh
printf '%s\n' "$*" >> "$BUS_TEST_LOG"
printf 'unix:path=%s/session.sock,guid=test\n' "$BUS_DIR"
printf '999999\n' >&3
FAKE
cat > "$BUS_FIXTURE/bin/dbus-send" <<'FAKE'
#!/bin/sh
printf '%s\n' "$*" >> "$BUS_TEST_LOG"
[ "$1" = "--bus=unix:path=$BUS_DIR/session.sock,guid=test" ] || exit 65
exit "${BUS_TEST_STATUS:-0}"
FAKE
chmod +x "$BUS_FIXTURE/bin/dbus-daemon" "$BUS_FIXTURE/bin/dbus-send"
sed -n '/^\/usr\/bin\/dbus-daemon --session/,/^echo "Started private container system and session D-Bus"/p' \
    "$ASSETS/desktop_container_session.sh" \
    | sed "s#/usr/bin/#$BUS_FIXTURE/bin/#g" > "$BUS_FIXTURE/check.sh"
printf '\necho SESSION_READY\n' >> "$BUS_FIXTURE/check.sh"
export BUS_DIR="$BUS_FIXTURE/runtime" BUS_TEST_LOG="$BUS_FIXTURE/calls" SESSION_PIDS=""
BUS_TEST_STATUS=0 sh "$BUS_FIXTURE/check.sh" > "$BUS_FIXTURE/success" 2>&1 \
    || fail "session readiness rejected the explicit bus"
grep -q SESSION_READY "$BUS_FIXTURE/success" || fail "ready session did not continue"
grep -q -- "--address=unix:path=$BUS_DIR/session.sock" "$BUS_TEST_LOG" \
    || fail "session daemon did not receive its fixed runtime socket address"
: > "$BUS_TEST_LOG"
set +e
BUS_TEST_STATUS=37 sh "$BUS_FIXTURE/check.sh" > "$BUS_FIXTURE/failure" 2>&1
code=$?
set -e
assert_eq "$code" 37 "session bus readiness failure status"
assert_eq "$(wc -l < "$BUS_TEST_LOG" | tr -d ' ')" 2 "one daemon start and one readiness request"
! grep -q SESSION_READY "$BUS_FIXTURE/failure" || fail "unavailable session bus continued startup"
grep -q 'unavailable at .*session.sock,guid=test (status 37)' "$BUS_FIXTURE/failure" \
    || fail "session bus readiness omitted its explicit address or original status"

# --- 23. runtime XFCE errors and immediate native compositor exits keep their status --------
cp "$TEST_ROOT/live-Xlorie" "$PREFIX/bin/Xlorie"
printf '#!/bin/sh\nexit 37\n' > "$PREFIX/bin/dbus-run-session"
write_spec native "" xfce4
set +e
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/xfce-runtime-out" 2>&1
code=$?
set -e
assert_eq "$code" 37 "runtime XFCE session failure exit"
assert_eq "$(status)" exit:37 "runtime XFCE failure status"
write_spec native ""
fake_command start-plasma "exit 29"
for attempt in 1 2 3 4 5; do
    set +e
    sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/native-fast-out" 2>&1
    code=$?
    set -e
    assert_eq "$code" 29 "immediate compositor failure exit"
    assert_eq "$(status)" exit:29 "immediate compositor failure status"
done

# --- 24. user stop owns termination during readiness and after 'stopped' is already written --
write_spec native "" xfce4
printf '#!/bin/sh\nexec sleep 300\n' > "$PREFIX/bin/Xlorie"
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/stop-during-readiness" 2>&1 &
SUPERVISOR=$!
wait_for "unready server pid" pid_running
sh "$TEST_ROOT/desktop_stop.sh" "$TEST_ROOT/spec"
wait "$SUPERVISOR" || fail "user stop during readiness returned failure"
assert_eq "$(status)" stopped "stop during readiness status"
write_spec native ""
fake_command start-plasma "exec sleep 300"
sh "$TEST_ROOT/desktop_start.sh" "$TEST_ROOT/spec" > "$TEST_ROOT/stop-already-written" 2>&1 &
SUPERVISOR=$!
wait_for "native server pid" pid_running
printf 'stopped\n' > "$TEST_ROOT/state/test.status"
kill -TERM "$(cat "$TEST_ROOT/state/test.pid")"
wait "$SUPERVISOR" || fail "already completed user stop returned failure"
assert_eq "$(status)" stopped "completed stop must not become failed"

# --- 25. package holds are required, including cached Turnip retries -----------------------
set +e
FAKE_HOLD_STATUS=42 PATH="$INST_ROOT/bin:$PATH" PREFIX="$INST_ROOT/usr" TMPDIR="$TEST_ROOT/tmp" \
    sh "$TEST_ROOT/desktop_install.sh" "$TEST_ROOT/inst-spec" --operation=install > "$TEST_ROOT/hold-failure" 2>&1
code=$?
set -e
[ "$code" -ne 0 ] || fail "failed Xserver package hold reported installation success"
grep -q 'Command failed (exit 42): apt-mark hold xorg-server' "$TEST_ROOT/hold-failure" \
    || fail "package hold lost its failing command or status"
grep -q 'Desktop install finished' "$TEST_ROOT/hold-failure" && fail "failed hold reported finished"
set +e
FAKE_TURNIP_HOLD_STATUS=43 FAKE_CURL_URLS="$TURNIP_ROOT/urls" FAKE_CURL_PAYLOAD="$TURNIP_ROOT/turnip.tar.gz" \
    PATH="$TURNIP_ROOT/bin:$PATH" sh "$TURNIP_ROOT/run.sh" > "$TURNIP_ROOT/hold-failure" 2>&1
code=$?
set -e
[ "$code" -ne 0 ] || fail "cached Turnip skipped a failed package hold"
grep -q 'holding the container Turnip packages' "$TURNIP_ROOT/hold-failure" \
    || fail "Turnip package hold failure was not reported"

# --- 26. legacy restoration never retries package errors or accepts upgrades ---------------
REPAIR_ROOT="$TEST_ROOT/repair"
mkdir -p "$REPAIR_ROOT/bin" "$REPAIR_ROOT/info" "$REPAIR_ROOT/state"
printf '#!/bin/sh\nexit 0\n' > "$REPAIR_ROOT/info/example.postinst"
cat > "$REPAIR_ROOT/bin/dpkg-query" <<'FAKE'
#!/bin/sh
case "$*" in *Version*) echo 1 ;; *Architecture*) echo arm64 ;; esac
FAKE
cat > "$REPAIR_ROOT/bin/dpkg-deb" <<'FAKE'
#!/bin/sh
case "$1" in
    --field) case "$3" in Version) echo 1 ;; Package) echo example ;; Architecture) echo arm64 ;; esac ;;
    --control) mkdir -p "$3"; printf '#!/bin/sh\n# original package\nexit 0\n' > "$3/postinst" ;;
esac
FAKE
cat > "$REPAIR_ROOT/bin/apt-get" <<'FAKE'
#!/bin/sh
[ "${DEBIAN_FRONTEND:-}" = noninteractive ] || exit 98
echo "apt-get $* frontend=$DEBIAN_FRONTEND" >> "$REPAIR_LOG"
case "$*" in
    *' download example=1') : > example_1_arm64.deb ;;
    '-s '*) printf '%s upgraded, 0 newly installed, 1 reinstalled, 0 to remove and 0 not upgraded.\n' "${REPAIR_UPGRADES:-0}" ;;
    '-y '*) exit 43 ;;
    *) exit 99 ;;
esac
FAKE
chmod +x "$REPAIR_ROOT/bin/"*
sed -e "s#/var/lib/dpkg/info#$REPAIR_ROOT/info#g" \
    -e "s#/var/lib/termux-proot-statx#$REPAIR_ROOT/state#g" \
    "$ASSETS/desktop_container_statx_setup.sh" > "$REPAIR_ROOT/setup.sh"
cat > "$REPAIR_ROOT/check.sh" <<'CHECK'
#!/bin/sh
. "$REPAIR_SETUP"
restore_legacy_postinsts
CHECK
export REPAIR_SETUP="$REPAIR_ROOT/setup.sh" REPAIR_LOG="$REPAIR_ROOT/log"
# Exercise the real restoration function with a conflicting parent frontend.
export DEBIAN_FRONTEND=dialog
set +e
PATH="$REPAIR_ROOT/bin:$PATH" sh "$REPAIR_ROOT/check.sh" > "$REPAIR_ROOT/out" 2>&1
code=$?
set -e
assert_eq "$code" 43 "original package restoration failure exit"
assert_eq "$DEBIAN_FRONTEND" dialog "restoration frontend must not escape its subshell"
assert_eq "$(grep -c 'frontend=noninteractive$' "$REPAIR_ROOT/log")" 3 "all restoration APT phases require the explicit frontend"
assert_eq "$(grep -c '^apt-get -y ' "$REPAIR_ROOT/log")" 1 "restoration must not retry"
[ "$(cat "$REPAIR_ROOT/info/example.postinst")" = "$(printf '#!/bin/sh\nexit 0')" ] \
    || fail "failed restoration rewrote the existing postinst"
: > "$REPAIR_ROOT/log"
set +e
REPAIR_UPGRADES=1 PATH="$REPAIR_ROOT/bin:$PATH" sh "$REPAIR_ROOT/check.sh" > "$REPAIR_ROOT/upgrade-out" 2>&1
code=$?
set -e
assert_eq "$code" 65 "unexpected upgrade plan must fail before package changes"
grep -q '^apt-get -y ' "$REPAIR_ROOT/log" && fail "restoration executed an upgrade plan"
grep -q 'would upgrade/remove packages' "$REPAIR_ROOT/upgrade-out" || fail "unsafe package plan was not diagnosed"

# --- 27. container removal uses only the explicit method and preserves its failure ----------
REMOVE_ROOT="$TEST_ROOT/remove"
mkdir -p "$REMOVE_ROOT/bin" "$REMOVE_ROOT/usr/var/lib/proot-distro/containers/debian-test"
cat > "$REMOVE_ROOT/bin/proot-distro" <<'FAKE'
#!/bin/sh
printf '%s\n' "$*" >> "$REMOVE_CALLS"
echo 'original uninstall diagnostic' >&2
exit "${REMOVE_STATUS:-0}"
FAKE
chmod +x "$REMOVE_ROOT/bin/proot-distro"
cat > "$REMOVE_ROOT/spec" <<'SPEC'
DESKTOP_MODE=proot
DESKTOP_REMOVE_METHOD=proot-distro
DESKTOP_PROOT_DISTRO=debian-test
SPEC
printf 'DESKTOP_STATUS_FILE="%s"\n' "$REMOVE_ROOT/status" >> "$REMOVE_ROOT/spec"
export REMOVE_CALLS="$REMOVE_ROOT/calls"
set +e
REMOVE_STATUS=42 PREFIX="$REMOVE_ROOT/usr" PATH="$REMOVE_ROOT/bin:/usr/bin:/bin" \
    /bin/sh "$ASSETS/desktop_remove.sh" "$REMOVE_ROOT/spec" > "$REMOVE_ROOT/out" 2>&1
code=$?
set -e
assert_eq "$code" 42 "uninstall original failure exit"
assert_eq "$(cat "$REMOVE_ROOT/status")" exit:42 "uninstall failure state"
assert_eq "$(wc -l < "$REMOVE_CALLS" | tr -d ' ')" 1 "uninstall must not retry"
[ -d "$REMOVE_ROOT/usr/var/lib/proot-distro/containers/debian-test" ] || fail "failed uninstall fell back to deleting rootfs"
grep -q 'original uninstall diagnostic' "$REMOVE_ROOT/out" || fail "uninstall original error was hidden"
assert_eq "$(cat "$REMOVE_CALLS")" 'remove debian-test' "explicit uninstall command"
REMOVE_STATUS=0 PREFIX="$REMOVE_ROOT/usr" PATH="$REMOVE_ROOT/bin:/usr/bin:/bin" \
    /bin/sh "$ASSETS/desktop_remove.sh" "$REMOVE_ROOT/spec" > "$REMOVE_ROOT/success" 2>&1 \
    || fail "successful explicit uninstall failed"
assert_eq "$(cat "$REMOVE_ROOT/status")" exit:0 "uninstall success state"
set +e
PREFIX="$REMOVE_ROOT/no-tools" PATH=/usr/bin:/bin /bin/sh "$ASSETS/desktop_remove.sh" "$REMOVE_ROOT/spec" > "$REMOVE_ROOT/missing-tool" 2>&1
code=$?
set -e
assert_eq "$code" 127 "missing explicit uninstall tool"
for invalid in missing-method wrong-method native-mode invalid-alias missing-spec; do
    cp "$REMOVE_ROOT/spec" "$REMOVE_ROOT/invalid-spec"
    case "$invalid" in
        missing-method) sed '/DESKTOP_REMOVE_METHOD=/d' "$REMOVE_ROOT/spec" > "$REMOVE_ROOT/invalid-spec" ;;
        wrong-method) printf 'DESKTOP_REMOVE_METHOD=recursive-delete\n' >> "$REMOVE_ROOT/invalid-spec" ;;
        native-mode) printf 'DESKTOP_MODE=native\n' >> "$REMOVE_ROOT/invalid-spec" ;;
        invalid-alias) printf 'DESKTOP_PROOT_DISTRO=../other\n' >> "$REMOVE_ROOT/invalid-spec" ;;
        missing-spec) rm "$REMOVE_ROOT/invalid-spec" ;;
    esac
    calls_before=$(wc -l < "$REMOVE_CALLS" | tr -d ' ')
    set +e
    PREFIX="$REMOVE_ROOT/usr" PATH="$REMOVE_ROOT/bin:/usr/bin:/bin" \
        /bin/sh "$ASSETS/desktop_remove.sh" "$REMOVE_ROOT/invalid-spec" > "$REMOVE_ROOT/invalid-out" 2>&1
    code=$?
    set -e
    assert_eq "$code" 64 "invalid removal contract: $invalid"
    assert_eq "$(wc -l < "$REMOVE_CALLS" | tr -d ' ')" "$calls_before" "invalid removal must not invoke tool"
done

# --- 28. required KDE services and cache failures stop before launching Plasma --------------
KDE_FIXTURE="$TEST_ROOT/kde-services"
mkdir -p "$KDE_FIXTURE/bin"
for service in kactivitymanagerd kded6 kglobalacceld; do
    cat > "$KDE_FIXTURE/bin/$service" <<'FAKE'
#!/bin/sh
label=${0##*/}
if [ "$label" = kglobalacceld ]; then
    : > "$KDE_FIXTURE/unexpected-kglobalacceld"
    exit 0
fi
if [ "${KDE_FAIL_SERVICE:-}" = "$label" ]; then
    echo "original $label startup diagnostic" >&2
    exit "${KDE_FAIL_STATUS:-23}"
fi
exec sleep 300
FAKE
    chmod +x "$KDE_FIXTURE/bin/$service"
done
cat > "$KDE_FIXTURE/bin/kbuildsycoca6" <<'FAKE'
#!/bin/sh
echo 'original KDE cache diagnostic'
exit "${KDE_CACHE_STATUS:-0}"
FAKE
chmod +x "$KDE_FIXTURE/bin/kbuildsycoca6"
cat > "$KDE_FIXTURE/check.sh" <<'CHECK'
#!/bin/sh
SESSION_PIDS=""
LIBEXEC="$KDE_FIXTURE/bin"
trap 'status=$?; trap - EXIT; for pid in $SESSION_PIDS; do kill -TERM "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; done; exit "$status"' EXIT
CHECK
sed -n '/^# These installed Plasma components/,/^echo "=== launching plasmashell/{ /^echo "=== launching plasmashell/d; p; }' \
    "$ASSETS/desktop_container_session.sh" >> "$KDE_FIXTURE/check.sh"
printf 'echo plasma-launch-reached\n' >> "$KDE_FIXTURE/check.sh"
export KDE_FIXTURE
for failure in service-failure service-zero cache-failure missing-service; do
    case "$failure" in
        service-failure) fail_service=kded6; fail_status=23; cache_status=0; expected=23 ;;
        service-zero) fail_service=kded6; fail_status=0; cache_status=0; expected=70 ;;
        cache-failure) fail_service=; fail_status=0; cache_status=41; expected=41 ;;
        missing-service) fail_service=; fail_status=0; cache_status=0; expected=127
            mv "$KDE_FIXTURE/bin/kactivitymanagerd" "$KDE_FIXTURE/bin/kactivitymanagerd.disabled" ;;
    esac
    set +e
    KDE_FAIL_SERVICE="$fail_service" KDE_FAIL_STATUS="$fail_status" KDE_CACHE_STATUS="$cache_status" \
        PATH="$KDE_FIXTURE/bin:$PATH" sh "$KDE_FIXTURE/check.sh" > "$KDE_FIXTURE/$failure" 2>&1
    code=$?
    set -e
    assert_eq "$code" "$expected" "required KDE failure: $failure"
    grep -q plasma-launch-reached "$KDE_FIXTURE/$failure" && fail "KDE failure still launched Plasma: $failure"
    [ "$failure" != missing-service ] || mv "$KDE_FIXTURE/bin/kactivitymanagerd.disabled" "$KDE_FIXTURE/bin/kactivitymanagerd"
done
grep -q 'original kded6 startup diagnostic' "$KDE_FIXTURE/service-failure" || fail "KDE service raw error was hidden"
grep -q 'original KDE cache diagnostic' "$KDE_FIXTURE/cache-failure" || fail "KDE cache raw error was hidden"
PATH="$KDE_FIXTURE/bin:$PATH" sh "$KDE_FIXTURE/check.sh" > "$KDE_FIXTURE/success" 2>&1 \
    || fail "live required KDE services and a successful cache did not launch Plasma"
grep -q plasma-launch-reached "$KDE_FIXTURE/success" || fail "ready required KDE services did not reach Plasma"
[ ! -e "$KDE_FIXTURE/unexpected-kglobalacceld" ] || fail "Wayland policy launched the standalone kglobalacceld no-op"
grep -q 'global shortcuts are unavailable' "$KDE_FIXTURE/success" || fail "Wayland policy hid unavailable cross-bus shortcuts"

printf 'desktop scripts contract tests passed\n'
