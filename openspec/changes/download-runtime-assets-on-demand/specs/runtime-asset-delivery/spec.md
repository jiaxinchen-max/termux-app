# 运行时资产交付规格

## Purpose

为桌面主题与 Android API 配套命令提供可独立维护的组件资产交付，使 APK 只携带可信目录和宿主功能，并在初始化时正确处理架构匹配、网络故障、离线缓存和可恢复安装，控制包体而不损坏用户已有环境。

## ADDED Requirements

### Requirement: APK 与运行时资源分离
系统 MUST 将 Hyprland 主题归档、重复字体以及 Android API CLI/deb 配套资源放在组件资产库；APK 只携带对应可信元数据和必要安装逻辑，保留 Android API 宿主与现有 USB 能力。

#### Scenario: 检查 APK 内容
- **WHEN** 检查新构建的 ARM64 APK
- **THEN** 不包含主题归档、旧独立主题字体、API CLI/deb 二进制或 UPower deb，仍包含匹配宿主与安装目录。

### Requirement: 构建配套身份与架构
系统 MUST 保持 Linux 下 API CLI/deb 随 App 构建和校验，按明确版本与 ABI 在资产库发布。App 只能获取与当前 APK 契约及架构匹配的固定版本，不使用动态 latest 或其他架构回退。

#### Scenario: 当前架构安装
- **WHEN** ARM64 App 初始化 Android API 配套资源
- **THEN** 只获取已登记 ARM64 组件，并验证固定版本、大小、SHA-256 和文件清单；错误版本或 ABI 被拒绝。

#### Scenario: 构建环境不匹配
- **WHEN** API CLI/deb 需要重新构建但没有可用 Linux 环境
- **THEN** 明确报告构建不可用，不编造产物；Hyprland/KWin 原生包构建仍遵循指定编译机边界。

### Requirement: 按需下载与离线复用
系统 MUST 仅在用户明确执行初始化、安装、修复或主题切换时下载缺失组件。已校验且身份匹配的本地组件可离线复用，普通桌面启动不新增联网步骤。

#### Scenario: 首次安装所选主题
- **WHEN** 用户安装缺少本地缓存的 m3ww 主题
- **THEN** 从资产库获取 m3ww 包，不为此下载 classic 主题或未变化合成器。

#### Scenario: 离线重复使用
- **WHEN** 已存在有效的目标主题或 API 组件且设备离线
- **THEN** 复用本地有效内容，不强制重新下载，既有 API 命令及桌面可继续使用。

### Requirement: 下载失败与初始化状态分离
系统 MUST 在断网、404、超时、校验错误、磁盘不足或取消时清理临时文件，保留原可用组件和用户配置，给出可重试错误，不写完整成功状态。基础终端完成与 API 配套资源完成必须分开记录。

#### Scenario: 终端初始化后 API 下载失败
- **WHEN** 基础终端已初始化但首次 API 组件获取失败
- **THEN** 保留基础终端，显示 API 待完成及重试入口；下次明确重试不能因为 prefix 已存在就跳过 API 初始化。

#### Scenario: 主题切换失败
- **WHEN** 新主题下载中断或哈希不符
- **THEN** 原主题与当前选择保持可用，失败候选不激活，用户可重试。

### Requirement: 安装边界与可恢复激活
系统 MUST 只激活经过完整验证的候选资源，拒绝越界路径、链接、重复成员和超限内容，并处理并发安装与中断恢复；新旧安装不可混合成成功状态。

#### Scenario: 恶意或残缺组件
- **WHEN** 资源包含路径越界、未登记文件或安装在中途失败
- **THEN** 不写越界文件，不遗留半激活版本；旧版本保留或恢复，并报告失败。

### Requirement: 发布与包体可核验
系统 MUST 同步 App 与资产库的组件身份、URL、大小、SHA 和许可，发布后验证匿名获取的真实字节；APK 名称保持 termux-app_apt-android-7-debug_arm64-v8a.apk，交付记录列出实际体积及差异。

#### Scenario: 对外交付
- **WHEN** 用户授权新组件发布并生成新 APK
- **THEN** 公开资产与 App 固定目录一致，APK 中没有外置资源的重复副本；记录真实校验和与体积，不承诺未构建的精简数字。
