## ADDED Requirements

### Requirement: Games 领域对象具有稳定不变量

系统 MUST 使用不可变领域对象表达游戏、运行配置、启动任务、组件任务和快照，并在对象边界拒绝无效 ID、进度、摘要和状态数据。

#### Scenario: 更新任务状态

- **WHEN** Repository 或 Orchestrator 更新任务进度和状态
- **THEN** 返回新的不可变任务快照
- **AND** 原对象保持不变
- **AND** 进度、终态和任务标识满足模型不变量

### Requirement: UI 与具体后端解耦

系统 MUST 通过 Repository 和 LocalGameOrchestrator 接口隔离 UI、持久化和 Termux 执行后端。

#### Scenario: 使用 fake backend 测试 UI

- **WHEN** UI 注入 fake Orchestrator 和内存 Repository
- **THEN** 不需要 Android Service、终端 Session 或 Wine/X11
- **AND** 仍可提交、观察和取消稳定 task-id
