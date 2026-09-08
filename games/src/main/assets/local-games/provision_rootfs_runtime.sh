#!/data/data/com.termux/files/usr/bin/sh

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: provision_rootfs_runtime.sh <provision-spec>}

TASK_ID=
PACKAGE_NAME=
VERSION=
RECIPE_SHA256=
CONTAINER_NAME=
BUILD_CONTEXT=
RECIPE_DIRECTORY=
SOURCE_DIRECTORY=
METADATA_ROOT=
EVENTS_PATH=
LOG_PATH=
COMPLETED=false
FAILURE_CODE=provision_script_failed

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
}
trap on_exit EXIT

fail() {
    FAILURE_CODE=$1
    exit "${2:-1}"
}

while IFS='=' read -r key value; do
    case "$key" in
        schemaVersion) [ "$value" = 2 ] || fail unsupported_provision_spec 64 ;;
        taskId) TASK_ID=$value ;;
        packageName) PACKAGE_NAME=$value ;;
        version) VERSION=$value ;;
        recipeSha256) RECIPE_SHA256=$value ;;
        containerName) CONTAINER_NAME=$value ;;
        buildContext) BUILD_CONTEXT=$value ;;
        recipeDirectory) RECIPE_DIRECTORY=$value ;;
        sourceDirectory) SOURCE_DIRECTORY=$value ;;
        metadataRoot) METADATA_ROOT=$value ;;
        eventsPath) EVENTS_PATH=$value ;;
        logPath) LOG_PATH=$value ;;
        '') ;;
        *) fail invalid_provision_spec 64 ;;
    esac
done < "$SPEC_PATH"

for value in "$TASK_ID" "$PACKAGE_NAME" "$CONTAINER_NAME"; do
    case "$value" in ''|*[!A-Za-z0-9._-]*) fail invalid_provision_identifier 64 ;; esac
done
case "$VERSION" in ''|*[!0-9]*|0*) fail invalid_provision_version 64 ;; esac
case "$RECIPE_SHA256" in *[!0-9a-f]*|'') fail invalid_provision_recipe_digest 64 ;; esac
[ "${#RECIPE_SHA256}" -eq 64 ] || fail invalid_provision_recipe_digest 64

PRIVATE_ROOT=${SPEC_PATH%/runtime/provision/specs/*}
[ -n "$PRIVATE_ROOT" ] && [ "$PRIVATE_ROOT" != "$SPEC_PATH" ] || \
    fail invalid_provision_spec_path 64
case "$SPEC_PATH" in "$PRIVATE_ROOT"/runtime/provision/specs/*.provisionspec) ;; *)
    fail invalid_provision_spec_path 64 ;;
esac
for path in "$SPEC_PATH" "$BUILD_CONTEXT" "$RECIPE_DIRECTORY" "$SOURCE_DIRECTORY" \
    "$METADATA_ROOT" "$EVENTS_PATH" "$LOG_PATH"; do
    case "$path" in "$PRIVATE_ROOT"/*) ;; *) fail provision_path_outside_private_storage 64 ;; esac
    case "$path" in *'/../'*|*/..|*'/./'*|*/.) fail invalid_provision_private_path 64 ;; esac
done
events_directory=${EVENTS_PATH%/*}
logs_directory=${LOG_PATH%/*}
[ "$events_directory" != "$EVENTS_PATH" ] && [ "$logs_directory" != "$LOG_PATH" ] || \
    fail invalid_provision_output_path 64
mkdir -p "$events_directory" "$logs_directory" || fail provision_output_directory_failed 70
: >> "$LOG_PATH"

progress() {
    printf '%s\n' "$1" | tee -a "$LOG_PATH"
}

# Keep the durable log for task reconciliation, while forwarding command output to
# the PTY when provisioning was started from the Games component screen.
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

PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
PROOT_DISTRO="$PREFIX/bin/proot-distro"
CONTAINER_DIRECTORY="$PREFIX/var/lib/proot-distro/containers/$CONTAINER_NAME"
ROOTFS="$CONTAINER_DIRECTORY/rootfs"
BASE_IMAGE=debian:trixie-20260824
supports_container_provision() {
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
supports_container_provision || {
    fail proot_distro_install_login_unsupported 69
}
[ -f "$RECIPE_DIRECTORY/provision-container.sh" ] || fail rootfs_recipe_script_missing 66
[ -f "$RECIPE_DIRECTORY/games-runtime.properties" ] || fail rootfs_recipe_manifest_missing 66
find "$SOURCE_DIRECTORY" -name '*.deb' -type f | grep -q . || fail rootfs_source_packages_missing 66

progress '==> [2/4] Preparing runtime build context'
rm -rf "$BUILD_CONTEXT"
mkdir -p "$BUILD_CONTEXT/hangover-source"
cp "$RECIPE_DIRECTORY/provision-container.sh" "$BUILD_CONTEXT/provision-container.sh"
cp "$RECIPE_DIRECTORY/games-runtime.properties" "$BUILD_CONTEXT/games-runtime.properties"
cp -al "$SOURCE_DIRECTORY"/. "$BUILD_CONTEXT/hangover-source"/ 2>/dev/null || \
    cp -a "$SOURCE_DIRECTORY"/. "$BUILD_CONTEXT/hangover-source"/

printf '{"schemaVersion":1,"taskId":"%s","state":"BUILDING"}\n' "$TASK_ID" >> "$EVENTS_PATH"

runtime_complete() {
    [ -x "$ROOTFS/usr/bin/env" ] &&
        [ -x "$ROOTFS/usr/bin/wine" ] &&
        [ -x "$ROOTFS/usr/bin/wineboot" ] &&
        [ -f "$ROOTFS/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc" ] &&
        [ -f "$ROOTFS/etc/games-runtime.properties" ] &&
        [ -d "$ROOTFS/mnt/games/game" ] &&
        [ -d "$ROOTFS/mnt/games/prefix" ]
}
base_container_ready() {
    [ -x "$ROOTFS/usr/bin/env" ] && [ -f "$CONTAINER_DIRECTORY/manifest.json" ]
}
if ! runtime_complete; then
    if ! base_container_ready; then
        progress '==> [3/4] Downloading and unpacking Debian ImageFS'
        if [ -d "$CONTAINER_DIRECTORY" ]; then
            if ! run_logged "$PROOT_DISTRO" remove --quiet "$CONTAINER_NAME"; then
                fail proot_distro_container_remove_failed 70
            fi
        fi
        if ! run_logged "$PROOT_DISTRO" install --architecture aarch64 --name "$CONTAINER_NAME" \
            "$BASE_IMAGE"; then
            fail proot_distro_container_install_failed 70
        fi
    fi
    if ! base_container_ready; then
        fail proot_distro_base_container_invalid 70
    fi
    progress '==> [4/4] Installing game runtime packages in Debian'
    if ! run_logged "$PROOT_DISTRO" login "$CONTAINER_NAME" --isolated \
        --bind "$BUILD_CONTEXT:/run/games-provision" -- \
        /bin/sh /run/games-provision/provision-container.sh; then
        fail rootfs_guest_provision_failed 70
    fi
fi

if ! runtime_complete; then
    fail games_runtime_container_invalid 70
fi

PACKAGE_ROOT="$METADATA_ROOT/$PACKAGE_NAME"
VERSION_ID="v${VERSION}-${RECIPE_SHA256%${RECIPE_SHA256#????????????}}"
mkdir -p "$PACKAGE_ROOT/versions"
RECEIPT_TMP="$PACKAGE_ROOT/versions/$VERSION_ID.properties.tmp"
RECEIPT="$PACKAGE_ROOT/versions/$VERSION_ID.properties"
{
    printf 'schemaVersion=1\n'
    printf 'packageName=%s\n' "$PACKAGE_NAME"
    printf 'version=%s\n' "$VERSION"
    printf 'recipeSha256=%s\n' "$RECIPE_SHA256"
    printf 'containerName=%s\n' "$CONTAINER_NAME"
} > "$RECEIPT_TMP"
mv "$RECEIPT_TMP" "$RECEIPT"

POINTER="$PACKAGE_ROOT/active.properties"
PREVIOUS=
if [ -f "$POINTER" ]; then
    PREVIOUS=$(sed -n 's/^active=\([A-Za-z0-9._-]*\)$/\1/p' "$POINTER" | head -n 1)
fi
POINTER_TMP="$POINTER.tmp"
{
    printf 'schemaVersion=1\n'
    printf 'active=%s\n' "$VERSION_ID"
    printf 'previous=%s\n' "$PREVIOUS"
} > "$POINTER_TMP"
[ ! -f "$POINTER" ] || cp "$POINTER" "$POINTER.bak"
mv "$POINTER_TMP" "$POINTER"
rm -f "$POINTER.bak"

printf '{"schemaVersion":1,"taskId":"%s","state":"SUCCEEDED"}\n' "$TASK_ID" >> "$EVENTS_PATH"
progress '==> Runtime installation completed'
COMPLETED=true
