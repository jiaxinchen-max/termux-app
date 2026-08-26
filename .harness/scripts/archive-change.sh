#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

change_id=""
archive_date="$(date +%F)"
dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --change-id) change_id="$2"; shift 2 ;;
    --date) archive_date="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) echo "Usage: $0 --change-id <id> [--date YYYY-MM-DD] [--dry-run]" >&2; exit 1 ;;
  esac
done

[[ "$change_id" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo "FAIL: invalid change-id" >&2; exit 1; }
source_change="openspec/changes/$change_id"
source_run=".harness/runs/$change_id"
target_change="openspec/changes/archive/$archive_date-$change_id"
target_run=".harness/runs/archive/$archive_date-$change_id"
[[ -d "$source_change" && -d "$source_run" ]] || { echo "FAIL: active change/run pair missing" >&2; exit 1; }
grep -q 'final_archive: Approved' "$source_run/run_state.yaml" || { echo "FAIL: final_archive is not Approved" >&2; exit 1; }
if grep -Eq '^[[:space:]]*-[[:space:]]*\[[[:space:]]\]' "$source_change/tasks.md"; then
  echo "FAIL: unfinished tasks remain" >&2
  exit 1
fi
.harness/scripts/verify.sh --change-id "$change_id" --strict-run
if [[ "$dry_run" -eq 1 ]]; then
  echo "Dry run passed: $target_change"
  echo "Dry run passed: $target_run"
  exit 0
fi
[[ ! -e "$target_change" && ! -e "$target_run" ]] || { echo "FAIL: archive target exists" >&2; exit 1; }
mkdir -p openspec/changes/archive .harness/runs/archive
mv "$source_change" "$target_change"
if ! mv "$source_run" "$target_run"; then
  mv "$target_change" "$source_change"
  echo "FAIL: run archive failed; change restored" >&2
  exit 1
fi
.harness/scripts/verify.sh --change-id "$change_id" --strict-run
echo "Archived: $target_change"
echo "Archived: $target_run"
