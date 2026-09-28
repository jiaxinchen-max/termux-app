# Games 组件管理页面设计

## 总体方案

```text
LocalGamesActivity
  -> ComponentCatalogRepository
       -> bundled ComponentIndex
       -> FileComponentTaskRepository
       -> ComponentInstallationReader
  -> ComponentTasks Intent API
       -> ComponentTaskForegroundService
```

页面 snapshot 是 index、最新相关任务和 active/previous 安装指针的组合结果；任务状态仍以持久 Repository 为事实源。

## Activity 与导航

- toolbar 下使用 Material TabLayout 提供“游戏库/组件”。
- 默认游戏库；旋转时保存 selected tab，组件页重新加载 snapshot。
- 组件页使用 ScrollView + 动态 MaterialCard，不新增 RecyclerView 依赖。
- onStart 启动低频刷新，onStop 移除 callback；Activity 不绑定或持有 Service。

## Service 与任务状态

- 主操作映射：未安装/可更新 -> enqueue，下载/安装中 -> pause，暂停 -> resume，失败 -> retry。
- 次操作映射：活动任务 -> cancel；存在 previous -> rollback 并二次确认。
- enqueue 返回稳定 task-id；页面刷新后通过 package/version/task 状态重新关联。

## 数据、存储与迁移

- `ComponentStoragePaths` 统一 `files/games/components/{tasks,downloads,install}`。
- `ComponentInstallationReader` 严格读取 receipt 和 active/previous，不修改组件内容。
- 不新增 schema；多任务选择优先当前版本的活动、暂停、失败任务。

## 组件下载与安装

不改变协议和安装事务。Installer 与 UI reader 共用 installation metadata 约束，回滚继续通过前台 Service 串行执行。

## X11、输入与运行会话

N/A。

## 受保护路径

- 不修改主 app、Manifest、TermuxService、脚本或 X11。
- 模块内 Activity/layout 为已确认父 change 的正常实现范围。

## 测试策略

- JVM：catalog 状态映射、任务优先级、安装/previous、错误状态。
- AndroidTest：打开组件 tab、16 项渲染、Activity recreate 后 tab 与状态恢复。
- 回归：33 个既有 Games JVM 测试、lint、assemble、app compile、module boundary。

## 风险与回滚

按 proposal 执行。
