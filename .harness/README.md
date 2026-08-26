# Termux App Harness

`.harness` 是本项目的需求驱动开发工作台。它把需求分析、设计、实现、评审、测试、设备验证和归档组织成可接手、可审计的证据链。

## 目录

```text
.harness/
├── agents/       # 项目 Owner Agent 责任边界
├── policies/     # Android/Termux/X11/NDK 受保护路径
├── rules/        # 稳定工程约束
├── runs/         # 每个 change 的阶段台账和证据
├── schemas/      # YAML 产物结构定义
├── scripts/      # 启动、状态、采证、自检和归档脚本
├── templates/    # 通用阶段模板
└── workflows/    # 十阶段工作流

openspec/
├── config.yaml
├── specs/        # 已归档能力基线
└── changes/      # 活跃 change
```

## 核心原则

- 需求先进入 `openspec/changes/<change-id>/`，业务代码后修改。
- `proposal.md`、`design.md`、`specs/**/spec.md`、`tasks.md` 使用中文正文。
- `.harness/runs/<change-id>/` 保存十阶段状态、评审、命令日志和校验摘要。
- Activity 不直接执行 shell；运行链经 Service/Orchestrator 调用 Termux 后端。
- `TermuxActivity`、`TermuxService`、X11、启动脚本、组件索引和构建边界属于受保护路径。
- 用户确认规划后才能进入实现；归档、commit、push、部署分别授权。

## 常用命令

```bash
.harness/scripts/start-change.sh <change-id>
.harness/scripts/harness-status.sh --change-id <change-id>
.harness/scripts/harness-doctor.sh --change-id <change-id>
.harness/scripts/run-stage-command.sh --change-id <change-id> --stage unit-test --name app-unit-test -- ./gradlew :app:testDebugUnitTest
.harness/scripts/verify.sh
.harness/scripts/verify.sh --change-id <change-id> --strict-run
.harness/scripts/archive-change.sh --change-id <change-id> --dry-run
```

## 当前功能 change

- OpenSpec：`openspec/changes/implement-local-game-hub/`
- Run ledger：`.harness/runs/implement-local-game-hub/`

规划确认口令：

```text
确认 proposal/design/specs/tasks，可以进入实现。
```
