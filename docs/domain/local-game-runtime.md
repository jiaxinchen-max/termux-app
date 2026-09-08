# Local Game Runtime Domain

## 概念

- Game：用户可识别的本地游戏资产，引用外部目录和主 EXE。
- RuntimeProfile：游戏运行所需的后端、RootFS、Wine、Box64、图形、音频、分辨率、环境、输入和执行模式配置。
- GameRuntimeBackendType：每游戏冻结的 `GLIBC_TERMUX_BOX` 或 `ROOTFS_PROOT` 运行实现，与 AppExperienceMode 正交。
- 两个 GameRuntimeBackendType 均为正式能力；组件缺失只产生可恢复 preflight，不隐藏后端、不自动回退。
- GameRuntimeBackend：拥有组件需求、Prefix、RootFS 解析和启动脚本选择的后端 SPI。
- RootfsInstallationResolver：只从 Games runtime receipt 解析 Termux PRoot-Distro 私有容器的 immutable `rootfs/`，并校验 manifest 声明的运行器、图形和 DX 能力。
- RuntimeProvisionTask：持久化设备内 recipe 准备、PRoot 构建、校验和激活状态；不复用 LaunchTask。
- Component：可下载、校验、安装和回滚的共享运行组件。
- ComponentType：GameHub 对齐的产品槽位，固定为 ImageFS、容器、GPU、DirectX、转译器、通用组件和运行支持；不改变物理交付 identity。
- ComponentDescriptor：同时保存不可变交付字段和产品展示/兼容元数据；profileValue 与 package id 可不同，例如 `virgl` 对应 `virgl-mesa`。
- ComponentCatalogSnapshot：组件索引、当前相关任务和安装快照组合出的只读页面状态。
- ComponentInstallationSnapshot：active/previous 不可变版本目录及 receipt 的只读视图。
- RuntimeComponentActivation：把 Games 已校验、已安全解压的 immutable version directory 发布到 Host launcher runtime 的请求；Host 不取得源目录所有权。
- GameDocumentTree：SAF DocumentsContract 的只读、Provider 无关目录树边界。
- GameScanResult：有界扫描计数、命中限制和排序后 ExecutableCandidate 的不可变快照。
- ExecutableCandidate：通过 DOS/PE 签名校验的相对 EXE 路径、工作目录、评分和识别依据。
- GameLibraryItem：持久 Game 与当次加载 `GameAccessState` 的只读组合，不缓存 Provider 事实。
- GameArtworkReference：`local-games://artwork/<game-id>` 稳定引用，只解析到 app 私有封面目录。
- RuntimeProfilePreset：推荐、稳定、兼容和自定义四种可解释的游戏级配置来源。
- LegacyRuntimeConfiguration：主 app 通过 Host 暴露的 TermuxBox container 只读快照。
- LaunchPreflightResult：目录、runtime、组件 receipt 和存储预算组合出的启动门禁结果。
- ComponentRequirement：profile 解析出的组件 id、期望版本、SHA-256 和下载大小。
- StorageBudget：新 prefix 保留量与缺失组件安装峰值相加后的 required/available bytes。
- LaunchTask：一次从预检到清理的持久运行任务。
- LaunchSpec：按 task-id 冻结 Game、RuntimeProfile、canonical 游戏根目录、argv 和私有运行路径的不可变启动输入。
- LaunchEvent：启动脚本输出的严格 JSONL 状态记录，以 task-id 和单调 sequence 驱动 LaunchTask。
- LaunchExecutionMode：一次启动使用后台 AppShell 或命名 TerminalSession；旧配置默认 AppShell。
- AppExperienceMode：App 冷启动时冻结的 Terminal/Games 交互模式，只决定根页面和 X11 UI 所有权。
- LaunchRequest：Games Host 的私有文件执行请求；主 app 按冻结模式选择已有 Termux runner。
- GameSession：X11 首帧出现后到安全退出完成前的可操作会话。
- GameAssetInventory：只统计 game-id 可证明拥有的 app 私有 prefix、缓存、诊断、快照和配置；外部内容仅标记受保护。
- SaveSnapshot：配置与 app 私有 prefix 的不可变本地恢复点。
- GameUninstallPlan：外部内容固定 KEEP、其他私有分类需显式选择的卸载意图。

## Owner 边界

- UI Owner：`LocalGamesActivity` 及页面，只负责用户意图和状态展示。
- Task Owner：Orchestrator Service，负责任务状态、恢复、取消和清理。
- Execution Owner：Termux adapter 与 shell，负责进程和运行环境。
- Display/Input Owner：Termux:X11 runtime，负责 Surface、输入和控制器。
- App Experience Owner：主 `app` 持久化 `AppExperienceMode` 并执行冷重启；`:games` 仅通过 Host 查询或请求切换。
- Component Owner：组件仓库与下载服务，负责版本、partial、校验和原子安装。
- Component Task Owner：模块私有前台 Service，负责命令串行化、通知和进程重建恢复。
- Integration Owner：主 `app` 的 `LocalGamesHost` adapter，只负责把 app 内 TermuxService 能力提供给 `:games`。
- Import Owner：`GameImportActivity` 只编排授权、扫描、用户确认和 Game 保存；目录读取由 SAF adapter 执行，规则由纯 Java scanner 执行。
- Library Owner：`LocalGamesActivity`/`GameDetailActivity` 只按 game-id 加载、编辑和展示；`GameLibraryRepository` 聚合实时权限，`GameArtworkStore` 拥有私有封面事务。
- Runtime Profile Owner：`GameRuntimeProfileActivity` 只编辑和展示；`FileRuntimeProfileRepository` 拥有当前/上次成功快照，`LaunchPreflightEvaluator` 拥有启动门禁规则。
- Runtime Backend Owner：`GameRuntimeBackendRegistry` 按冻结类型选择唯一后端；GLIBC 与 RootFS 实现不得互相回退或共享 Prefix。
- Legacy Mapping Owner：主 app Host 只生成 detached snapshot，Games mapper 只在用户显式选择后转换，不写回 TermuxBox。
- Launch Task Owner：`LocalGameOrchestratorService` 在 Host 调用前持久化 task/spec，重放事件并处理恢复、取消和超时。
- Launch Script Owner：`start_local_game.sh` 拥有 Wine 子进程、task/global lock 和清理；Android 不解析终端屏幕文本。
- Asset Recovery Owner：`GameSnapshotManager` 拥有校验、事务恢复和中断清理；`GameUninstaller` 只删除 game-id 可证明拥有的私有路径。

## 模块边界

- `:games` 拥有产品 UI、Activity、领域、数据、组件和任务编排。
- `:app` 依赖并初始化 `:games`，提供 Termux 执行 Host。
- `:games` 可以依赖 `termux-shared` 和 `termux-x11`，但不得依赖 `:app`。
- 只有 `com.termux.localgames.api` 是主应用可依赖的稳定接口。

## 不变量

1. Activity 销毁不得等价于游戏任务结束。
2. 一个 task-id 最多对应一个有效游戏进程组。
3. 未通过大小和摘要校验的组件不得进入已安装状态。
4. 组件安装失败不得破坏上一可用版本。
5. 终端显示内容不是任务状态事实源。
6. 游戏卸载默认不删除外部游戏目录和存档。
7. RUNNING 必须同时具备有效游戏进程和可操作 X11 会话证据。
8. 任务进入终态后必须完成或明确记录未完成的清理动作。
9. `:games` 不得引用 `com.termux.app.*`，主应用能力必须经过公开 Host 契约。
10. 组件索引必须来自 `:games`，但合并后的 asset 路径保持 `termux-box-packages/index-v1.json`。
11. 组件归档必须在 app 私有 staging 中完成路径和链接检查，写入 receipt 后才能发布版本目录。
12. active pointer 只引用不可变版本目录；切换失败保留旧 active，rollback 交换 active/previous。
13. 仅 QUEUED、DOWNLOADING、VERIFYING、VERIFIED、INSTALLING 自动恢复；FAILED 需 retry，PAUSED 需 resume。
14. 组件页面只从索引、持久任务 Repository 和安装指针重建状态，不以 View 或 Service 绑定状态为事实源。
15. Activity 只能通过 `ComponentTasks` 提交 enqueue、pause、resume、retry、cancel 和 rollback，不得直接下载、校验或安装。
16. 当前 active 已匹配索引版本时，历史 PAUSED/FAILED 任务不得把页面状态降级为暂停或失败。
17. 游戏导入不得申请全盘存储权限、复制或修改用户游戏目录，只保存 tree URI 和安全相对路径。
18. `.exe` 扩展名不能替代 DOS/PE 签名校验；候选评分只能排序，不能替代用户确认。
19. 扫描必须受深度、文档数、目录数、候选数和声明总大小上限约束，并暴露结果不完整状态。
20. Game Repository 必须再次校验 executable/workingDirectory，拒绝绝对路径、盘符、反斜杠和 `..` 穿越。
21. 启动参数以独立字符串列表持久化；Windows 路径中的普通反斜杠不得在导入解析时丢失。
22. `GameAccessState` 必须由 persisted URI permission 和当前 Provider 可读性派生，不得写回 Game 形成过期事实。
23. Game 的非空 artworkUri 必须是同 game-id 的私有 artwork reference；不得引用另一游戏封面或任意文件路径。
24. 封面导入限制 20 MiB 和 16384 最大边长，列表/详情按目标尺寸采样，替换或移除失败恢复旧封面。
25. 默认从游戏库删除只删除 Game properties 和私有封面；只有用户显式选择的 app 私有分类可额外删除，不得删除、移动、重命名或写入 SAF tree。
26. Activity 停止只能移除定时刷新，不能移除用于释放 in-flight 标记的完成 callback，否则返回页面后可能永久停止刷新。
27. 每个 RuntimeProfile id 固定等于 game-id；不存在配置时只返回推荐默认值，用户保存前不得隐式落盘。
28. 当前 profile 与 last-success profile 是独立私有文件；恢复动作只更新表单，用户保存后才能改变当前配置。
29. TermuxBox container 只能通过 `LegacyRuntimeConfiguration` 只读映射；不得调用保存、切换当前 container、删除或写 prefix。
30. 推荐、稳定、兼容预设必须产生确定值；自定义预设不得修改输入，diff 按固定字段及排序后的 map key 输出。
31. LaunchTask 创建前必须重新检查 SAF、Host runtime、组件 index/active receipt 和私有存储，UI 的历史预检结果不能作为启动凭据。
32. RootFS 组件 active 的版本与 SHA-256 必须同时匹配 index/profile；GLIBC 组件必须同时通过 Host runtime capability probe。未知、缺失、版本不可用、receipt 不匹配或未激活均阻断启动。
33. 新 prefix 至少保留 1 GiB；缺失或不匹配组件按压缩大小三倍计入下载、staging 和安装峰值预算，计算必须防溢出。
34. LaunchTask 和 LaunchSpec 必须在调用 Host 前持久化；同 game 的非终态 task-id 必须复用。
35. LaunchTask 的事件 sequence、stage 和 progress 只能单调前进；终态不可变，重复事件幂等忽略。
36. LaunchSpec 文本使用 Base64 行协议；shell 不得 source、eval 或拼接用户路径和参数，每个 argument 保持独立 argv。
37. SAF tree URI 不得直接传给 Wine；Host 只能返回已验证的 canonical POSIX 目录，无法映射时阻断启动。
38. Games 默认使用 Termux AppShell；用户可按游戏选择命名 TerminalSession，但 Host 不得强制打开 TermuxActivity。
39. 脚本事件最多报告 WAITING_FIRST_FRAME；只有后续 X11 首帧证据可以把 LaunchStage 置为 RUNNING。
40. LaunchTask schema v3 持久化 displayConnected、displayUpdatedAt 和 firstFrameAt；显示证据不占用 JSONL sequence。
41. renderer 首帧必须来自当前 Android Surface 的 root buffer 绘制及 eglSwapBuffers 成功，Surface 创建或 socket 连接不等价于首帧。
42. LaunchSpec schema v2 冻结 xinput/dinput mapper 和可选数字触控 profile，GameSession 不读取启动后变化的 RuntimeProfile。
43. GameSessionActivity 重建、后台和普通销毁不终止游戏；只有显式确认安全退出才写取消标记。
44. 强制清理全局 Wine lock 前必须核对 lock PID 等于该 LaunchTask PID 且进程已消失。
45. RuntimeProfile schema v2 持久化 LaunchExecutionMode；v1 只读迁移必须默认 APP_SHELL。
46. LaunchSpec schema v3 冻结 LaunchExecutionMode；v1/v2 只读迁移必须默认 APP_SHELL。
47. APP_SHELL 与 TERMINAL_SESSION 必须共享脚本、JSONL、PID、cancel marker 和清理链。
48. TERMINAL_SESSION 只能旁路展示日志文件，不得接管 Wine 子进程 PID 或任务状态事实源。
49. 资产扫描、快照和卸载不得遍历 SAF tree；外部游戏文件和存档固定为受保护内容。
50. 快照发布前必须完成有界复制和确定性 SHA-256；恢复校验失败不得改变 active prefix/config。
51. prefix/config 恢复必须持久化 prepared/committed 事实；重启后 prepared 回滚旧版本，committed 保留新版本。
52. 非终态 LaunchTask 阻止快照和卸载；LaunchTask 创建必须与资产变更使用同一进程内协调锁。
53. 无 AppExperienceMode 或非法值必须回退 TERMINAL，保持既有 TermuxActivity 内嵌 X11 行为。
54. GAMES 模式的 Launcher 根页面必须是 LocalGamesActivity；纯终端 TermuxActivity 不得注册 X11 Host、恢复 X11 Fragment 或挂载 Display Surface，左侧栏只保留 session 管理与终端输入动作。
55. GAMES 模式只有 GameSessionActivity 可以持有游戏 X11 UI；TerminalSession 仅提供进程日志可见性。
56. AppExperienceMode 与 LaunchExecutionMode 正交；任一模式不得隐式修改另一模式。
57. AppExperienceMode 变更必须在用户确认后冷重启主进程，不得在已初始化 X11 runtime 的进程中热切换。
58. RuntimeProfile schema v3 和 LaunchSpec schema v4 冻结 GameRuntimeBackendType；旧 schema 必须默认 `GLIBC_TERMUX_BOX`。
59. `ROOTFS_PROOT` 必须提供非空 RootFS recipe id；`GLIBC_TERMUX_BOX` 禁止携带 RootFS 字段。
60. RootFS canonical path 必须位于 Termux 私有 `$PREFIX/var/lib/proot-distro/containers/<name>/rootfs`，且 `<name>` 来自 Games active receipt；不得接受环境变量、公共存储或任意绝对路径。
61. GLIBC 与 RootFS Prefix 必须按 backend 隔离；运行中预检或启动失败不得切换后端重试。
62. AppExperienceMode 变更仍需冷重启；GameRuntimeBackendType 是每游戏配置，修改后不要求重启 App。
63. 预装 Games 运行时的完整 RootFS 不作为下载组件；Hangover 等上游源输入先经 Games 下载器断点续传和 SHA-256 校验，再由 Termux AppShell 安装 Debian 基础容器并在 guest 内通过 `apt/dpkg` 组装。
64. 构建结果必须通过 `etc/games-runtime.properties` 声明能力；只有校验通过的版本化容器才能原子激活并进入 LaunchSpec。
64. GLIBC 后端不得将 Vortek、Gladio 等未实现 renderer 映射为 Turnip；未实现能力必须稳定失败。
65. RootFS manifest schema v2 必须声明 `runtimeBackend=rootfs_proot`、`architecture=aarch64` 及 runtime/graphics/DX/audio capabilities。
66. RootFS 基线为 Debian 13 + Hangover 11.9 + WineD3D，并显式区分 VirGL 与 llvmpipe；VirGL 缺少可执行 host server 时必须预检阻断，llvmpipe 不得伪装为 VirGL。Turnip/DXVK 只有完成 KGSL ABI、Vulkan 和真机验证后才可写入 capabilities。
67. RootFS 游戏目录与 Prefix 只能绑定到固定 guest 路径 `/mnt/games/game`、`/mnt/games/prefix`；镜像缺少挂载点必须在 Host 提交前阻断。
68. 启动脚本必须记录 PulseAudio 所有权；退出只终止当前任务创建的 daemon，并同时清理任务拥有的 X11、Wine、PRoot 和 GPU bridge 进程。
69. 组件索引 schema v2 必须严格声明 type、displayName、versionName、summary、framework、base、recommended、profileValue 和 runtimeBackends；parser 仍须兼容 schema v1。
70. 组件产品类型和展示元数据不得改变 package id、版本码、URL、大小、SHA-256、task、receipt 或 active/previous 指针。
71. 缺少可验证交付资源或运行后端接入的 GameHub 槽位不得进入下载状态机；UI 只能将其标记为当前未提供。
72. `prefix-apps` 等单一归档在没有成员 manifest 和独立安装边界前不得拆成 Mono、Gecko、字体、PhysX 等虚假任务。
73. GLIBC RuntimeProfile 的 Wine、GPU 和 DirectX 选项必须来自同一组件索引并按 runtimeBackends 过滤；目录外旧值保留显示但阻止保存。
74. Games private active receipt 只证明组件资产已校验并可复用，不等价于 `$PREFIX/glibc` launcher runtime 可用。
75. GLIBC 组件页和 preflight 必须以 Host runtime capability probe 为准；RootFS source component 继续以 Games immutable active receipt 为准。
76. Runtime 激活必须消费 Games prepared directory，不得重新解包或删除 Games 持有的 archive/version directory。
77. 升级前 private active 已存在但 Host probe 不可用时，UI 必须提供无需重新下载的显式激活动作。
78. GLIBC prefix provisioning 与两个 runtime 的 game launch 都必须先由 Host 幂等准备 `termux-x11` launcher/loader；不得依赖终端集成 X11 的设置动作。
79. 干净 Termux bootstrap 不得隐式要求 p7zip；前缀 overlay 必须由已校验组件中的预展开目录提供，旧归档回退缺工具时明确失败。
