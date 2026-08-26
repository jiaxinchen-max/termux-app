#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

change_id=""
stage=""
name=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --change-id) change_id="$2"; shift 2 ;;
    --stage) stage="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --) shift; break ;;
    *) echo "FAIL: unknown argument $1" >&2; exit 1 ;;
  esac
done

[[ "$change_id" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo "FAIL: invalid change-id" >&2; exit 1; }
[[ "$stage" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo "FAIL: invalid stage" >&2; exit 1; }
[[ "$name" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || { echo "FAIL: invalid name" >&2; exit 1; }
[[ $# -gt 0 ]] || { echo "FAIL: command is required after --" >&2; exit 1; }

run_dir=".harness/runs/$change_id"
[[ -d "$run_dir" ]] || { echo "FAIL: run not found: $run_dir" >&2; exit 1; }
mkdir -p "$run_dir/evidence/commands"
log="$run_dir/evidence/commands/${stage}-${name}.log"
command_file="$run_dir/evidence/commands/${stage}-${name}.command"
printf '%q ' "$@" > "$command_file"
printf '\n' >> "$command_file"

set +e
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
set -e

digest="$(shasum -a 256 "$log" | awk '{print $1}')"
printf '%s  %s\n' "$digest" "$(basename "$log")" > "$log.sha256"
echo "Evidence: $log"
echo "SHA-256: $digest"
exit "$status"
