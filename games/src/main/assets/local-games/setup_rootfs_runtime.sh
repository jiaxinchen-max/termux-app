#!/data/data/com.termux/files/usr/bin/sh

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: setup_rootfs_runtime.sh <setup-spec>}

TASK_ID=
PACKAGE_NAME=
VERSION=
RECIPE_SHA256=
CONTAINER_ID=
CONTAINER_NAME=
BUILD_CONTEXT=
RECIPE_DIRECTORY=
COMPONENTS=
EVENTS_PATH=
LOG_PATH=
BASE_ONLY=false
WINE_PREFIX_DIRECTORY=
HOME_DIRECTORY=
WINE_PACKAGE=
PREFIX_WARMUP_SCRIPT=
COMPLETED=false
FAILURE_CODE=setup_script_failed
WARMUP_DISPLAY_PID=

on_exit() {
    status=$?
    [ -z "$WARMUP_DISPLAY_PID" ] || kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true
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
    # The per-task build context holds a full copy of the source .debs (~hundreds of MB) and is
    # only needed while the guest setup runs. Drop it on every exit (success or failure) so
    # staging/ does not accumulate one dead copy per setup and fill the data partition.
    [ -n "$BUILD_CONTEXT" ] && rm -rf "$BUILD_CONTEXT" 2>/dev/null || true
}
trap on_exit EXIT

fail() {
    FAILURE_CODE=$1
    exit "${2:-1}"
}

while IFS='=' read -r key value; do
    case "$key" in
        schemaVersion) [ "$value" = 3 ] || fail unsupported_setup_spec 64 ;;
        taskId) TASK_ID=$value ;;
        packageName) PACKAGE_NAME=$value ;;
        version) VERSION=$value ;;
        recipeSha256) RECIPE_SHA256=$value ;;
        containerId) CONTAINER_ID=$value ;;
        containerName) CONTAINER_NAME=$value ;;
        buildContext) BUILD_CONTEXT=$value ;;
        recipeDirectory) RECIPE_DIRECTORY=$value ;;
        component) COMPONENTS="$COMPONENTS$value
" ;;
        eventsPath) EVENTS_PATH=$value ;;
        logPath) LOG_PATH=$value ;;
        baseOnly) BASE_ONLY=$value ;;
        winePrefixDirectory) WINE_PREFIX_DIRECTORY=$value ;;
        homeDirectory) HOME_DIRECTORY=$value ;;
        winePackage) WINE_PACKAGE=$value ;;
        prefixWarmupScript) PREFIX_WARMUP_SCRIPT=$value ;;
        '') ;;
        *) fail invalid_setup_spec 64 ;;
    esac
done < "$SPEC_PATH"
case "$BASE_ONLY" in true|false) ;; *) fail invalid_setup_spec 64 ;; esac
# Required unconditionally, even for BASE_ONLY: that path also builds the shared template Wine
# prefix (see below), sourcing the same rootfs_prefix_warmup.sh functions a per-container warmup
# uses.
[ -n "$PREFIX_WARMUP_SCRIPT" ] || fail invalid_setup_spec 64
if [ "$BASE_ONLY" = false ]; then
    case "$WINE_PACKAGE" in ''|*[!A-Za-z0-9._-]*) fail invalid_setup_wine_package 64 ;; esac
    [ -n "$WINE_PREFIX_DIRECTORY" ] || fail invalid_setup_spec 64
    [ -n "$HOME_DIRECTORY" ] || fail invalid_setup_spec 64
fi

for value in "$TASK_ID" "$PACKAGE_NAME" "$CONTAINER_ID" "$CONTAINER_NAME"; do
    case "$value" in ''|*[!A-Za-z0-9._-]*) fail invalid_setup_identifier 64 ;; esac
done
case "$VERSION" in ''|*[!0-9]*|0*) fail invalid_setup_version 64 ;; esac
case "$RECIPE_SHA256" in *[!0-9a-f]*|'') fail invalid_setup_recipe_digest 64 ;; esac
[ "${#RECIPE_SHA256}" -eq 64 ] || fail invalid_setup_recipe_digest 64

PRIVATE_ROOT=${SPEC_PATH%/runtime/setup/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || \
    fail invalid_setup_spec_path 64
case "$SPEC_PATH" in "$PRIVATE_ROOT"/runtime/setup/specs/*.setupspec) ;; *)
    fail invalid_setup_spec_path 64 ;;
esac
# The app's own files-dir, in the exact string form Java's File.getCanonicalPath() produces
# (SPEC_PATH itself came from that, via RootfsSetupForegroundService -> spec.getCanonicalPath()).
# Used only for the proot-distro container/template storage paths below -- NOT for the Termux
# installation prefix ($PREFIX below), which is a separate, always-stable convention. Modern
# Android resolves a Context's files dir under /data/user/<id>/..., while a bare hardcoded
# "/data/data/..." guess (the old $PREFIX-based derivation this replaces) can land on a
# different-but-equivalent path in another process's mount view. Both paths work fine for actual
# file I/O (the OS maps them to the same storage), but a text-equality marker check comparing
# "was this already warmed" written from one and read from the other would never match -- which
# is exactly why the RootFS prefix warmup done at import/save time was silently redone, in full,
# every time at launch. Keying every container/template path off this single Java-derived string
# instead, consistently, is what makes the marker comparison agree across both call sites.
TERMUX_FILES_ROOT=${PRIVATE_ROOT%/games}
[ -n "$TERMUX_FILES_ROOT" ] && [ "$TERMUX_FILES_ROOT" != "$PRIVATE_ROOT" ] || \
    fail invalid_setup_spec_path 64
for path in "$SPEC_PATH" "$BUILD_CONTEXT" "$RECIPE_DIRECTORY" \
    "$EVENTS_PATH" "$LOG_PATH"; do
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail setup_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_setup_private_path 64 ;; esac
done
# Each base component is a "<componentId>|<canonical dir>" line; validate id and private path.
[ -n "$COMPONENTS" ] || fail invalid_setup_spec 64
OLD_IFS=$IFS
IFS='
'
for entry in $COMPONENTS; do
    [ -n "$entry" ] || continue
    cid=${entry%%|*}
    cdir=${entry#*|}
    case "$cid" in ''|*[!A-Za-z0-9._-]*) fail invalid_setup_identifier 64 ;; esac
    [ "$cdir" != "$entry" ] && [ -n "$cdir" ] || fail invalid_setup_spec 64
    case "$cdir" in "$PRIVATE_ROOT"/*) ;; *) fail setup_path_outside_private_storage 64 ;; esac
    case "$cdir" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_setup_private_path 64 ;; esac
done
IFS=$OLD_IFS
# Empty for BASE_ONLY tasks (no per-game container/prefix involved); validated like the above
# whenever actually supplied.
for path in "$WINE_PREFIX_DIRECTORY" "$HOME_DIRECTORY" "$PREFIX_WARMUP_SCRIPT"; do
    [ -n "$path" ] || continue
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail setup_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_setup_private_path 64 ;; esac
done
events_directory=${EVENTS_PATH%/*}
logs_directory=${LOG_PATH%/*}
[ "$events_directory" != "$EVENTS_PATH" ] && [ "$logs_directory" != "$LOG_PATH" ] || \
    fail invalid_setup_output_path 64
mkdir -p "$events_directory" "$logs_directory" || fail setup_output_directory_failed 70
: >> "$LOG_PATH"

progress() {
    printf '%s\n' "$1" | tee -a "$LOG_PATH"
}

# Keep the durable log for task reconciliation, while forwarding command output to
# the PTY when setup was started from the Games component screen.
run_logged() {
    if [ -t 1 ]; then
        status_path="$LOG_PATH.command-status.$$"
        rm -f "$status_path"
        (
            set +e
            "$@"
            command_status=$?
            printf '%s\n' "$command_status" > "$status_path"
        ) 2>&1 | tee -a "$LOG_PATH"
        command_status=$(cat "$status_path" 2>/dev/null || printf '1')
        rm -f "$status_path"
        return "$command_status"
    fi
    "$@" >> "$LOG_PATH" 2>&1
}

# Like run_logged, but for a long, possibly-stalling step (wineboot) that must stay visible in the
# live setup console without an arbitrary wall-clock ceiling. Two differences from run_logged:
#  1. Visibility without a tee pipe: the command writes to "$LOG_PATH" and a background `tail -f`
#     forwards new lines to the PTY. Because the command is backgrounded directly (no pipe), its
#     own "$!" is proot's PID -- and proot runs with --kill-on-exit (see
#     rootfs_prefix_warmup.sh's run_rootfs_maintenance_command), so killing that one PID tears
#     down the whole proot->wine process tree. (A `... | tee &` pipeline's $! is tee, not proot,
#     so killing it would leave wine running -- hence the tail-forward instead.)
#  2. Silence watchdog instead of a fixed timeout: the command may run as long as "$LOG_PATH"
#     keeps growing, and is aborted only after PREFIX_SILENCE_TIMEOUT seconds of *zero* growth -- a
#     genuine deadlock (e.g. the old Mono-installer hang), never a healthy-but-slow run, which
#     streams output continuously. Returns the command's real exit code, or 124 (mimicking
#     `timeout`) on watchdog fire, so the caller's existing "-ne 124 -> timeout" check is reused.
run_logged_watchdog() {
    watchdog_silence=${PREFIX_SILENCE_TIMEOUT:-300}
    watchdog_tail_pid=
    if [ -t 1 ]; then
        tail -n 0 -f "$LOG_PATH" 2>/dev/null &
        watchdog_tail_pid=$!
    fi
    "$@" >> "$LOG_PATH" 2>&1 &
    watchdog_cmd_pid=$!
    watchdog_last_size=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
    watchdog_last_change=$(date +%s)
    watchdog_fired=0
    # Quiet set -x around the poll loop itself -- a per-5s trace of the watchdog machinery is noise
    # in the live console, not progress (same reasoning as rootfs_prefix_warmup.sh's poll loop).
    set +x
    while kill -0 "$watchdog_cmd_pid" 2>/dev/null; do
        sleep 5
        watchdog_now=$(date +%s)
        watchdog_size=$(wc -c < "$LOG_PATH" 2>/dev/null || printf '0')
        if [ "$watchdog_size" != "$watchdog_last_size" ]; then
            watchdog_last_size=$watchdog_size
            watchdog_last_change=$watchdog_now
        elif [ $((watchdog_now - watchdog_last_change)) -ge "$watchdog_silence" ]; then
            # SIGKILL, not SIGTERM: proot is a ptrace tracer that forwards SIGTERM to the guest
            # init (Windows wineboot ignores it) and does NOT itself exit, so --kill-on-exit never
            # fires -- verified on-device that `kill` leaves the whole wine tree running. SIGKILL
            # can't be forwarded/caught, so proot dies at the kernel and ptrace EXITKILL reaps every
            # tracee (including the daemonised, PPID=1 wineserver). watchdog_cmd_pid is proot's real
            # PID because run_rootfs_maintenance_command execs proot (see its header).
            kill -9 "$watchdog_cmd_pid" 2>/dev/null || true
            watchdog_fired=1
            break
        fi
    done
    set -x
    watchdog_status=0
    wait "$watchdog_cmd_pid" 2>/dev/null || watchdog_status=$?
    [ -z "$watchdog_tail_pid" ] || { kill "$watchdog_tail_pid" 2>/dev/null || true; wait "$watchdog_tail_pid" 2>/dev/null || true; }
    [ "$watchdog_fired" = 0 ] || return 124
    return "$watchdog_status"
}

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
PROOT_DISTRO="$PREFIX/bin/proot-distro"
CONTAINERS_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/containers"
# Winlator-style: one shared, always-current RootFS every container's proot session mounts with
# `-r` (see start_rootfs_game.sh) -- never a per-containerId copy. Beside containers/ (never
# under it, and not itself a proot-distro-managed container) so it is never mistaken for one by
# the reset/backup/asset-scanning code paths that key off containerIds, and so the plain
# tar-extraction below (not `proot-distro remove`/`install`) is the right tool to replace it.
SHARED_ROOTFS_CONTAINER_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY/rootfs"
BASE_IMAGE=debian:trixie-20260824
# Every container built from the same recipe (same box64/Wine source component, same
# setup-container.sh/games-runtime.properties, same version of this script) would otherwise
# redo an identical multi-hundred-MB download+unpack+apt-install. Build that once into a fixed,
# reserved "tmpl-build" scratch container and archive it instead of hardlink-cloning it into every
# real container. "tmpl-build" is never a real containerId (those are generated, not
# user-chosen), so it never collides with, and is invisible to, the reset/backup/asset-scanning
# code paths that key off real containerIds. A single fixed name (not one per recipe hash) is
# safe because RuntimeInstallationGate.requireRootfsSlot() guarantees only one instance of this
# script runs at a time system-wide, and the scratch directory is deleted at the end of every
# build (see below) so nothing lingers between builds anyway.
BUILD_CONTAINER_NAME=tmpl-build
BUILD_DIRECTORY="$CONTAINERS_DIRECTORY/$BUILD_CONTAINER_NAME"
BUILD_ROOTFS="$BUILD_DIRECTORY/rootfs"
# The built template is archived once, under one fixed name (no recipe hash), and every real
# container is produced by extracting that archive -- independent real files, no hardlinks. Some
# Android data partitions reject hardlinks outright (link() returns EPERM even for a self-owned
# file), which made the old `cp -al` clone fail for every game; tar extraction is also exactly how
# proot-distro rootfs images normally ship. The cache lives beside containers/ (never under it) so
# it is not mistaken for a container. A fixed name means there is always at most one archive on
# disk -- this script's own BASE_ONLY path deletes it (and its sidecar) directly before
# rebuilding, with no hash to recompute, so "rebuild RootFS" can never target a stale/wrong path
# (the bug a recipe-hash-keyed name caused).
TEMPLATE_CACHE_DIR="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-template-cache"
TEMPLATE_RECIPE="$TEMPLATE_CACHE_DIR/games-rootfs-base.recipe"
if command -v zstd >/dev/null 2>&1; then
    TEMPLATE_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base.tar.zst"
    TEMPLATE_COMPRESSOR=zstd
    TEMPLATE_PREFIX_EXT=zst
else
    TEMPLATE_ARCHIVE="$TEMPLATE_CACHE_DIR/games-rootfs-base.tar.gz"
    TEMPLATE_COMPRESSOR=gzip
    TEMPLATE_PREFIX_EXT=gz
fi
# One prefix template is pre-booted per translator (see the BASE_ONLY loop below), not a single
# fixed-name archive -- TEMPLATE_PREFIX_ARCHIVE itself is resolved per-container, per-translator,
# via rootfs_prefix_warmup.sh's resolve_template_prefix_archive() further down.
TEMPLATE_PREFIX_ARCHIVE=
# Sourced early (not just before the per-container warmup call further down) because BASE_ONLY
# also builds the shared template Wine prefix below, using these same functions.
warmup_fail() { fail "$1" 70; }
. "$PREFIX_WARMUP_SCRIPT"
supports_container_setup() {
    [ -x "$PROOT_DISTRO" ] &&
        "$PROOT_DISTRO" install --help 2>&1 | grep -q -- '--name' &&
        "$PROOT_DISTRO" login --help 2>&1 | grep -q -- '--isolated' &&
        "$PROOT_DISTRO" login --help 2>&1 | grep -q -- '--bind'
}
progress '==> [1/4] Checking Termux runtime dependencies'
if [ ! -x "$PREFIX/bin/proot" ] || [ ! -x "$PROOT_DISTRO" ] || \
    ! "$PROOT_DISTRO" --help >/dev/null 2>&1 || [ ! -x "$PREFIX/bin/pulseaudio" ]; then
    [ -x "$PREFIX/bin/pkg" ] || fail termux_pkg_missing 69
    export DEBIAN_FRONTEND=noninteractive
    # PRoot-Distro 5.x imports Python ssl before it can print help. Explicitly
    # request OpenSSL so an older bootstrap prefix is upgraded atomically instead
    # of leaving a runnable proot-distro launcher with a broken _ssl module.
    # AppShell has no stdin/PTY. DEBIAN_FRONTEND alone does not suppress a
    # dpkg conffile decision, so retain the existing file and accept defaults.
    progress '==> Installing or repairing PRoot, PulseAudio, Python and OpenSSL'
    if ! run_logged "$PREFIX/bin/pkg" install -y \
        -o Dpkg::Options::=--force-confdef \
        -o Dpkg::Options::=--force-confold \
        openssl python proot proot-distro pulseaudio; then
        fail proot_distro_package_install_failed 69
    fi
fi
[ -x "$PROOT_DISTRO" ] || fail proot_distro_missing 69
[ -x "$PREFIX/bin/proot" ] || fail proot_missing 69
supports_container_setup || {
    fail proot_distro_install_login_unsupported 69
}
[ -f "$RECIPE_DIRECTORY/setup-container.sh" ] || fail rootfs_recipe_script_missing 66
[ -f "$RECIPE_DIRECTORY/games-runtime.properties" ] || fail rootfs_recipe_manifest_missing 66

progress '==> [2/4] Preparing runtime build context'
rm -rf "$BUILD_CONTEXT"
mkdir -p "$BUILD_CONTEXT/components"
cp "$RECIPE_DIRECTORY/setup-container.sh" "$BUILD_CONTEXT/setup-container.sh"
cp "$RECIPE_DIRECTORY/games-runtime.properties" "$BUILD_CONTEXT/games-runtime.properties"
# Stage every base component under components/<id>/ for the guest to install by content.
OLD_IFS=$IFS
IFS='
'
for entry in $COMPONENTS; do
    [ -n "$entry" ] || continue
    cid=${entry%%|*}
    cdir=${entry#*|}
    find "$cdir" -type f | grep -q . || fail rootfs_source_packages_missing 66
    mkdir -p "$BUILD_CONTEXT/components/$cid"
    cp -al "$cdir"/. "$BUILD_CONTEXT/components/$cid"/ 2>/dev/null || \
        cp -a "$cdir"/. "$BUILD_CONTEXT/components/$cid"/
done
IFS=$OLD_IFS

printf '{"schemaVersion":1,"taskId":"%s","state":"BUILDING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

# $1 = rootfs path to check (either a real container's or the shared template's).
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
# $1 = container directory, $2 = its rootfs path.
base_container_ready() {
    directory=$1
    root=$2
    [ -x "$root/usr/bin/env" ] && [ -f "$directory/manifest.json" ]
}
# Builds a fresh proot-distro container from the base image and runs the games
# setup script inside it. $1 = container name, $2 = its directory, $3 = its rootfs.
build_runtime_into() {
    name=$1
    directory=$2
    root=$3
    if ! base_container_ready "$directory" "$root"; then
        progress '==> [3/4] Downloading and unpacking Debian ImageFS'
        if [ -d "$directory" ]; then
            if ! run_logged "$PROOT_DISTRO" remove --quiet "$name"; then
                fail proot_distro_container_remove_failed 70
            fi
        fi
        if ! run_logged "$PROOT_DISTRO" install --architecture aarch64 --name "$name" \
            "$BASE_IMAGE"; then
            fail proot_distro_container_install_failed 70
        fi
    fi
    if ! base_container_ready "$directory" "$root"; then
        fail proot_distro_base_container_invalid 70
    fi
    progress '==> [4/4] Installing game runtime packages in Debian'
    if ! run_logged "$PROOT_DISTRO" login "$name" --isolated \
        --bind "$BUILD_CONTEXT:/run/games-setup" -- \
        /bin/sh /run/games-setup/setup-container.sh; then
        fail rootfs_guest_setup_failed 70
    fi
}
# Archives the already-built template container ($BUILD_DIRECTORY, i.e. manifest.json +
# rootfs/ + shm/ + sysdata/) into $TEMPLATE_ARCHIVE once. Written to a .tmp then atomically
# renamed, so a present archive is always complete. No-op if the archive already exists.
ensure_template_archive() {
    [ -f "$TEMPLATE_ARCHIVE" ] && return 0
    mkdir -p "$TEMPLATE_CACHE_DIR"
    rm -f "$TEMPLATE_ARCHIVE.tmp"
    if ! run_logged tar -C "$BUILD_DIRECTORY" \
        --use-compress-program "$TEMPLATE_COMPRESSOR" -cf "$TEMPLATE_ARCHIVE.tmp" .; then
        rm -f "$TEMPLATE_ARCHIVE.tmp"
        fail rootfs_template_archive_failed 70
    fi
    if ! mv "$TEMPLATE_ARCHIVE.tmp" "$TEMPLATE_ARCHIVE"; then
        rm -f "$TEMPLATE_ARCHIVE.tmp"
        fail rootfs_template_archive_failed 70
    fi
}
# Replaces $SHARED_ROOTFS_CONTAINER_DIRECTORY with a fresh extraction of the shared template
# archive -- independent real files, no hardlinks, so it works on filesystems that reject them.
# Not a registered proot-distro container (it holds the one rootfs every real container's proot
# session mounts directly, see start_rootfs_game.sh), so a plain rm -rf is the right tool here,
# unlike the "proot-distro remove" a real container's own directory would need.
extract_shared_rootfs() {
    rm -rf "$SHARED_ROOTFS_CONTAINER_DIRECTORY"
    mkdir -p "$SHARED_ROOTFS_CONTAINER_DIRECTORY"
    if ! run_logged tar -C "$SHARED_ROOTFS_CONTAINER_DIRECTORY" \
        --use-compress-program "$TEMPLATE_COMPRESSOR" --numeric-owner -xpf "$TEMPLATE_ARCHIVE"; then
        fail rootfs_template_extract_failed 70
    fi
}

# Reclaim stray build dirs / archives left by an older version of this script (hash-named
# tmpl-<sha> dirs, hash-named cache archives) or by an interrupted BASE_ONLY rebuild. No game
# ever reads any of these directly, so it is always safe to drop anything that is not the current
# fixed-name artifact.
if [ "$BASE_ONLY" = true ]; then
    # A rebuild must actually replace the archive, not silently reuse a stale one --
    # ensure_template_archive() below is a no-op when the archive already exists, so force a
    # real rebuild by clearing it first. Harmless (a no-op delete) on a first-ever "Build" too.
    # Every per-translator prefix template (games-rootfs-base-prefix-<translator>.tar.*, see the
    # BASE_ONLY loop below -- one per distinct RUNTIME_TRANSLATOR bucket, not one per literal wine
    # package) is rebuilt in lockstep with the rootfs for the same reason (the known hash-drift
    # failure mode this session already fixed once for the rootfs side).
    rm -f "$TEMPLATE_ARCHIVE" "$TEMPLATE_ARCHIVE.tmp" "$TEMPLATE_RECIPE"
    for stray_prefix in "$TEMPLATE_CACHE_DIR"/games-rootfs-base-prefix-*.tar.*; do
        [ -e "$stray_prefix" ] || continue
        rm -f "$stray_prefix"
    done
fi
if [ -d "$CONTAINERS_DIRECTORY" ]; then
    for stray_container in "$CONTAINERS_DIRECTORY"/tmpl-*; do
        [ -d "$stray_container" ] || continue
        case "$stray_container" in */"$BUILD_CONTAINER_NAME") continue ;; esac
        run_logged rm -rf "$stray_container"
    done
fi
if [ -d "$TEMPLATE_CACHE_DIR" ]; then
    for stray_archive in "$TEMPLATE_CACHE_DIR"/*.tar.*; do
        [ -f "$stray_archive" ] || continue
        case "$stray_archive" in
            "$TEMPLATE_ARCHIVE"|"$TEMPLATE_ARCHIVE.tmp") continue ;;
            # Every per-translator prefix template is a live artifact, not just one fixed name.
            "$TEMPLATE_CACHE_DIR"/games-rootfs-base-prefix-*.tar.*) continue ;;
        esac
        rm -f "$stray_archive"
    done
fi

if [ ! -f "$TEMPLATE_ARCHIVE" ]; then
    if ! runtime_complete "$BUILD_ROOTFS"; then
        build_runtime_into "$BUILD_CONTAINER_NAME" "$BUILD_DIRECTORY" "$BUILD_ROOTFS"
        runtime_complete "$BUILD_ROOTFS" || fail rootfs_template_build_invalid 70
    fi
    progress '==> Archiving the shared runtime template'
    ensure_template_archive
    printf '%s\n' "$RECIPE_SHA256" > "$TEMPLATE_RECIPE.tmp" &&
        mv "$TEMPLATE_RECIPE.tmp" "$TEMPLATE_RECIPE" || fail rootfs_template_archive_failed 70
    rm -rf "$BUILD_DIRECTORY"
    # (Re)archiving and (re)publishing the one live, shared RootFS happen in the same task so the
    # two can never disagree -- every container, old and new alike, mounts whatever this leaves
    # behind (see GameStoragePaths.getSharedRootfsDirectory()). A rebuild therefore takes effect
    # for already-created games immediately; there is no more per-container pinning to go stale.
    progress '==> Publishing the shared RootFS image'
    extract_shared_rootfs
    runtime_complete "$SHARED_ROOTFS" || fail rootfs_shared_image_invalid 70

    # Pre-boot one Wine prefix per *translator* (wineboot -u + CJK FontLink), archived alongside
    # the rootfs, so every subsequent container extracts a ready-made prefix instead of paying the
    # wineboot cost again (see rootfs_prefix_warmup.sh's resolve_template_prefix_archive() /
    # TEMPLATE_PREFIX_ARCHIVE fast path). A Kron4ek box64-wine prefix and a Hangover prefix have
    # incompatible ntdll/kernel32 layouts (confirmed: cloning the wrong one produced `wine: could
    # not load kernel32.dll, status c000007b`) -- but that incompatibility is cross-architecture
    # (Hangover's native ARM64 wine vs box64's translated x86_64 wine), not cross-wine-version: two
    # wine packages that resolve_rootfs_translator() below maps to the SAME RUNTIME_TRANSLATOR
    # (e.g. two box64-wine-* versions) share one compatible prefix, so only the first
    # games-runtime.properties entry for each distinct translator actually gets a template built;
    # later entries mapping to an already-built translator just reuse it. Read the package list
    # from the manifest already staged into the build context, rather than hardcoding it, so
    # adding a wine package to the recipe is enough on its own to pick up (or share) a pre-booted
    # template; no edit needed here.
    runtime_packages=$(sed -n 's/^runtimePackages=//p' "$BUILD_CONTEXT/games-runtime.properties")
    [ -n "$runtime_packages" ] || fail rootfs_runtime_packages_missing 70
    TEMPLATE_PREFIX_BUCKETS_BUILT=
    # One termux-x11 session for the whole loop below, started once and killed once, never
    # restarted per translator: restarting it between iterations (even on a fresh display number)
    # left the second wineboot connected to a half-torn-down X session that never finished booting
    # (`boot event wait timed out`, looping until the wineboot timeout killed it) -- confirmed by
    # reproducing the SAME failure with a distinct display number per iteration (ruling out a
    # stale-socket/display-number collision) and then reproducing a clean, progressing wineboot
    # with a single long-lived termux-x11 session instead. A real container's own warmup (further
    # below) never hits this, since it only ever starts termux-x11 once in its script process.
    command -v termux-x11 >/dev/null 2>&1 || fail rootfs_prefix_warmup_display_missing 70
    MAINTENANCE_DISPLAY=:9
    termux-x11 "$MAINTENANCE_DISPLAY" >> "$LOG_PATH" 2>&1 &
    WARMUP_DISPLAY_PID=$!
    OLD_IFS=$IFS
    IFS=','
    for template_wine_package in $runtime_packages; do
        IFS=$OLD_IFS
        [ -n "$template_wine_package" ] || continue
        WINE_PACKAGE=$template_wine_package
        ROOTFS_CANONICAL=$(realpath "$SHARED_ROOTFS") || fail rootfs_unreadable 70
        resolve_rootfs_translator
        case " $TEMPLATE_PREFIX_BUCKETS_BUILT " in
            *" $RUNTIME_TRANSLATOR "*)
                progress "==> Reusing the $RUNTIME_TRANSLATOR Wine prefix template for $template_wine_package"
                IFS=','
                continue
                ;;
        esac
        progress "==> Pre-warming the $RUNTIME_TRANSLATOR Wine prefix template (via $template_wine_package)"
        TEMPLATE_PREFIX_ARCHIVE_DESTINATION="$TEMPLATE_CACHE_DIR/games-rootfs-base-prefix-$RUNTIME_TRANSLATOR.tar.$TEMPLATE_PREFIX_EXT"
        TEMPLATE_PREFIX_BUILD_DIRECTORY="$TEMPLATE_CACHE_DIR/prefix-build"
        TEMPLATE_HOME_BUILD_DIRECTORY="$TEMPLATE_CACHE_DIR/home-build"
        rm -rf "$TEMPLATE_PREFIX_BUILD_DIRECTORY" "$TEMPLATE_HOME_BUILD_DIRECTORY"
        mkdir -p "$TEMPLATE_PREFIX_BUILD_DIRECTORY" "$TEMPLATE_HOME_BUILD_DIRECTORY"
        PREFIX_PATH="$TEMPLATE_PREFIX_BUILD_DIRECTORY"
        HOME_PATH="$TEMPLATE_HOME_BUILD_DIRECTORY"
        RUNTIME_ROOT_PATH=template
        # Empty, not $TEMPLATE_PREFIX_ARCHIVE_DESTINATION: this build step is what PRODUCES that
        # archive, so warmup_rootfs_prefix must do the real wineboot run here, never the clone path.
        TEMPLATE_PREFIX_ARCHIVE=
        CANCEL_PATH=
        TERMUX_FILES_DIR="$TERMUX_FILES_ROOT"
        PROOT_BIN="$PREFIX/bin/proot"
        warmup_rootfs_prefix
        mkdir -p "$TEMPLATE_CACHE_DIR"
        rm -f "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION.tmp"
        if ! run_logged tar -C "$TEMPLATE_PREFIX_BUILD_DIRECTORY" --use-compress-program "$TEMPLATE_COMPRESSOR" \
            -cf "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION.tmp" .; then
            rm -f "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION.tmp"
            fail rootfs_prefix_template_archive_failed 70
        fi
        if ! mv "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION.tmp" "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION"; then
            rm -f "$TEMPLATE_PREFIX_ARCHIVE_DESTINATION.tmp"
            fail rootfs_prefix_template_archive_failed 70
        fi
        rm -rf "$TEMPLATE_PREFIX_BUILD_DIRECTORY" "$TEMPLATE_HOME_BUILD_DIRECTORY"
        TEMPLATE_PREFIX_BUCKETS_BUILT="$TEMPLATE_PREFIX_BUCKETS_BUILT $RUNTIME_TRANSLATOR"
        IFS=','
    done
    IFS=$OLD_IFS
    kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true
    wait "$WARMUP_DISPLAY_PID" 2>/dev/null || true
    WARMUP_DISPLAY_PID=
fi

if [ "$BASE_ONLY" = true ]; then
    printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
    progress '==> Base runtime build completed'
    COMPLETED=true
    exit 0
fi

# Should be unreachable: GameImportReadinessGate/RuntimeEnvironmentStatus.rootfsState() already
# gate game creation on the base archive existing, and the block above republishes the shared
# rootfs every time that archive is (re)built -- the two can only disagree if something deleted
# games-shared-rootfs/ directly without going through this script.
runtime_complete "$SHARED_ROOTFS" || fail rootfs_shared_image_missing 70

# Warms this container's Wine prefix (locale, CJK fonts, `wineboot -u`) right now, at
# import/config-save time, instead of leaving it for the user's first Launch tap -- see
# rootfs_prefix_warmup.sh, shared with start_rootfs_game.sh so the same marker files this writes
# are recognized there and skipped (near-instant) on every subsequent launch. A confirmed design
# choice: unlike RuntimeWarmup's own "never throws" wrapper, a warmup failure here fails this
# whole setup task (surfaced as FAILED/retry in the Components tab) rather than being silently
# deferred to launch time.
progress '==> Warming the Wine prefix for this container (locale, CJK fonts, wineboot)'
ROOTFS_CANONICAL=$(realpath "$SHARED_ROOTFS") || fail rootfs_unreadable 70
PREFIX_PATH="$WINE_PREFIX_DIRECTORY"
HOME_PATH="$HOME_DIRECTORY"
RUNTIME_ROOT_PATH="$ROOTFS_CANONICAL"
CANCEL_PATH=
TERMUX_FILES_DIR="$TERMUX_FILES_ROOT"
PROOT_BIN="$PREFIX/bin/proot"
mkdir -p "$PREFIX_PATH" "$HOME_PATH"
command -v termux-x11 >/dev/null 2>&1 || fail rootfs_prefix_warmup_display_missing 70
# Runs on a dedicated display number, never the shared ":0" a real game session on another
# container might concurrently be using -- RuntimeInstallationGate only serializes setup/reset
# tasks against each other, not against an already-running game.
MAINTENANCE_DISPLAY=:9
termux-x11 "$MAINTENANCE_DISPLAY" >> "$LOG_PATH" 2>&1 &
WARMUP_DISPLAY_PID=$!
resolve_rootfs_translator
resolve_template_prefix_archive
warmup_rootfs_prefix
kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true
WARMUP_DISPLAY_PID=

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
progress '==> Runtime installation completed'
COMPLETED=true
