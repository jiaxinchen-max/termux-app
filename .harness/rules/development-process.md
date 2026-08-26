# Development Process Rules

1. 新功能和行为修改必须对应一个 active OpenSpec change。
2. 规划产物和 Harness run 使用相同 change-id。
3. 规划确认前只允许修改 `.harness/`、`openspec/` 和明确的分析文档。
4. 业务实现只覆盖已确认 tasks；新增范围先更新规划并重审。
5. 每阶段结果写入 run ledger，不用聊天结论替代文件证据。
6. 命令验证通过 `run-stage-command.sh` 采集日志和 checksum。
7. Review findings 中存在 Open MUST_FIX 时不得进入下一门禁阶段。
8. 用户接受后才能更新最终产品能力文档和归档。
9. 归档不授权 git stage、commit、push 或发布。
