# 安装会话四组合审计

本次为源码检查，不代表设备回归通过；未修改业务代码。范围：native/proot × Wine/desktop 的安装、准备、隐藏后恢复、动画和重复提交。

| 组合 | 动画及恢复原会话 | 同任务去重 | 结论 |
| --- | --- | --- | --- |
| native desktop | 缺失 INSTALLING 状态与安装任务关联 | 每次新建任务 ID，无同桌面去重 | 与 proot 共用入口，确认同样有缺陷 |
| proot desktop | 缺失 INSTALLING 状态与安装任务关联 | 每次新建任务 ID，无同桌面去重 | 原反馈路径确认 |
| native Wine | 已有卡片呼吸动画、Prefix 控制台恢复 | 已落盘任务可复用；落盘前仍生成新 ID | 不应重写已有动画；补原子预留与任务身份一致性 |
| proot Wine | 已有卡片呼吸动画、RootFS 控制台恢复 | 已落盘任务可复用；落盘前仍生成新 ID | 底层 RootFS 门禁通常拒绝第二个任务，但调用方仍拿到新 ID，不能等同恢复原会话 |

## 已确认遗漏

1. **桌面两模式安装状态与任务身份缺失**：DesktopTasks.state 先检查 isInstalled，首次安装过程返回 NOT_INSTALLED。install 无活动安装去重，每次生成时间戳 ID。DesktopActivity.bindCard 将该状态映射到安装确认。重装已有完成标记时也无正确 INSTALLING 状态。
2. **Wine 提交到任务落盘之间存在身份空档**：PrefixSetupTasks.enqueue 与 RuntimeSetupTasks.enqueue 先扫描任务仓库，再生成新 ID，未原子预留；RuntimeEditingStop.register 只是取消所有权关联，并不去重。PrefixSetupForegroundService 按 gameId 发现已有任务后直接返回，第二个调用方得到的 ID 未关联既有终端；RootfsSetupForegroundService 的 RuntimeInstallationGate 对不同 ID 拒绝，而不是返回原任务。此处确认的是代码竞态窗口，尚未设备复现。
3. **Wine 公共安装互斥遗漏 Prefix 任务**：LocalGamesActivity.showActiveInstallationConsole 覆盖组件、RootFS、reset、备份，但不检查 PrefixSetupTask。RuntimeInstallationGate 的 component/rootfs/reset 查询同样没有 PrefixSetupTask。组件作为 Prefix 的依赖须允许，但无关安装/重建不能绕过父任务保护；修复要区分父子关系，不能直接禁止全部组件任务。
4. **Wine 编辑入口可中断安装**：卡片正在 building 时主点击恢复会话，但详情/更多编辑仍可进入；GameDetailActivity.loadGame 调用 RuntimeEditingStop.game，后者会取消 Prefix/RootFS 任务并停止进程。这是现有显式编辑策略，并非隐藏窗口会取消；如要求安装中只查看进度，应在进入编辑前统一拦截，不只保护卡片主点击。是否改变明确编辑/删除的取消语义须在规划中声明。
5. **Wine 列表依赖轮询快照**：卡片点击闭包读取上次 building 快照；只有发现活动任务时才持续安排 buildingPoll，任务未落盘或入口快照过期时可能先显示启动入口。提交时可观测预留与点击时查询真实活动任务需共同修复。

## 已有正确逻辑

- Wine 两模式均将非终态准备任务显示为卡片背景呼吸动画，并分别打开 RuntimeSetupConsoleDialog/PrefixSetupConsoleDialog。
- 两类控制台 dismiss 仅释放观察/窗口；隐藏本身不调用取消安装。
- 已落盘的同 game/container 任务在 API 层可复用；Prefix 服务有同 game 去重；RootFS 服务有全局安装槽门禁。因此不能将两模式描述为全部没有防重。

## 修复建议与验收

原桌面修复方案需补充 Wine 提交阶段原子任务身份、所有入口点击恢复、Prefix 父任务互斥与编辑入口处理。保留已有 Wine 动画和控制台，统一生命周期事实，避免新建任务 ID 后靠服务拒绝。覆盖终端未创建、快速双击、隐藏重开、页面重建、成功、失败与提交异常；同一安装只应提交一次且始终恢复同一 ID。当前规划未获实施确认，本次仅完成检查。
