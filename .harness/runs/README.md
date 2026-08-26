# Harness Runs

- Active run：`.harness/runs/<change-id>/`
- Archived run：`.harness/runs/archive/YYYY-MM-DD-<change-id>/`
- OpenSpec change 与 run 必须使用相同 change-id。
- `run_state.yaml` 是阶段状态源，`evidence/manifest.yaml` 是验证证据索引。
- 不手工删除已归档 run。
