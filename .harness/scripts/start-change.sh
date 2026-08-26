#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
  echo "Usage: .harness/scripts/start-change.sh [--spec <spec-id>] <change-id>"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

change_id=""
spec_id=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --spec)
      [[ $# -ge 2 ]] || fail "--spec requires a value"
      spec_id="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      [[ -z "$change_id" ]] || fail "unexpected argument: $1"
      change_id="$1"
      shift
      ;;
  esac
done

[[ "$change_id" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || fail "change-id must be lowercase kebab-case"
spec_id="${spec_id:-$change_id}"
[[ "$spec_id" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || fail "spec-id must be lowercase kebab-case"

change_dir="openspec/changes/$change_id"
run_dir=".harness/runs/$change_id"
[[ ! -e "$change_dir" ]] || fail "change already exists: $change_dir"
[[ ! -e "$run_dir" ]] || fail "run already exists: $run_dir"

mkdir -p "$change_dir/specs/$spec_id" \
  "$run_dir/request_analysis/review" \
  "$run_dir/coding/review" \
  "$run_dir/unit_test/review" \
  "$run_dir/pre_merge" \
  "$run_dir/ci_result" \
  "$run_dir/device_verify" \
  "$run_dir/evidence/commands"

sed "s/CHANGE_ID/$change_id/g" .harness/templates/run_state.yaml > "$run_dir/run_state.yaml"
sed "s/CHANGE_ID/$change_id/g" .harness/templates/change_manifest.yaml > "$run_dir/change_manifest.yaml"
sed "s/CHANGE_ID/$change_id/g" .harness/templates/evidence_manifest.yaml > "$run_dir/evidence/manifest.yaml"
cp .harness/templates/test_plan.md "$run_dir/unit_test/test_plan.md"

cat > "$change_dir/proposal.md" <<EOF
# $change_id 提案

## 背景

## 目标

## 非目标

## 用户可见行为

## 影响范围

## 风险与回滚
EOF

cat > "$change_dir/design.md" <<EOF
# $change_id 设计

## 总体方案

## Activity 与导航

## Service 与任务状态

## 数据、存储与迁移

## 组件下载与安装

## X11、输入与运行会话

## 受保护路径

## 测试策略

## 风险与回滚
EOF

cat > "$change_dir/tasks.md" <<EOF
# $change_id 任务

- [ ] 补齐并确认 proposal、design、specs 和 tasks
- [ ] 完成已确认范围实现
- [ ] 完成代码评审和测试评审
- [ ] 采集 Gradle、设备和运行时证据
- [ ] 用户接受后归档
EOF

cat > "$change_dir/specs/$spec_id/spec.md" <<EOF
## ADDED Requirements

### Requirement: $spec_id 行为被明确

实现前 MUST（必须）明确外部可观察行为和验证证据。

#### Scenario: 开始实现

- **WHEN** 开始修改业务代码
- **THEN** proposal、design、specs 和 tasks 已完成评审
- **AND** 受保护路径、测试策略和回滚方案已明确
EOF

cat > "$run_dir/summary.md" <<EOF
# $change_id Summary

## Status

- Current stage: Requirement Analysis
- OpenSpec change: $change_id

## Stage Ledger

| Stage | Status | Artifact |
| --- | --- | --- |
| 1. Requirement Analysis | In Progress | request_analysis/spec.md |
| 2. Requirement Review | Not Started | request_analysis/review/spec_review_v1.md |
| 3. Coding Implementation | Not Started | coding/coding_report_v1.md |
| 4. Code Review | Not Started | coding/review/code_review_v1.md |
| 5. Test Writing | Not Started | unit_test/test_plan.md |
| 6. Test Review | Not Started | unit_test/review/unit_test_review_v1.md |
| 7. Pre-merge Packaging | Not Started | pre_merge/pre_merge_summary_v1.md |
| 8. CI Validation | Not Started | ci_result/ci_result_v1.md |
| 9. Device Verification | Not Started | device_verify/device_verify_v1.md |
| 10. User Confirmation and Archive | Not Started | summary.md |
EOF

cat > "$run_dir/request_analysis/intake.md" <<EOF
# Request Intake

## 原始需求

## 需求澄清

## 人工确认
EOF

echo "# Requirement Analysis" > "$run_dir/request_analysis/spec.md"
echo "# Task Coverage Review" > "$run_dir/request_analysis/tasks.md"
echo -e "# Specification Review v1\n\n- Status: Pending" > "$run_dir/request_analysis/review/spec_review_v1.md"
sed -e "s/CHANGE_ID/$change_id/g" -e "s/REVIEW_NAME/specification-v1/g" .harness/templates/review_findings.yaml > "$run_dir/request_analysis/review/spec_findings.yaml"
echo -e "# Tasks Review v1\n\n- Status: Pending" > "$run_dir/request_analysis/review/tasks_review_v1.md"
sed -e "s/CHANGE_ID/$change_id/g" -e "s/REVIEW_NAME/tasks-v1/g" .harness/templates/review_findings.yaml > "$run_dir/request_analysis/review/tasks_findings.yaml"
echo -e "# Coding Report v1\n\n- Status: Not Started" > "$run_dir/coding/coding_report_v1.md"
echo -e "# Code Review v1\n\n- Status: Not Started" > "$run_dir/coding/review/code_review_v1.md"
sed -e "s/CHANGE_ID/$change_id/g" -e "s/REVIEW_NAME/code-v1/g" .harness/templates/review_findings.yaml > "$run_dir/coding/review/findings.yaml"
echo -e "# Test Report v1\n\n- Status: Not Started" > "$run_dir/unit_test/test_report_v1.md"
echo -e "# Test Review v1\n\n- Status: Not Started" > "$run_dir/unit_test/review/unit_test_review_v1.md"
sed -e "s/CHANGE_ID/$change_id/g" -e "s/REVIEW_NAME/test-v1/g" .harness/templates/review_findings.yaml > "$run_dir/unit_test/review/findings.yaml"
echo -e "# Pre-merge Summary v1\n\n- Status: Not Started" > "$run_dir/pre_merge/pre_merge_summary_v1.md"
echo -e "# CI Result v1\n\n- Status: Not Started" > "$run_dir/ci_result/ci_result_v1.md"
echo -e "# Device Verification v1\n\n- Status: Not Started" > "$run_dir/device_verify/device_verify_v1.md"
echo "Created OpenSpec change: $change_dir"
echo "Created Harness run: $run_dir"
