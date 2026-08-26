#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

change_id=""
strict=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --change-id) change_id="$2"; shift 2 ;;
    --strict-run) strict=1; shift ;;
    -h|--help)
      echo "Usage: .harness/scripts/verify.sh [--change-id <id>] [--strict-run]"
      exit 0
      ;;
    *) echo "FAIL: unknown argument $1" >&2; exit 1 ;;
  esac
done

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

required=(
  .harness/README.md
  .harness/workflows/HARNESS_WORKFLOW.md
  .harness/rules/development-process.md
  .harness/rules/engineering-structure.md
  .harness/rules/project-coding-standards.md
  .harness/policies/protected-paths.yaml
  .harness/templates/run_state.yaml
  .harness/templates/change_manifest.yaml
  .harness/templates/evidence_manifest.yaml
  openspec/config.yaml
)
for file in "${required[@]}"; do
  [[ -s "$file" ]] || fail "missing required file: $file"
done

for template in .harness/templates/run_state.yaml .harness/templates/change_manifest.yaml .harness/templates/evidence_manifest.yaml; do
  .harness/scripts/validate-yaml-schema.rb "$template" >/dev/null
done

if [[ "$strict" -eq 0 ]]; then
  echo "Harness verification passed."
  exit 0
fi

[[ "$change_id" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || fail "strict-run requires a valid --change-id"
change_dir="openspec/changes/$change_id"
run_dir=".harness/runs/$change_id"
if [[ ! -d "$change_dir" ]]; then
  change_dir="$(find openspec/changes/archive -maxdepth 1 -type d -name "*-$change_id" -print -quit 2>/dev/null || true)"
fi
if [[ ! -d "$run_dir" ]]; then
  run_dir="$(find .harness/runs/archive -maxdepth 1 -type d -name "*-$change_id" -print -quit 2>/dev/null || true)"
fi
[[ -d "$change_dir" ]] || fail "OpenSpec change not found: $change_id"
[[ -d "$run_dir" ]] || fail "Harness run not found: $change_id"

for file in proposal.md design.md tasks.md; do
  [[ -s "$change_dir/$file" ]] || fail "missing planning artifact: $change_dir/$file"
done
find "$change_dir/specs" -type f -name spec.md -size +0c | grep -q . || fail "missing change spec"
for file in run_state.yaml change_manifest.yaml summary.md request_analysis/spec.md request_analysis/tasks.md evidence/manifest.yaml; do
  [[ -s "$run_dir/$file" ]] || fail "missing run artifact: $run_dir/$file"
done

grep -q "change_id: $change_id" "$run_dir/run_state.yaml" || fail "run_state change_id mismatch"
grep -q "change_id: $change_id" "$run_dir/change_manifest.yaml" || fail "change_manifest change_id mismatch"
grep -q "openspec_change: $change_id" "$run_dir/change_manifest.yaml" || fail "manifest openspec_change mismatch"
.harness/scripts/validate-yaml-schema.rb "$run_dir/run_state.yaml" >/dev/null
.harness/scripts/validate-yaml-schema.rb "$run_dir/change_manifest.yaml" >/dev/null
.harness/scripts/validate-yaml-schema.rb "$run_dir/evidence/manifest.yaml" >/dev/null
while IFS= read -r findings; do
  .harness/scripts/validate-yaml-schema.rb "$findings" >/dev/null
done < <(find "$run_dir" -type f -name '*findings.yaml' | sort)

current_stage="$(awk '/^current_stage:/ {print $2}' "$run_dir/run_state.yaml")"
if [[ "$current_stage" -ge 3 ]] && grep -A8 '^protected_paths:' "$run_dir/change_manifest.yaml" | grep -q 'status: Pending'; then
  fail "protected path approval is still Pending at implementation stage"
fi
while IFS= read -r model_path; do
  [[ -z "$model_path" || -s "$model_path" ]] || fail "domain model path missing: $model_path"
done < <(awk '
  /^domain_model:/ {in_domain=1; next}
  in_domain && /^[^[:space:]]/ {in_domain=0}
  in_domain && /^[[:space:]]+model_paths:/ {in_paths=1; next}
  in_paths && /^[[:space:]]+- / {sub(/^[[:space:]]+- /, ""); print; next}
  in_paths && !/^[[:space:]]+- / {in_paths=0}
' "$run_dir/change_manifest.yaml")

if grep -R -n -E 'TODO|TBD|Describe the current problem' "$change_dir" >/dev/null; then
  fail "planning artifacts contain placeholders"
fi

if find "$run_dir" -type f -name '*findings.yaml' -exec grep -l -A3 'severity: MUST_FIX' {} \; | grep -q .; then
  fail "run contains MUST_FIX findings; inspect findings status"
fi

while IFS= read -r checksum_file; do
  (cd "$(dirname "$checksum_file")" && shasum -a 256 -c "$(basename "$checksum_file")") >/dev/null || fail "checksum mismatch: $checksum_file"
done < <(find "$run_dir/evidence" -type f -name '*.sha256' | sort)

echo "Strict Harness verification passed for $change_id."
