# Games 游戏级运行配置设计

## 总体方案

```text
GameRuntimeProfileActivity
  -> FileRuntimeProfileRepository
  -> RuntimeProfilePresets / RuntimeProfileDiff
  -> LegacyRuntimeProfileMapper
       <- LocalGamesHost.listLegacyRuntimeConfigurations()
  -> LaunchPreflightEvaluator
       -> ComponentIndex + ComponentInstallationReader
       -> StorageSpaceProvider + GameAccessState + LocalGamesHost
```

RuntimeProfile id 固定等于 game-id，避免修改 Game schema；配置与 Game 生命周期一一对应。启动链后续按 game-id 读取并冻结 profile。

## Activity 与导航

- `GameDetailActivity` 增加“运行配置”入口，只传 game-id。
- 模块私有 `GameRuntimeProfileActivity` 后台加载 Game、profile、legacy snapshots、组件安装状态和可用空间。
- 页面支持四类预设、TermuxBox 配置导入、关键字段编辑、字段级变更摘要、保存、恢复默认和恢复上次成功。
- Activity recreate 保存未提交表单；所有 I/O callback 使用 generation 丢弃过期结果。

## Service 与任务状态

本分片不创建 Service 或 LaunchTask。`LaunchPreflightEvaluator` 是纯同步领域服务；Activity 在后台线程执行。后续启动 Orchestrator 必须复用同一 evaluator，并在创建 LaunchTask 前重新计算。

## 数据、存储与迁移

- 当前 profile：`files/games/profiles/<game-id>.properties`。
- 上次成功快照：`files/games/profiles/<game-id>.last-success.properties`。
- schema v1 使用固定键与有界 map 条目；环境变量键仅允许 `[A-Za-z_][A-Za-z0-9_]*`，组件版本必须为正整数文本。
- 保存采用 `.tmp` 后同目录 rename；文件名和 profile id 必须一致。
- 删除 Game 时本分片不自动删除 profile，留待资产安全分片统一实现；读取不存在的 profile 时生成推荐默认值但不隐式落盘。

## TermuxBox 只读映射

- API 增加不可变 `LegacyRuntimeConfiguration` DTO 和 Host 只读列表方法。
- app adapter 使用现有 `TermuxBoxRepository.getContainers()` 建立 DTO；不调用 save/setCurrent/remove。
- Games mapper转换 Wine、graphics、DX、audio、resolution、Box64、env 和 gamepad 字段。
- 导入必须由用户显式触发，源 container id 仅用于 UI 标签，不写入旧配置。

## 预设与变更摘要

- 推荐：Wine 9.3 vanilla、Turnip、DXVK、ALSA、1280x720、INTERMEDIATE。
- 稳定：Wine 9.0 staging、Turnip、DXVK、ALSA、1280x720、INTERMEDIATE。
- 兼容：Wine 8.18 staging、VirGL、WineD3D、ALSA、1024x768、STABILITY。
- 自定义：保留当前字段，不覆盖值。
- Diff 按稳定字段顺序输出 before/after；环境和组件版本按 key 排序。

## 组件依赖与存储预算

- 固定基础组件：`scripts`、`glibc-prefix`、`box64-binaries`、`prefix-apps`、`libudev`、`en-ru-locale`。
- Wine 使用 `winePackage`；DXVK/WineD3D 和 Turnip/VirGL 按 profile 选择追加。
- index 不存在、未安装、版本/SHA 不匹配分别形成阻断项。
- 新 prefix 保留 1 GiB；每个缺失组件按 index 压缩大小的 3 倍估算下载、staging 与安装峰值，使用饱和加法避免溢出。
- SAF 权限、Provider、Host runtime、组件和空间问题均阻断；结果包含稳定 code、组件 id、required/available bytes。

## 组件下载与安装

预检只读取现有 index 和安装 receipt，不创建任务。组件下载仍由现有持久前台任务链负责。

## X11、输入与运行会话

N/A。inputProfileId 仅保存映射结果，不挂载输入或 X11。

## 受保护路径

不修改已登记 Critical 路径。app 侧仅修改 `app/src/main/java/com/termux/app/localgames/TermuxLocalGamesHostFactory.java`；Games library manifest 仅声明模块私有 Activity。

## 测试策略

- JVM：profile 校验/持久化/损坏拒绝/原子覆盖、legacy 映射、四预设、稳定 diff、环境解析、依赖解析、安装版本与 SHA、权限/runtime/空间错误。
- AndroidTest：详情入口与 private manifest、配置页面保存/recreate/默认恢复、真实 StatFs 结果渲染。
- 回归：Games 全量 JVM/AndroidTest、lint、AAR、app compile、module boundary 和所有 Harness strict。

## 风险与回滚

按 proposal 执行。配置均位于 app 私有目录且不改变既有 Game/TermuxBox 数据，功能可按模块入口独立回滚。
