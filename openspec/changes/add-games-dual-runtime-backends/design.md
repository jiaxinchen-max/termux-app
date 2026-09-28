# Games 双运行后端设计

## 总体方案

```text
LocalGameOrchestratorService
  -> GameRuntimeBackendRegistry
       -> GlibcTermuxBoxBackend
       -> RootfsProotBackend
  -> backend.preflight(...)
  -> backend.createLaunchSpec(...)
  -> backend.installLauncher(...)
  -> LocalGamesHost.startLaunch(...)

RootfsProvisionForegroundService
  -> Games component downloader (resume + SHA-256)
  -> LocalGamesHost.startRuntimeProvision(...)
  -> TermuxService AppShell
  -> pkg install proot-distro
  -> proot-distro install Debian base container
  -> proot-distro login + guest provisioning
  -> Games active/previous runtime metadata
```

共享层只持有 Game、RuntimeProfile、LaunchTask、LaunchSpec、组件任务和 JSONL 状态。后端负责运行时语义，不允许以 `if (backend)` 持续扩散到 Activity。

## Activity 与导航

本分片不改变导航。`GameRuntimeProfileActivity` 提供每游戏后端选择；选择 RootFS 时显示镜像组件字段并应用独立默认值。`AppExperienceMode` 与 `GameRuntimeBackendType` 是两个独立维度。

## Service 与任务状态

- Service 根据冻结 profile 从 registry 取得后端；未注册后端以 `runtime_backend_unsupported` 失败。
- 后端准备必须发生在写 LaunchSpec 前，禁止启动后隐式切换后端。
- `LaunchTask` 状态机、PID、取消和 JSONL 顺序规则保持不变。
- Host 仍只提交私有脚本和 spec；RootFS 进程树由对应脚本拥有并响应同一 cancel marker。

## 数据、存储与迁移

- `RuntimeProfile` schema v3 新增 `runtimeBackendType` 和 `rootfsPackage`。
- `LaunchSpec` schema v4 冻结 `runtimeBackendType`、`rootfsPackage` 和 `runtimeRootPath`。
- v1/v2 profile、v1-v3 LaunchSpec 默认迁移为 `GLIBC_TERMUX_BOX`，RootFS 字段为空。
- RootFS 激活元数据位于 `files/games/runtimes/rootfs/<package>/`；receipt 引用的容器名必须解析到 `$PREFIX/var/lib/proot-distro/containers/<name>/rootfs`。
- Prefix 继续按 game-id 隔离；不同后端使用独立子目录，避免 Wine Prefix ABI 互相覆盖。

## 组件下载与安装

- GLIBC 后端沿用当前 TermuxBox 索引和 base components。
- 预装 Games 运行时的完整 RootFS 不是下载组件。组件索引只包含可恢复下载的上游源输入，例如锁定 size/SHA-256 的 Hangover release tar。
- App 内置 guest provisioning recipe；持久 Service 通过 Termux AppShell 调用 `pkg install proot-distro`、`proot-distro install` 和 `proot-distro login`，不依赖 Dockerfile 或 Docker daemon。
- Debian 基础容器使用官方日期 tag；PRoot-Distro 校验 OCI layer digest，固定 Debian snapshot 和包签名约束 guest 依赖。
- RootFS 图形能力声明 `rootfs-virgl-mesa` 和 `rootfs-llvmpipe`，DX 能力只声明 `rootfs-wined3d`。VirGL 必须额外探测可执行 Android host server；缺失时预检阻断，不得静默降级。llvmpipe 是无 GPU bridge 环境的显式软件后端。未打包 Android KGSL Turnip 前不得声明 `rootfs-turnip` 或 `rootfs-dxvk`。
- 独立运行器/图形/DX 分层组件需要后续离线镜像合成器；在没有 union/overlay 组合和冻结路径协议前不得只校验不挂载。
- 源组件沿用下载 staging、断点续传、校验和 immutable install。容器 provisioning 另有持久 task、recipe staging、版本化容器和 active/previous pointer。
- App 不执行不受控 `apt upgrade`；recipe 使用固定 Debian snapshot。

## X11、输入与运行会话

- X11、输入和会话 Activity 属于共享 Host 层，不依赖具体 Wine 后端。
- RootFS 后端通过受限共享 tmp/X11 socket、PulseAudio endpoint 和 GPU bridge 与 Host 通信。
- 启动器仅停止自己创建的 PulseAudio daemon；复用既有 daemon 时不得越权终止。显式退出必须清理任务拥有的音频、X11、GPU bridge、Wine 和 PRoot 进程。
- 宿主游戏目录与 Prefix 分别绑定到固定 guest 路径 `/mnt/games/game`、`/mnt/games/prefix`，不依赖 RootFS 复刻 `/storage/...` 宿主目录层级。
- Adreno 快路径需要标准 glibc 打包的 Android KGSL Mesa；VirGL 需要匹配当前 ABI 的 Android host server；llvmpipe 只用于模拟器、无 GPU bridge 或兼容性诊断。Turnip 在完成 ABI 和 Termux:X11 真机验证后再加入 manifest。
- 本分片不修改 X11 控件，只保留协议字段和后端能力边界。

## 受保护路径

- `runtime-script-critical`：新增独立 RootFS 启动脚本并调整脚本安装选择；必须验证 schema、shell quoting、路径 containment、取消和清理。
- 不修改 `termux-service-critical`、`android-entry-critical`、`x11-input-critical` 和构建边界。

## 测试策略

- JVM：profile v1/v2/v3、LaunchSpec v1-v4 迁移；后端 registry；组件需求隔离；RootFS active pointer/path containment。
- shell：GLIBC v4 兼容；RootFS 缺少 PRoot/rootfs/wine 时稳定失败；特殊字符 argv 不经 eval。
- 编译：`:games:testDebugUnitTest`、`:games:assembleDebug`、`:app:compileDebugJavaWithJavac`。
- 设备矩阵：ARM64 4K AVD 验证 llvmpipe 首帧、返回键控制中心和退出清理；真机后续覆盖 4K/16K page、Adreno/Mali、Hangover/Box64、VirGL/Turnip。

## 风险与回滚

两个后端均保留在 registry 和 profile UI 中。RootFS 制品未安装时以可恢复 preflight 阻断，不隐藏后端或自动切换 GLIBC。任何 RootFS 失败不得修改 GLIBC Prefix、组件 active pointer 或旧 TermuxBox 容器。

## 双后端能力边界

- `GLIBC_TERMUX_BOX` 只接受已真实集成的 Turnip、VirGL、DXVK 和 WineD3D；Vortek/Gladio 不得映射为 Turnip。
- `ROOTFS_PROOT` 只接受 active RootFS manifest 实际声明的能力；默认 Hangover 版本与 manifest 一致。
- 两个后端共享 LaunchTask、X11 Host adapter、输入和 JSONL 事件协议，但不共享 Prefix、RootFS、Wine 或具体 renderer 实现。
