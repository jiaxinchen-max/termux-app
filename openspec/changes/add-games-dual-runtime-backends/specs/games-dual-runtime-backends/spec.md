## ADDED Requirements

### Requirement: 每个游戏冻结独立运行后端

系统 MUST（必须）将游戏运行后端保存于 RuntimeProfile，并冻结到 LaunchSpec。

#### Scenario: 旧配置迁移

- **WHEN** 读取未包含运行后端的旧 RuntimeProfile 或 LaunchSpec
- **THEN** 系统将其解释为 `GLIBC_TERMUX_BOX`
- **AND** 不修改旧 TermuxBox 配置或共享 Prefix

#### Scenario: RootFS 配置启动

- **WHEN** 游戏 profile 选择 `ROOTFS_PROOT`
- **THEN** LaunchSpec 冻结 RootFS 包和已激活 immutable RootFS 路径
- **AND** Host 只提交 RootFS 后端的独立脚本
- **AND** 游戏目录与 Prefix 绑定到固定 guest 路径，不要求 RootFS 存在宿主 `/storage/...` 目录层级

### Requirement: 后端实现互不侵入

系统 MUST（必须）通过 registry/SPI 选择后端，Activity 和共享状态机 MUST NOT 直接依赖 TermuxBox 或 PRoot 实现。

#### Scenario: 选择后端

- **WHEN** Orchestrator 准备一个启动任务
- **THEN** 组件需求、预检、路径解析和脚本选择均由同一个已冻结后端提供
- **AND** 任一步失败时不得切换到另一后端重试

### Requirement: RootFS 来源可校验和回滚

RootFS MUST（必须）由 Termux App 内部安装基础容器并在 guest 内完成 provisioning，再通过 Games active pointer 解析；不得下载预装 Games 运行时的完整 RootFS 成品，也不得从环境变量或公共可写路径接受根目录。

#### Scenario: RootFS active pointer 无效

- **WHEN** pointer、receipt、容器名或 Termux 私有 `rootfs/` 内容根无效
- **THEN** 启动在 Host 调用前以稳定 preflight 错误阻断
- **AND** 不执行 PRoot

#### Scenario: RootFS 能力不匹配

- **WHEN** active RootFS 的 `etc/games-runtime.properties` 未声明 profile 选择的运行器、图形或 DX 能力
- **THEN** 启动在 LaunchSpec 持久化前阻断
- **AND** 不把未挂载的分层组件视为可用能力

#### Scenario: VirGL host bridge 缺失

- **WHEN** profile 选择 `rootfs-virgl-mesa`，但当前 ABI 没有可执行 Android VirGL server
- **THEN** 启动在 LaunchSpec 持久化前报告稳定组件缺失
- **AND** 不得静默切换到 llvmpipe、Turnip 或 GLIBC 后端

#### Scenario: 显式软件渲染

- **WHEN** profile 选择 `rootfs-llvmpipe` 且 active manifest 声明该能力
- **THEN** RootFS 启动器设置软件 Mesa 环境且不启动 VirGL server
- **AND** 仍复用同一 X11、音频、输入和 LaunchTask 事件链

#### Scenario: 显式退出清理任务拥有的音频

- **WHEN** GLIBC 或 RootFS 启动器为当前 LaunchTask 创建 PulseAudio daemon，随后用户确认退出
- **THEN** 启动器必须终止该 daemon 以及当前任务的 X11、Wine 和运行容器进程
- **AND** 若启动前已存在可复用 PulseAudio daemon，则不得将其标记为当前任务拥有或在退出时终止

### Requirement: 产品模式与运行后端正交

系统 MUST（必须）保持 `AppExperienceMode` 和 `GameRuntimeBackendType` 相互独立。

#### Scenario: 修改游戏后端

- **WHEN** 用户仅修改某个游戏的运行后端
- **THEN** 其他游戏和 App 顶层交互模式保持不变
- **AND** 不要求冷重启 App

### Requirement: 两个运行后端均为正式能力

系统 MUST（必须）同时注册并保留 `GLIBC_TERMUX_BOX` 与 `ROOTFS_PROOT`，不得因其中一个后端缺少组件而隐藏、替换或自动回退到另一后端。

#### Scenario: RootFS 组件尚未安装

- **WHEN** 用户为游戏选择 `ROOTFS_PROOT` 且对应 RootFS 尚未安装
- **THEN** 系统保留该 profile 并报告可恢复的组件缺失
- **AND** 不改写为 `GLIBC_TERMUX_BOX`

#### Scenario: 未实现的渲染器

- **WHEN** profile 选择 Vortek、Gladio 或其他未接入当前后端的 renderer
- **THEN** preflight 返回稳定的 unsupported selection
- **AND** 不得通过映射到 Turnip 伪装为可用

### Requirement: GLIBC Prefix 在启动前完成初始化

系统 MUST（必须）在保存 GLIBC RuntimeProfile 后通过持久前台任务初始化游戏独占 Wine Prefix；游戏启动器不得执行 `wineboot` 或隐式重建 Prefix。

#### Scenario: 保存 GLIBC profile

- **WHEN** 用户保存后端为 `GLIBC_TERMUX_BOX` 的 RuntimeProfile
- **THEN** 系统创建可恢复的 PrefixProvisionTask，并通过 Termux AppShell 执行 Mobox 兼容初始化顺序
- **AND** task、JSONL 事件和日志在 Activity 销毁或进程恢复后仍可对账

#### Scenario: Prefix 尚未就绪

- **WHEN** 启动时 `.termux-box-bootstrap-done` 不存在或记录的 Wine 包与 profile 不一致
- **THEN** preflight/launcher 返回稳定的 `prefix_provision_required` 或 `prefix_runtime_mismatch`
- **AND** 启动器不得运行 `wineboot`、删除 Prefix 或切换运行后端

#### Scenario: AVD wineserver 未自行结束

- **WHEN** `wineboot` 已生成 `.update-timestamp` 且 registry 文件稳定，但 wineserver 仍未退出
- **THEN** bootstrap 向该 Prefix 的 wineserver 发送正常关闭请求并继续 Mobox 后处理
- **AND** Wine 命令退出码不得覆盖有效 Prefix marker 的判定

### Requirement: RootFS 设备内 provisioning 可恢复

系统 MUST（必须）通过 Termux package/PRoot-Distro 生态在设备内安装基础容器并执行 guest provisioning，并将下载、安装、校验和激活状态持久化。

#### Scenario: Provision RootFS runtime

- **WHEN** 安装 Debian 13 + Hangover RootFS
- **THEN** Hangover 源组件通过 Games 下载器断点续传并校验 size/SHA-256
- **AND** Termux AppShell 调用 `proot-distro install` 下载基础容器，并通过 `proot-distro login` 在容器内执行固定 recipe，无需 Dockerfile 或 Docker daemon
- **AND** provisioning 结果 manifest 声明架构、基础镜像、snapshot、运行器、图形、DX 和音频能力
- **AND** 校验完成后才原子替换 active pointer，并保留 previous 用于回滚
