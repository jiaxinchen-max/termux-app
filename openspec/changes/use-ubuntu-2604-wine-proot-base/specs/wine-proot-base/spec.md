# Wine PRoot Ubuntu 底包规范（已批准，实施中）

## ADDED Requirements

### Requirement: 统一 Ubuntu 26.04 来源

系统 MUST 对新建及显式重建的 Wine PRoot 使用 Ubuntu 26.04 LTS / Resolute，发行版与 Desktop PRoot 一致，并保持独立裁剪镜像。系统 MUST 固定可校验来源，不得回退 Debian、混装其基础系统库或仅修改发行版标签。

#### Scenario: 全新基础环境
- 当用户安装新的 Wine PRoot 基础环境时
- 则安装的系统身份为 Ubuntu / 26.04 / resolute，来源、配方和镜像摘要一致，桌面容器根目录不受影响。

### Requirement: 预装镜像及前缀真实兼容

系统 MUST 在离线制品准备阶段完成客体依赖和裁剪，并验证 Hangover、Wine/Box64 与 Proton/Box64 的运行库及前缀来源。设备安装及启动不得以执行客体 apt、首次完整 wineboot 或静默更换 Wine 版本来兜底。

#### Scenario: 不兼容来源
- 当运行库 ABI 或某类模板来源校验失败时
- 则制品验收失败，不更改默认来源，不通过放宽哈希或重写就绪标记伪造成功。

### Requirement: 仅支持新配方

系统 MUST 使用新的 Ubuntu 配方、镜像和模板身份，不实现历史 Debian 容器、前缀或备份的兼容转换，不将旧身份映射为新身份。系统 MUST 保持现有共享底包存储结构，不增加发行版并存管理。

#### Scenario: 接收旧身份
- 当新版收到旧配方任务或Debian备份时
- 则明确拒绝并提示重建，不静默复用旧模板或通过修改来源摘要伪造兼容。

### Requirement: 停止会话后原子重建

系统 MUST 在重建前停止受影响 Wine 会话并确认进程退出，使用安全暂存、完整校验及原子发布。失败或取消 MUST 不发布新的就绪状态，保留可回滚根与前缀。

#### Scenario: 停止失败或安装失败
- 当进程无法停止、摘要错误、空间不足、还原越界或任务取消时
- 则安装明确失败，保留错误日志，不替换正在使用的根、不删除无关游戏 C 盘，也不留下可用假状态。

### Requirement: 发布和命名可追踪

系统 MUST 使用真实存在且字节数和摘要可校验的版本化制品，在正式启用默认前完成验证。APK 文件名 MUST 保持 `termux-app_apt-android-7-debug_arm64-v8a.apk`；不得重命名或覆盖旧公开制品。

#### Scenario: 候选尚未发布
- 当候选镜像仍处于本地验证、没有正式发布授权或地址时
- 则不启用虚构的生产下载地址，也不宣称新装链路已交付。
