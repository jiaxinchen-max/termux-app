# Games 双运行后端提案

## 背景

现有 Games 启动链固定依赖 TermuxBox 的 glibc-prefix、Box64 和 x86_64 Wine。该路径性能明确，但所有运行组件均需适配 Termux/Android。另一类需求希望在标准 Debian/Ubuntu ARM64 RootFS 内直接使用发行版 Wine、Hangover、FEX、DXVK 和工具生态，Termux 只承担 Android Host、进程和桥接能力。

## 目标

- 将游戏运行实现拆为 `GLIBC_TERMUX_BOX` 与 `ROOTFS_PROOT` 两个独立后端。
- 保持当前 profile、LaunchSpec 和 GLIBC 启动数据向后兼容，默认行为不变。
- 后端分别拥有组件解析、预检、运行目录解析和启动脚本选择，不共享具体 shell 实现。
- RootFS 后端由 Termux App 通过 `proot-distro install` 下载基础容器，并在容器内执行版本化 provisioning；冻结容器路径后通过独立启动请求提交。
- 产品交互模式与游戏运行后端分离：前者仍需冷重启，后者为每游戏 profile 配置。
- 两个后端均作为正式能力持续维护；RootFS 后端不得因 GLIBC 后端可用而降级为不可选择骨架。
- 提供可复现的 Debian 13 + Hangover on-device provisioning recipe、持久任务和 active/previous 激活流程。

## 非目标

- 不发布或下载预装 Games 运行时的完整 RootFS 成品；Debian 基础容器由 PRoot-Distro 下载。
- 不在 PRoot 内运行 UMU/pressure-vessel、systemd、Docker 或其他依赖 namespace/cgroup 的服务。
- 不修改 TermuxService、TermuxActivity 和 termux-x11 实现。
- 不把 PRoot 描述为安全隔离边界。

## 用户可见行为

现有游戏继续使用 GLIBC TermuxBox 启动。RootFS 后端先通过 Games 下载器获取并校验 Hangover 上游源组件，再由 Termux `pkg` 安装 PRoot-Distro、下载 Debian 13 基础容器，并在容器内通过 Debian 包管理安装 Hangover 11.9。图形基线提供需要 host bridge 的 VirGL 和显式软件后端 llvmpipe，均使用 WineD3D；缺失项以稳定 preflight 错误阻断，不会回退或污染 GLIBC profile。

## 影响范围

修改 `:games` domain、持久 provisioning Service、profile/spec、后端 registry、预检、脚本和组件索引；主 app 仅扩展 `LocalGamesHost` adapter，不修改 TermuxService 和 X11。

## 风险与回滚

- schema 风险：profile v1/v2 和 LaunchSpec v1-v3 读取时默认映射到 `GLIBC_TERMUX_BOX`；新字段严格校验。
- 路径风险：Games active receipt 只能引用 Termux 私有前缀下的版本化 `proot-distro/containers/<name>/rootfs`。
- 性能风险：RootFS 后端明确标识为 PRoot，后续真机验证 syscall、Prefix 初始化和首帧开销。
- 回滚：移除 RootFS backend 注册和 profile/spec 新字段即可；已有 GLIBC 数据仍可读取，未触碰旧 TermuxBox 数据。
