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
GAMES_RUNTIME_TRANSLATOR=
GAMES_CUSTOM_BOX64_ID=
COMPLETED=false
IMAGE_PID=
PUBLISHING=false
NEW_PUBLISHED=false
OLD_RECIPE_PRESENT=false
OLD_RECIPE_VALUE=
FAILURE_CODE=setup_script_failed
WARMUP_DISPLAY_PID=
SETUP_SCHEMA=
CONTROL_PATHS_VALIDATED=false
SEEN_SETUP_FIELDS=' '
SETUP_COMPONENT_COUNT=
SETUP_COMPONENT_INDEX=0
SETUP_PENDING_COMPONENT=

on_exit() {
    status=$?
    if [ -n "$IMAGE_PID" ]; then
        kill -TERM "$IMAGE_PID" 2>/dev/null || true
        wait "$IMAGE_PID" 2>/dev/null || true
    fi
    if [ "$PUBLISHING" = true ] && [ "$status" -ne 0 ]; then
        if [ "$OLD_RECIPE_PRESENT" = true ]; then
            printf '%s\n' "$OLD_RECIPE_VALUE" > "$TEMPLATE_RECIPE.rollback" &&
                mv "$TEMPLATE_RECIPE.rollback" "$TEMPLATE_RECIPE" || true
        else
            rm -f "$TEMPLATE_RECIPE"
        fi
        if [ "$NEW_PUBLISHED" = true ] && [ -e "$SHARED_ROOTFS_CONTAINER_DIRECTORY" ] && [ ! -e "$BUILD_DIRECTORY" ]; then
            mv "$SHARED_ROOTFS_CONTAINER_DIRECTORY" "$BUILD_DIRECTORY" || true
        fi
        if [ -e "$PREVIOUS_ROOTFS" ] && [ ! -e "$SHARED_ROOTFS_CONTAINER_DIRECTORY" ]; then
            mv "$PREVIOUS_ROOTFS" "$SHARED_ROOTFS_CONTAINER_DIRECTORY" || true
        fi
    fi
    [ -z "${PROOT_PID:-}" ] || kill "$PROOT_PID" 2>/dev/null || true
    [ -z "$WARMUP_DISPLAY_PID" ] || kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true
    if [ "$CONTROL_PATHS_VALIDATED" = true ] && [ "$status" -ne 0 ] && [ "$COMPLETED" = false ] && [ -n "$EVENTS_PATH" ]; then
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
    [ "$CONTROL_PATHS_VALIDATED" = true ] && [ -n "$BUILD_CONTEXT" ] && rm -rf "$BUILD_CONTEXT" 2>/dev/null || true

}
trap on_exit EXIT
trap 'fail rootfs_setup_interrupted 130' INT TERM

fail() {
    FAILURE_CODE=$1
    exit "${2:-1}"
}

while IFS= read -r line || [ -n "$line" ]; do
    key=${line%%=*}
    value=${line#*=}
    [ "$key" != "$line" ] || fail invalid_setup_spec 64
    if [ "$key" = schemaVersion ]; then
        [ -z "$SETUP_SCHEMA" ] || fail invalid_setup_spec 64
        case "$value" in 3|4) SETUP_SCHEMA=$value ;; *) fail unsupported_setup_spec 64 ;; esac
        if [ "$SETUP_SCHEMA" = 4 ]; then
            PRIVATE_ROOT=${SPEC_PATH%/runtime/setup/specs/*}
            [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || fail invalid_setup_spec_path 64
            TERMUX_FILES_DIR=${PRIVATE_ROOT%/front}
            [ "$TERMUX_FILES_DIR" != "$PRIVATE_ROOT" ] || fail invalid_setup_spec_path 64
            [ "$(realpath "$SPEC_PATH")" = "$SPEC_PATH" ] || fail invalid_setup_spec_path 64
            [ -f "$PRIVATE_ROOT/runtime/proot_component_bindings.sh" ] || fail proot_binding_helper_missing 64
            . "$PRIVATE_ROOT/runtime/proot_component_bindings.sh"
        fi
        continue
    fi
    [ -n "$SETUP_SCHEMA" ] || fail unsupported_setup_spec 64
    if [ "$SETUP_SCHEMA" = 4 ]; then
        case "$key" in
            bindingCount|binding.*)
                proot_binding_field "$key" "$value" || fail invalid_proot_bindings 64
                continue ;;
            componentCount)
                [ -z "$SETUP_COMPONENT_COUNT" ] || fail invalid_setup_spec 64
                case "$value" in ''|0*|*[!0-9]*) fail invalid_setup_spec 64 ;; esac
                [ "${#value}" -le 2 ] && [ "$value" -le 16 ] || fail invalid_setup_spec 64
                SETUP_COMPONENT_COUNT=$value
                continue ;;
            component.*)
                [ -n "$SETUP_COMPONENT_COUNT" ] && [ "$SETUP_COMPONENT_INDEX" -lt "$SETUP_COMPONENT_COUNT" ] || fail invalid_setup_spec 64
                value=$(proot_binding_decode "$value") || fail invalid_setup_spec_base64 64
                case "$key" in
                    "component.$SETUP_COMPONENT_INDEX.id")
                        [ -z "$SETUP_PENDING_COMPONENT" ] || fail invalid_setup_spec 64
                        case "$value" in ''|*[!A-Za-z0-9._-]*) fail invalid_setup_identifier 64 ;; esac
                        SETUP_PENDING_COMPONENT=$value ;;
                    "component.$SETUP_COMPONENT_INDEX.directory")
                        [ -n "$SETUP_PENDING_COMPONENT" ] || fail invalid_setup_spec 64
                        COMPONENTS="$COMPONENTS$SETUP_PENDING_COMPONENT|$value
"
                        SETUP_PENDING_COMPONENT=
                        SETUP_COMPONENT_INDEX=$((SETUP_COMPONENT_INDEX + 1)) ;;
                    *) fail invalid_setup_spec 64 ;;
                esac
                continue ;;
            component) fail invalid_setup_spec 64 ;;
        esac
        value=$(proot_binding_decode "$value") || fail invalid_setup_spec_base64 64
    fi
    if [ "$key" != component ]; then
        case "$SEEN_SETUP_FIELDS" in *" $key "*) fail duplicate_setup_spec_field 64 ;; esac
        SEEN_SETUP_FIELDS="$SEEN_SETUP_FIELDS$key "
    fi
    case "$key" in
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
        runtimeTranslator) GAMES_RUNTIME_TRANSLATOR=$value ;;
        box64ComponentId) GAMES_CUSTOM_BOX64_ID=$value ;;
        '') ;;
        *) fail invalid_setup_spec 64 ;;
    esac
done < "$SPEC_PATH"
case "$SETUP_SCHEMA" in
    3) ;;
    4)
        proot_bindings_complete || fail invalid_proot_bindings 64
        [ -n "$SETUP_COMPONENT_COUNT" ] && [ "$SETUP_COMPONENT_COUNT" -eq "$SETUP_COMPONENT_INDEX" ] &&
            [ -z "$SETUP_PENDING_COMPONENT" ] || fail invalid_setup_spec 64 ;;
    *) fail unsupported_setup_spec 64 ;;
esac
[ "$SETUP_SCHEMA" = 4 ] && [ "$VERSION" = 12 ] || fail unsupported_setup_spec 64
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
TERMUX_FILES_ROOT=${PRIVATE_ROOT%/front}
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
CONTROL_PATHS_VALIDATED=true

# Co-located with $PREFIX_WARMUP_SCRIPT by LaunchScriptInstaller (same runtimeDirectory) --
# derive its path from PREFIX_WARMUP_SCRIPT's own directory rather than adding a new spec field,
# same convention start_rootfs_game.sh uses to find rootfs_prefix_warmup.sh. Defines progress(),
# run_logged(), run_logged_watchdog() -- shared with backup_restore_rootfs.sh so both scripts'
# output follows the same "visible in the live console, silence-watchdog not a fixed timeout"
# convention instead of each growing its own copy.
COMMON_SCRIPT="$(dirname "$PREFIX_WARMUP_SCRIPT")/rootfs_script_common.sh"
[ -f "$COMMON_SCRIPT" ] || fail invalid_setup_spec 64
. "$COMMON_SCRIPT"

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
export PATH="$PREFIX/bin:/system/bin:$PATH"
SHARED_ROOTFS_CONTAINER_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-shared-rootfs"
SHARED_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY/rootfs"
BUILD_DIRECTORY="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/containers/tmpl-build"
BUILD_ROOTFS="$BUILD_DIRECTORY/rootfs"
TEMPLATE_CACHE_DIR="$TERMUX_FILES_ROOT/usr/var/lib/proot-distro/games-template-cache"
TEMPLATE_RECIPE="$TEMPLATE_CACHE_DIR/games-rootfs-base.recipe"
PREVIOUS_ROOTFS="$SHARED_ROOTFS_CONTAINER_DIRECTORY.previous"
IMAGE_HELPER="$RECIPE_DIRECTORY/prebuilt_rootfs.py"
IMAGE_DESCRIPTOR="$RECIPE_DIRECTORY/image.json"
IMAGE_OWNER="$BUILD_DIRECTORY/.idap-image-owner"
IMAGE_COMPONENT=rootfs_debian13_hangover_pdb2_minimal
warmup_fail() { fail "$1" 70; }
. "$PREFIX_WARMUP_SCRIPT"

progress '==> Checking Termux host dependencies'
host_runtime_ready() {
    [ -x "$PREFIX/bin/python" ] && [ -x "$PREFIX/bin/proot" ] &&
        [ -x "$PREFIX/bin/zstd" ] && [ -f "$PREFIX/lib/libexpat.so.1" ] &&
        "$PREFIX/bin/dbus-daemon" --version >/dev/null 2>&1 &&
        "$PREFIX/bin/pulseaudio" --version >/dev/null 2>&1
}
if ! host_runtime_ready; then
    [ -x "$PREFIX/bin/pkg" ] || fail termux_pkg_missing 69
    export DEBIAN_FRONTEND=noninteractive
    run_logged "$PREFIX/bin/pkg" install -y -o Dpkg::Options::=--force-confdef \
        -o Dpkg::Options::=--force-confold openssl python proot zstd pulseaudio dbus libexpat ||
        fail termux_runtime_dependencies_invalid 69
fi
host_runtime_ready || fail termux_runtime_dependencies_invalid 69
[ -f "$IMAGE_HELPER" ] && [ -f "$IMAGE_DESCRIPTOR" ] || fail rootfs_image_assets_missing 66
[ ! -L "$BUILD_DIRECTORY" ] && [ ! -L "$SHARED_ROOTFS_CONTAINER_DIRECTORY" ] || fail rootfs_image_path_invalid 70
mkdir -p "$TEMPLATE_CACHE_DIR" "${BUILD_DIRECTORY%/*}"

run_image_restore() {
    "$@" >> "$LOG_PATH" 2>&1 &
    IMAGE_PID=$!
    image_status=0
    wait "$IMAGE_PID" || image_status=$?
    IMAGE_PID=
    return "$image_status"
}
image_complete() {
    [ -x "$1/usr/bin/wine" ] && [ -x "$1/usr/bin/env" ] &&
        "$PREFIX/bin/python" "$IMAGE_HELPER" verify --root "$1" \
            --descriptor "$IMAGE_DESCRIPTOR" --recipe "$RECIPE_SHA256" >> "$LOG_PATH" 2>&1
}
verify_rootfs_locales() {
    run_logged "$PREFIX/bin/proot" --kill-on-exit --link2symlink -0 -r "$1" -b /dev -b /proc \
        -w / /usr/bin/env -i PATH=/usr/bin:/bin /bin/sh -eu -c '
        test "$(LC_ALL=zh_CN.UTF-8 locale charmap)" = UTF-8
        test "$(LC_ALL=zh_CN.GBK locale charmap)" = GBK
        fc-match "Noto Sans CJK SC"
    '
}

printf '{"schemaVersion":1,"taskId":"%s","state":"BUILDING"}\n' "$TASK_ID" >> "$EVENTS_PATH"
if [ "$BASE_ONLY" = false ] && { [ -e "$SHARED_ROOTFS_CONTAINER_DIRECTORY" ] || [ -e "$TEMPLATE_RECIPE" ]; }; then
    image_complete "$SHARED_ROOTFS" && [ "$(cat "$TEMPLATE_RECIPE" 2>/dev/null)" = "$RECIPE_SHA256" ] ||
        fail rootfs_rebuild_required 70
fi
if [ "$BASE_ONLY" = true ] || ! image_complete "$SHARED_ROOTFS"; then
    [ ! -e "$PREVIOUS_ROOTFS" ] || fail rootfs_publish_recovery_required 70
    if [ -e "$BUILD_DIRECTORY" ]; then
        [ -f "$IMAGE_OWNER" ] && [ ! -L "$IMAGE_OWNER" ] &&
            [ "$(cat "$IMAGE_OWNER")" = prebuilt-image-v1 ] || fail rootfs_image_scratch_unowned 70
        rm -rf "$BUILD_DIRECTORY" || fail rootfs_image_scratch_cleanup_failed 70
    fi
    mkdir "$BUILD_DIRECTORY"
    printf '%s\n' prebuilt-image-v1 > "$IMAGE_OWNER"
    image_directory=
    OLD_IFS=$IFS
    IFS='
'
    for entry in $COMPONENTS; do
        [ -n "$entry" ] || continue
        [ "${entry%%|*}" = "$IMAGE_COMPONENT" ] && [ -z "$image_directory" ] || fail rootfs_image_component_invalid 66
        image_directory=${entry#*|}
    done
    IFS=$OLD_IFS
    [ -n "$image_directory" ] || fail rootfs_image_component_missing 66
    image_name=$("$PREFIX/bin/python" -c 'import json,sys,re; d=json.load(open(sys.argv[1])); n=d["file"]; assert re.fullmatch(r"[A-Za-z0-9_.-]+\.tar\.zst",n); print(n)' "$IMAGE_DESCRIPTOR") || fail rootfs_image_metadata_invalid 66
    prefix_reserve=0
    if [ "$BASE_ONLY" = false ]; then
        prefix_reserve=$("$PREFIX/bin/python" -c 'import json,sys; n=json.load(open(sys.argv[1]))["expandedBytes"]; assert type(n) is int and 0 < n <= 2*1024**3; print(n+256*1024**2)' "$RECIPE_DIRECTORY/prefix-hangover-pdb2/bundle.json") || fail rootfs_prefix_budget_invalid 66
    fi
    progress '==> Extracting and verifying the preinstalled minimal RootFS'
    if ! run_image_restore "$PREFIX/bin/python" "$IMAGE_HELPER" restore --archive "$image_directory/$image_name" \
        --root "$BUILD_ROOTFS" --descriptor "$IMAGE_DESCRIPTOR" --recipe "$RECIPE_SHA256" --zstd "$PREFIX/bin/zstd" --reserve-bytes "$prefix_reserve"; then
        fail rootfs_image_restore_failed 70
    fi
    [ -f "$RECIPE_DIRECTORY/relocate_rootfs_links.py" ] || fail rootfs_link_helper_missing 66
    run_logged "$PREFIX/bin/python" "$RECIPE_DIRECTORY/relocate_rootfs_links.py" "$BUILD_ROOTFS" --verify ||
        fail rootfs_link_validation_failed 70
    image_complete "$BUILD_ROOTFS" && verify_rootfs_locales "$BUILD_ROOTFS" || fail rootfs_image_verification_failed 70
    progress '==> Publishing the shared RootFS image'
    if [ -f "$TEMPLATE_RECIPE" ]; then
        OLD_RECIPE_VALUE=$(cat "$TEMPLATE_RECIPE")
        OLD_RECIPE_PRESENT=true
    fi
    PUBLISHING=true
    if [ -e "$SHARED_ROOTFS_CONTAINER_DIRECTORY" ]; then
        mv "$SHARED_ROOTFS_CONTAINER_DIRECTORY" "$PREVIOUS_ROOTFS" || fail rootfs_shared_image_publish_failed 70
    fi
    NEW_PUBLISHED=true
    if ! mv "$BUILD_DIRECTORY" "$SHARED_ROOTFS_CONTAINER_DIRECTORY"; then
        [ ! -e "$PREVIOUS_ROOTFS" ] || mv "$PREVIOUS_ROOTFS" "$SHARED_ROOTFS_CONTAINER_DIRECTORY"
        fail rootfs_shared_image_publish_failed 70
    fi
    if ! image_complete "$SHARED_ROOTFS" || ! verify_rootfs_locales "$SHARED_ROOTFS"; then
        mv "$SHARED_ROOTFS_CONTAINER_DIRECTORY" "$BUILD_DIRECTORY" || fail rootfs_publish_recovery_required 70
        [ ! -e "$PREVIOUS_ROOTFS" ] || mv "$PREVIOUS_ROOTFS" "$SHARED_ROOTFS_CONTAINER_DIRECTORY" || fail rootfs_publish_recovery_required 70
        fail rootfs_shared_image_invalid 70
    fi
    if ! { printf '%s\n' "$RECIPE_SHA256" > "$TEMPLATE_RECIPE.tmp" && mv "$TEMPLATE_RECIPE.tmp" "$TEMPLATE_RECIPE"; }; then
        mv "$SHARED_ROOTFS_CONTAINER_DIRECTORY" "$BUILD_DIRECTORY" || fail rootfs_publish_recovery_required 70
        [ ! -e "$PREVIOUS_ROOTFS" ] || mv "$PREVIOUS_ROOTFS" "$SHARED_ROOTFS_CONTAINER_DIRECTORY" || fail rootfs_publish_recovery_required 70
        fail rootfs_recipe_record_failed 70
    fi
    PUBLISHING=false
    rm -rf "$PREVIOUS_ROOTFS"
fi
if [ "$BASE_ONLY" = true ]; then
    printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
    progress '==> Preinstalled minimal runtime ready (no guest package installation)'
    COMPLETED=true
    exit 0
fi
image_complete "$SHARED_ROOTFS" || fail rootfs_shared_image_missing 70

# Prepares this container's Wine prefix right now, at
# import/config-save time, instead of leaving it for the user's first Launch tap -- see
# rootfs_prefix_warmup.sh, shared with start_rootfs_game.sh so the same marker files this writes
# are recognized there and skipped (near-instant) on every subsequent launch. A confirmed design
# choice: unlike RuntimeWarmup's own "never throws" wrapper, a warmup failure here fails this
# whole setup task (surfaced as FAILED/retry in the Components tab) rather than being silently
# deferred to launch time.
progress '==> Preparing the Wine prefix for this container'
ROOTFS_CANONICAL=$(realpath "$SHARED_ROOTFS") || fail rootfs_unreadable 70
while IFS= read -r setup_bind; do
    [ -n "$setup_bind" ] || continue
    setup_target=${setup_bind##*:}
    setup_host_target="$ROOTFS_CANONICAL$setup_target"
    setup_resolved=$(realpath -m "$setup_host_target") || fail rootfs_binding_target_invalid 70
    case "$setup_resolved" in "$ROOTFS_CANONICAL"/opt/*) ;; *) fail rootfs_binding_target_invalid 70 ;; esac
    mkdir -p "$setup_host_target"
done <<SETUP_BIND_TARGETS
$PROOT_COMPONENT_BINDS
SETUP_BIND_TARGETS
PREFIX_PATH="$WINE_PREFIX_DIRECTORY"
HOME_PATH="$HOME_DIRECTORY"
RUNTIME_ROOT_PATH="$ROOTFS_CANONICAL"
CANCEL_PATH=
TERMUX_FILES_DIR="$TERMUX_FILES_ROOT"
PROOT_BIN="$PREFIX/bin/proot"
mkdir -p "$PREFIX_PATH" "$HOME_PATH"
resolve_rootfs_translator
# The prebuilt Hangover path only copies files; it needs no maintenance X server.
# Other runtimes still need a display before their template initializer is called.
if [ "$WINE_PACKAGE" != rootfs_hangover_11.16 ] || [ "$RUNTIME_TRANSLATOR" != hangover ]; then
    command -v termux-x11 >/dev/null 2>&1 || fail rootfs_prefix_warmup_display_missing 70
    MAINTENANCE_DISPLAY=:9
    termux-x11 "$MAINTENANCE_DISPLAY" >> "$LOG_PATH" 2>&1 &
    WARMUP_DISPLAY_PID=$!
fi
prepare_external_prefix_template
warmup_rootfs_prefix
[ "${CANCELLED:-0}" != 1 ] || fail rootfs_setup_cancelled 130
require_external_prepared_prefix
[ -z "$WARMUP_DISPLAY_PID" ] || kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true
WARMUP_DISPLAY_PID=

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
progress '==> Runtime installation completed'
COMPLETED=true
