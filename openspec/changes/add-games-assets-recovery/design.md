# Design: Games assets, recovery and safe uninstall

## Private ownership map

每个 game-id 只允许操作以下 app 私有路径：

- prefix：`files/games/prefixes/<game-id>`
- cache：`files/games/cache/<game-id>`
- logs：由该 game 的 LaunchTask 引用且位于 `files/games/launches/logs/`
- snapshots：`files/games/snapshots/<game-id>`
- configuration：当前/last-success RuntimeProfile 文件
- artwork/library：同 game-id 私有封面和记录

SAF rootUri、外部 EXE、外部存档与任意未解析路径只显示为“外部内容受保护”，不统计为可清理私有字节，也不传给删除器。

## Asset inventory

`GameAssetInventory` 输出固定分类、字节数、文件数、存在状态和用户可删除性。递归扫描有文件数/深度上限，拒绝符号链接或 canonical path 逃逸；溢出时饱和为 `Long.MAX_VALUE` 并标记 incomplete。

## Snapshot transaction

`GameSnapshotManager.create()`：

1. 拒绝存在非终态 LaunchTask 的游戏。
2. 将配置和 prefix 复制到同目录 staging，拒绝链接/逃逸并执行边界限制。
3. 对排序后的相对路径、长度和文件内容计算 SHA-256。
4. fsync 关键文件，写 manifest 后 rename 发布不可变快照。

`restore()` 先完整校验快照，再复制到 restore staging；发布前持久化 prepared 事务，当前 prefix/config rename 为 rollback，staging rename 为 active。全部发布后持久化独立 committed 标记，再删除 rollback。进程重启时，prepared 无 committed 标记则恢复旧版本；有 committed 标记则保留新版本并清理临时文件。

## Safe uninstall

`GameUninstallPlan` 的外部内容策略固定为 KEEP。默认计划只删除 library/artwork；prefix、cache、logs、snapshots 和 profiles 均默认 false。执行前再次拒绝活动任务并校验每个目标仍位于允许的私有根。启动任务创建与资产变更共享进程内协调锁，避免空闲检查后并发入队。删除失败返回稳定错误且不触碰外部目录。

## UI

`GameAssetsActivity` 展示固定分类、总私有占用、外部内容保护说明和快照列表，提供创建、恢复、删除恢复点。游戏详情删除对话框展示私有资产选项，默认不勾选；确认后执行安全卸载计划。
