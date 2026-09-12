#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

failed=0
check_file() {
  if [[ -s "$1" ]]; then
    echo "OK   $1"
  else
    echo "FAIL $1"
    failed=1
  fi
}

check_command() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "OK   command:$1"
  else
    echo "WARN command:$1 unavailable"
  fi
}

check_file .harness/README.md
check_file .harness/workflows/HARNESS_WORKFLOW.md
check_file .harness/policies/protected-paths.yaml
check_file .harness/scripts/verify.sh
check_file openspec/config.yaml
check_file gradlew
check_command java
check_command adb
check_command ruby

if [[ "${1:-}" == "--change-id" ]]; then
  id="${2:-}"
  check_file "openspec/changes/$id/proposal.md"
  check_file ".harness/runs/$id/run_state.yaml"
fi

exit "$failed"
