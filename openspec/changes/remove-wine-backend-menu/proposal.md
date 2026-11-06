# 移除 Wine 配置中的运行后端菜单

## Why

Wine 已按 Native 与 PRoot 分开管理，导入应沿用进入时的分组。当前导入没有后端选择器，但共用配置页仍显示“运行环境后端”菜单，容易被理解为可以转换配置所属类型；用户要求移除该入口并禁止页内切换。

## What Changes

- 从新导入和已有配置共用的运行配置页移除“运行环境后端”菜单及其只读子页；不以禁用或改名保留该入口。
- 将该子页原有的“组件”入口移到配置根页，继续按当前游戏绑定的后端管理组件，不增加类型选择器。
- 保持当前导入规则：Native 入口创建 Native 配置，PRoot 入口创建 PRoot 配置；已有配置编辑、保存、选择组件均不得转换其后端。
- 用户已批准实施，并补充：编辑页标题下只读显示当前配置的 Native/PRoot 后端，覆盖横竖屏及嵌入/独立运行配置页。
- 不恢复此前已删除的切换逻辑；源码中的现有子页已经只读。

## Capabilities

### New Capabilities

- `wine-library-runtime-scope`：Wine 导入和运行配置归属当前 Native/PRoot 分组，配置页不提供后端菜单或转换操作。

### Modified Capabilities

无。当前 `openspec/specs/` 没有正式基线，使用新的能力规格描述用户要求。

## Impact

- 计划修改 `GameRuntimeOptionsView.java`、`GameDetailActivity.java` 和横竖屏编辑布局，清理仅供被移除页面使用且无其他引用的字符串；共用 View 同时影响嵌入页和独立 Activity。
- 不改变 profile/container 的持久化 schema、运行时 SPI、启动器、C 盘和已有配置，不做 Native/PRoot 数据迁移。同一 EXE 在另一分组中继续通过独立导入创建配置。
- HTTP API：`N/A`，没有 HTTP 契约变化。
- 产品文档：`Required`，影响用户流程；验收后维护能力文档 `docs/product/wine-library.md`。该路径当前不存在，只记录规划目标，不提前写入正式产品事实。
- 非目标：转换已有配置、修改 `withRuntimeBackend` 领域接口、组件/RootFS 安装、真实 Wine/GPU 验收，以及上一轮脚本夹具修复。
- 风险：直接删除子页会丢失组件入口，因此保留其独立入口和游戏作用域；不依赖资源文案判断配置类型。
- 回滚：只撤销本次 UI/字符串改动，不更改已保存配置或设备数据。
