#!/data/data/com.termux/files/usr/bin/sh
# Runs in an actual Termux terminal session. Keep it POSIX-sh only.
set -u
PS4='+ '
set -x

SPEC=${1:?runtime_backup_spec_required}
[ -f "$SPEC" ] || { echo "runtime_backup_spec_missing" >&2; exit 2; }
. "$SPEC"

write_result() {
  status=$1
  runtime=$2
  error=${3:-}
  umask 077
  {
    printf 'status=%s\n' "$status"
    printf 'runtimeType=%s\n' "$runtime"
    printf 'error=%s\n' "$error"
  } > "$result"
}

fail() {
  code=$1
  echo "ERROR: $code" >&2
  write_result failed "${runtimeType:-unknown}" "$code"
  exit 1
}

trap 'rc=$?; [ "$rc" -eq 0 ] || [ -f "$result" ] || write_result failed "${runtimeType:-unknown}" "runtime_backup_command_failed"' EXIT

command -v tar >/dev/null 2>&1 || fail runtime_backup_tar_missing
case "$operation" in export|restore) ;; *) fail runtime_backup_operation_invalid ;; esac
case "${runtimeType:-}" in glibc|rootfs-proot|'') ;; *) fail runtime_backup_type_invalid ;; esac

mkdir -p "$jobDirectory" || fail runtime_backup_job_directory_failed

manifest_type() {
  sed -n 's/^runtimeType=//p' "$1" | head -n 1
}

check_archive_paths() {
  entries="$jobDirectory/archive.entries"
  tar -tf "$archive" > "$entries" || fail runtime_backup_archive_invalid
  cat "$entries"
  if grep -E '(^/|(^|/)\.\.?(/|$)|\\)' "$entries" >/dev/null 2>&1; then
    fail runtime_backup_path_unsafe
  fi
  grep -Fx 'runtime-backup.properties' "$entries" >/dev/null 2>&1 || fail runtime_backup_header_missing
}

check_runtime_payload_paths() {
  while IFS= read -r entry; do
    case "$runtimeType:$entry" in
      glibc:runtime-backup.properties|glibc:usr/glibc|glibc:usr/glibc/*|\
      glibc:games/components/install|glibc:games/components/install/*|\
      rootfs-proot:runtime-backup.properties|rootfs-proot:games/runtimes/rootfs|\
      rootfs-proot:games/runtimes/rootfs/*|\
      rootfs-proot:usr/var/lib/proot-distro/containers|\
      rootfs-proot:usr/var/lib/proot-distro/containers/*|\
      rootfs-proot:games/components/install/*)
        ;;
      *) fail runtime_backup_payload_unsafe ;;
    esac
  done < "$entries"
}

append_active_rootfs_containers() {
  runtime_root="$filesDirectory/games/runtimes/rootfs"
  containers_root="$filesDirectory/usr/var/lib/proot-distro/containers"
  found=0
  for pointer in "$runtime_root"/*/active.properties; do
    [ -f "$pointer" ] || continue
    package_root=$(dirname "$pointer")
    active=$(sed -n 's/^active=\([A-Za-z0-9._-]*\)$/\1/p' "$pointer" | head -n 1)
    [ -n "$active" ] || fail rootfs_backup_active_pointer_invalid
    receipt="$package_root/versions/$active.properties"
    [ -f "$receipt" ] || fail rootfs_backup_receipt_missing
    container=$(sed -n 's/^containerName=\([A-Za-z0-9._-]*\)$/\1/p' "$receipt" | head -n 1)
    [ -n "$container" ] || fail rootfs_backup_container_name_invalid
    [ -d "$containers_root/$container" ] || fail rootfs_backup_container_missing
    echo "Including active RootFS container: $container"
    tar \
      --exclude="usr/var/lib/proot-distro/containers/$container/rootfs/run/games-provision" \
      --exclude="usr/var/lib/proot-distro/containers/$container/rootfs/run/games-provision/*" \
      -C "$filesDirectory" -rvpf "$archive.partial" \
      "usr/var/lib/proot-distro/containers/$container" || fail runtime_backup_tar_failed
    found=1
  done
  [ "$found" = 1 ] || fail rootfs_backup_active_runtime_missing
}

build_rootfs_component_receipts() {
  snapshot="$jobDirectory/games/components/install"
  receipt_list="$jobDirectory/rootfs-component-receipts.list"
  install_root="$filesDirectory/games/components/install"
  rm -rf "$snapshot"
  mkdir -p "$snapshot" || fail runtime_backup_staging_create_failed
  : > "$receipt_list" || fail runtime_backup_staging_create_failed
  # The completed RootFS already contains Wine, DX and graphics packages. This
  # receipt only satisfies the Hangover source preflight; keeping its .deb files
  # would duplicate about 260 MiB of data already unpacked in the RootFS.
  for package_name in hangover-11.9-debian13-source; do
    package_root="$install_root/$package_name"
    pointer="$package_root/active.properties"
    [ -f "$pointer" ] || continue
    active=$(sed -n 's/^active=\([A-Za-z0-9._-]*\)$/\1/p' "$pointer" | head -n 1)
    [ -n "$active" ] || fail rootfs_backup_component_pointer_invalid
    receipt="$package_root/versions/$active/.games-component.properties"
    [ -f "$receipt" ] || fail rootfs_backup_component_receipt_missing
    target="$snapshot/$package_name/versions/$active"
    mkdir -p "$target" || fail runtime_backup_staging_create_failed
    cp "$pointer" "$snapshot/$package_name/active.properties" || fail runtime_backup_staging_create_failed
    cp "$receipt" "$target/.games-component.properties" || fail runtime_backup_staging_create_failed
    printf '%s\n' "games/components/install/$package_name/active.properties" >> "$receipt_list"
    printf '%s\n' "games/components/install/$package_name/versions/$active/.games-component.properties" >> "$receipt_list"
  done
}

append_rootfs_component_receipts() {
  receipt_list="$jobDirectory/rootfs-component-receipts.list"
  [ -s "$receipt_list" ] || return 0
  tar -C "$jobDirectory" -rvpf "$archive.partial" -T "$receipt_list" || \
    fail runtime_backup_tar_failed
}

export_runtime() {
  case "$runtimeType" in
    glibc)
      [ -d "$filesDirectory/usr/glibc" ] || fail glibc_runtime_missing
      [ -d "$filesDirectory/games/components/install" ] || fail runtime_component_receipts_missing
      ;;
    rootfs-proot)
      [ -d "$filesDirectory/games/runtimes/rootfs" ] || fail rootfs_runtime_missing
      [ -d "$filesDirectory/usr/var/lib/proot-distro/containers" ] || fail rootfs_runtime_missing
      [ -d "$filesDirectory/games/components/install" ] || fail runtime_component_receipts_missing
      ;;
  esac
  manifest="$jobDirectory/runtime-backup.properties"
  {
    printf 'schemaVersion=2\n'
    printf 'runtimeType=%s\n' "$runtimeType"
    if [ "$runtimeType" = rootfs-proot ]; then
      printf 'payloadScope=direct-runtime\n'
    else
      printf 'payloadScope=full\n'
    fi
    printf 'createdAt=%s\n' "$(date +%s)"
  } > "$manifest"
  rm -f "$archive.partial"
  echo "Creating tar archive…"
  case "$runtimeType" in
    glibc)
      tar -C "$jobDirectory" -vcpf "$archive.partial" runtime-backup.properties \
        -C "$filesDirectory" usr/glibc games/components/install || fail runtime_backup_tar_failed
      ;;
    rootfs-proot)
      # A direct-recovery archive contains only the containers referenced by the
      # active RootFS pointers. Component payloads are provisioning inputs, not
      # launch-time dependencies; retain their receipts so preflight remains valid.
      tar -C "$jobDirectory" -vcpf "$archive.partial" runtime-backup.properties || \
        fail runtime_backup_tar_failed
      tar -C "$filesDirectory" -rvpf "$archive.partial" games/runtimes/rootfs || \
        fail runtime_backup_tar_failed
      append_active_rootfs_containers
      build_rootfs_component_receipts
      append_rootfs_component_receipts
      ;;
  esac
  mv "$archive.partial" "$archive" || fail runtime_backup_archive_publish_failed
  echo "Archive created: $archive"
  write_result success "$runtimeType"
}

restore_runtime() {
  [ -f "$archive" ] || fail runtime_backup_archive_missing
  check_archive_paths
  manifest="$jobDirectory/restored-manifest.properties"
  tar -xOf "$archive" runtime-backup.properties > "$manifest" || fail runtime_backup_header_invalid
  schema=$(sed -n 's/^schemaVersion=//p' "$manifest" | head -n 1)
  runtimeType=$(manifest_type "$manifest")
  payloadScope=$(sed -n 's/^payloadScope=\([A-Za-z0-9._-]*\)$/\1/p' "$manifest" | head -n 1)
  [ "$schema" = 2 ] || fail runtime_backup_schema_unsupported
  case "$runtimeType" in glibc|rootfs-proot) ;; *) fail runtime_backup_type_invalid ;; esac
  case "$payloadScope" in ''|full|direct-runtime) ;; *) fail runtime_backup_scope_invalid ;; esac
  check_runtime_payload_paths
  echo "Restoring archive in place…"
  tar -C "$filesDirectory" --recursive-unlink --preserve-permissions -xvpf "$archive" || \
    fail runtime_backup_extract_failed
  case "$runtimeType" in
    glibc) [ -d "$filesDirectory/usr/glibc" ] || fail glibc_backup_payload_missing ;;
    rootfs-proot)
      [ -d "$filesDirectory/games/runtimes/rootfs" ] || fail rootfs_backup_payload_missing
      [ -d "$filesDirectory/usr/var/lib/proot-distro/containers" ] || fail rootfs_backup_containers_missing
      ;;
  esac
  echo "Runtime restored: $runtimeType"
  write_result success "$runtimeType"
}

if [ "$operation" = export ]; then export_runtime; else restore_runtime; fi
