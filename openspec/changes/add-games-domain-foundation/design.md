# Games 领域基础设计

## 总体方案

```text
UI -> LocalGameOrchestrator -> Repository interfaces
                               -> Game / RuntimeProfile / Task / Snapshot
```

模型不可变，所有集合构造时防御性复制。ID、路径引用和任务进度在构造时校验；任务更新返回新对象。

## Activity 与导航

Activity 后续只依赖 `LocalGameOrchestrator` 和稳定 task-id，不直接依赖具体 Repository。

## Service 与任务状态

- `LaunchTask` 使用 `LaunchTaskState` 与 `LaunchStage`。
- `ComponentTask` 使用 `ComponentTaskState`，保存断点、ETag 和 Last-Modified。
- Orchestrator 的观察接口返回可关闭 Subscription，避免 Activity 生命周期泄漏。

## 数据、存储与迁移

本分片只定义 Repository 契约；具体文件/数据库实现进入对应功能分片。模型携带 `SCHEMA_VERSION=1`，为后续持久化迁移提供显式版本。

## 组件下载与安装

仅定义 ComponentTask 及其 Repository；网络实现进入下一个 child change。

## X11、输入与运行会话

N/A。

## 受保护路径

不触碰 protected path；仅新增 `games/**` Java 和测试文件。

## 测试策略

- 模型输入、不变量和防御性复制。
- LaunchTask 与 ComponentTask 状态更新。
- Orchestrator/Repository 接口保持 Android-free。
- `:games:testDebugUnitTest`、lint、assemble、模块边界检查。

## 风险与回滚

按 proposal 执行。
