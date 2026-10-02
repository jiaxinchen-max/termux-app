#!/data/data/com.termux/files/usr/bin/sh
# Runs in an actual Termux terminal session. Keep it POSIX-sh only.
# GLIBC-only: RootFS backup/restore was removed when the RootFS runtime moved to one shared,
# always-current image (see GameStoragePaths.getSharedRootfsDirectory()) -- there is no more
# per-container install to export, and the shared image itself is regenerable from the base
# archive, not user data worth backing up.
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
case "${runtimeType:-}" in glibc|'') ;; *) fail runtime_backup_type_invalid ;; esac

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
      glibc:games/components/install|glibc:games/components/install/*)
        ;;
      *) fail runtime_backup_payload_unsafe ;;
    esac
  done < "$entries"
}

export_runtime() {
  [ -d "$filesDirectory/usr/glibc" ] || fail glibc_runtime_missing
  [ -d "$filesDirectory/games/components/install" ] || fail runtime_component_receipts_missing
  manifest="$jobDirectory/runtime-backup.properties"
  {
    printf 'schemaVersion=2\n'
    printf 'runtimeType=%s\n' "$runtimeType"
    printf 'payloadScope=full\n'
    printf 'createdAt=%s\n' "$(date +%s)"
  } > "$manifest"
  rm -f "$archive.partial"
  echo "Creating tar archive…"
  tar -C "$jobDirectory" -vcpf "$archive.partial" runtime-backup.properties \
    -C "$filesDirectory" usr/glibc games/components/install || fail runtime_backup_tar_failed
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
  [ "$runtimeType" = glibc ] || fail runtime_backup_type_invalid
  case "$payloadScope" in ''|full) ;; *) fail runtime_backup_scope_invalid ;; esac
  check_runtime_payload_paths
  echo "Restoring archive in place…"
  tar -C "$filesDirectory" --recursive-unlink --preserve-permissions -xvpf "$archive" || \
    fail runtime_backup_extract_failed
  [ -d "$filesDirectory/usr/glibc" ] || fail glibc_backup_payload_missing
  echo "Runtime restored: $runtimeType"
  write_result success "$runtimeType"
}

if [ "$operation" = export ]; then export_runtime; else restore_runtime; fi
