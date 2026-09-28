# Games 游戏库、封面与详情设计

## 总体方案

```text
LocalGamesActivity
  -> GameLibraryRepository
       -> FileGameRepository
       -> SafGameAccessProbe
  -> GameDetailActivity
       -> FileGameRepository
       -> GameArtworkStore / GameArtworkLoader
       -> GameImportActivity (reauthorize)
```

列表和详情均从 Repository 重载事实状态，不跨 Activity 传递完整 Game 对象。

## Activity 与导航

- `LocalGamesActivity.onStart` 后台加载游戏库，空库显示空态，非空库渲染动态 MaterialCard。
- 卡片展示本地封面或模块占位图、名称、相对 EXE、最近游玩和目录权限状态。
- 点击卡片通过显式 game-id Intent 打开私有 `GameDetailActivity`。
- 详情支持保存字段、选择/移除封面、重新授权和二次确认安全删除；返回库后重新加载。

## Service 与任务状态

N/A。库读取、权限探测、封面导入和保存是短时后台 I/O，不创建 Service。

## 数据、存储与迁移

- `GameLibraryItem` 聚合 Game 和 `GameAccessState`；权限状态不写入 Game，避免缓存过期事实。
- `SafGameAccessProbe` 先验证 persisted read permission，再只读查询 tree root；区分 ACCESSIBLE、PERMISSION_LOST、PROVIDER_UNAVAILABLE。
- 私有封面位于 `files/games/artwork/<game-id>.cover`，Game 保存稳定的 `local-games://artwork/<game-id>` 引用。
- 封面导入限制 20 MiB、最大边长 16384，采用同目录 temp/backup/publish；Game 保存失败时回滚旧封面。
- 删除顺序为 Game 记录后私有封面；外部 tree URI 和用户文件永不删除。

## 组件下载与安装

N/A。

## X11、输入与运行会话

N/A。

## 受保护路径

- 不修改 Critical protected paths 或 build 文件。
- `games/src/main/AndroidManifest.xml` 仅声明模块私有详情 Activity，不命中主 app manifest policy。

## 测试策略

- JVM：library snapshot 状态/排序、参数 format/parse round-trip、Game 更新和删除边界。
- AndroidTest：详情 Intent/private manifest、空库/有库切换、权限丢失展示、Activity recreate、私有 PNG 封面导入/加载/移除和安全删除不触碰外部目录。
- 回归：Games 全量 JVM/AndroidTest、lint、AAR、app compile、module boundary。

## 风险与回滚

按 proposal 执行。封面和库记录均位于 app 私有目录，可单独清理；外部游戏资产始终只读。
