#!/data/data/com.termux/files/usr/bin/sh

set -eu
PS4='+ '
set -x

SPEC_PATH=${1:?Usage: provision_rootfs_runtime.sh <provision-spec>}

TASK_ID=
PACKAGE_NAME=
VERSION=
RECIPE_SHA256=
CONTAINER_ID=
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
        containerId) CONTAINER_ID=$value ;;
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

for value in "$TASK_ID" "$PACKAGE_NAME" "$CONTAINER_ID" "$CONTAINER_NAME"; do
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
# Every container built from the same recipe (same box64/Wine source component, same
# provision-container.sh/games-runtime.properties, same version of this script -- that is
# exactly what RECIPE_SHA256 hashes) would otherwise redo an identical multi-hundred-MB
# download+unpack+apt-install. Build that once into a reserved "tmpl-" pseudo-container and
# hardlink-clone it into every real container instead. "tmpl-" is never a real containerId
# (those are generated, not user-chosen), so it never collides with, and is invisible to,
# the reset/backup/asset-scanning code paths that key off real containerIds.
TEMPLATE_CONTAINER_NAME="tmpl-${RECIPE_SHA256%${RECIPE_SHA256#????????????????}}"
TEMPLATE_DIRECTORY="$PREFIX/var/lib/proot-distro/containers/$TEMPLATE_CONTAINER_NAME"
TEMPLATE_ROOTFS="$TEMPLATE_DIRECTORY/rootfs"
# The built template is archived once and every real container is produced by extracting that
# archive -- independent real files, no hardlinks. Some Android data partitions reject hardlinks
# outright (link() returns EPERM even for a self-owned file), which made the old `cp -al` clone
# fail for every game; tar extraction is also exactly how proot-distro rootfs images normally
# ship. The cache lives beside containers/ (never under it) so it is not mistaken for a container
# and never matches the "tmpl-*" readiness scan. reset_rootfs_runtime.sh deletes the matching
# archive when it resets the template, so "reset RootFS" still forces a full rebuild next time.
TEMPLATE_CACHE_DIR="$PREFIX/var/lib/proot-distro/games-template-cache"
if command -v zstd >/dev/null 2>&1; then
    TEMPLATE_ARCHIVE="$TEMPLATE_CACHE_DIR/$TEMPLATE_CONTAINER_NAME.tar.zst"
    TEMPLATE_COMPRESSOR=zstd
else
    TEMPLATE_ARCHIVE="$TEMPLATE_CACHE_DIR/$TEMPLATE_CONTAINER_NAME.tar.gz"
    TEMPLATE_COMPRESSOR=gzip
fi
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
# provisioning script inside it. $1 = container name, $2 = its directory, $3 = its rootfs.
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
        --bind "$BUILD_CONTEXT:/run/games-provision" -- \
        /bin/sh /run/games-provision/provision-container.sh; then
        fail rootfs_guest_provision_failed 70
    fi
}
# Archives the already-built template container ($TEMPLATE_DIRECTORY, i.e. manifest.json +
# rootfs/ + shm/ + sysdata/) into $TEMPLATE_ARCHIVE once. Written to a .tmp then atomically
# renamed, so a present archive is always complete. No-op if the archive already exists.
ensure_template_archive() {
    [ -f "$TEMPLATE_ARCHIVE" ] && return 0
    mkdir -p "$TEMPLATE_CACHE_DIR"
    rm -f "$TEMPLATE_ARCHIVE.tmp"
    if ! run_logged tar -C "$TEMPLATE_DIRECTORY" \
        --use-compress-program "$TEMPLATE_COMPRESSOR" -cf "$TEMPLATE_ARCHIVE.tmp" .; then
        rm -f "$TEMPLATE_ARCHIVE.tmp"
        fail rootfs_template_archive_failed 70
    fi
    if ! mv "$TEMPLATE_ARCHIVE.tmp" "$TEMPLATE_ARCHIVE"; then
        rm -f "$TEMPLATE_ARCHIVE.tmp"
        fail rootfs_template_archive_failed 70
    fi
}
# Replaces $2 (container directory named $1) with a fresh extraction of the shared template
# archive -- independent real files, no hardlinks, so it works on filesystems that reject them.
extract_template_into() {
    name=$1
    directory=$2
    if [ -d "$directory" ]; then
        if ! run_logged "$PROOT_DISTRO" remove --quiet "$name"; then
            fail proot_distro_container_remove_failed 70
        fi
    fi
    mkdir -p "$directory"
    if ! run_logged tar -C "$directory" --use-compress-program "$TEMPLATE_COMPRESSOR" \
        --numeric-owner -xpf "$TEMPLATE_ARCHIVE"; then
        fail rootfs_template_extract_failed 70
    fi
}

if ! runtime_complete "$ROOTFS"; then
    if runtime_complete "$TEMPLATE_ROOTFS"; then
        progress '==> [3/4] Reusing the shared runtime template for this recipe (skips package install)'
    else
        build_runtime_into "$TEMPLATE_CONTAINER_NAME" "$TEMPLATE_DIRECTORY" "$TEMPLATE_ROOTFS"
        runtime_complete "$TEMPLATE_ROOTFS" || fail rootfs_template_build_invalid 70
    fi
    progress '==> Extracting the shared runtime template into this container'
    ensure_template_archive
    extract_template_into "$CONTAINER_NAME" "$CONTAINER_DIRECTORY"
fi

if ! runtime_complete "$ROOTFS"; then
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
