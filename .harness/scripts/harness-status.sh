#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

change_id="${HARNESS_CHANGE_ID:-}"
if [[ "${1:-}" == "--change-id" ]]; then
  change_id="${2:-}"
fi

if [[ -z "$change_id" ]]; then
  count=0
  inferred=""
  while IFS= read -r candidate; do
    count=$((count + 1))
    inferred="$candidate"
  done < <(find openspec/changes -mindepth 1 -maxdepth 1 -type d ! -name archive -exec basename {} \; 2>/dev/null | sort)
  [[ "$count" -eq 1 ]] && change_id="$inferred"
fi

echo "Harness Status"
if [[ -z "$change_id" ]]; then
  echo "- Change id: none inferred"
  echo "- Next: pass --change-id <id>"
  exit 0
fi

run_dir=".harness/runs/$change_id"
state="$run_dir/run_state.yaml"
echo "- Change id: $change_id"
echo "- Run dir: $run_dir"
if [[ ! -s "$state" ]]; then
  echo "- State: missing"
  exit 1
fi

echo "- Current stage: $(awk '/^current_stage:/ {print $2}' "$state")"
echo "- Requirement approval: $(awk '/^[[:space:]]+requirements:/ {print $2}' "$state")"
must_fix="$(find "$run_dir" -type f -name '*findings.yaml' -exec grep -l -B3 -A3 'severity: MUST_FIX' {} \; 2>/dev/null || true)"
if [[ -n "$must_fix" ]]; then
  echo "- MUST_FIX files:"
  echo "$must_fix" | sed 's/^/  - /'
else
  echo "- MUST_FIX files: none"
fi
