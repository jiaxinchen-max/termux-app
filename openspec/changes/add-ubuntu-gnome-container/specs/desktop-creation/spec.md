## ADDED Requirements

### Requirement: 按卡片自动选择受支持底包
系统 MUST 支持 Debian 与 Ubuntu 两种底包，创建界面只展示受支持桌面卡片，按桌面配置选择底包，不展示发行版选择或未实现占位。

#### Scenario: 创建容器桌面
- **WHEN** 用户打开容器创建界面
- **THEN** 展示 XFCE、Plasma、GNOME、Hyprland、Ubuntu GNOME；前四者使用对应 Debian 底包，Ubuntu GNOME 使用 Ubuntu noble ARM64。

#### Scenario: 创建原生桌面
- **WHEN** 用户打开原生创建界面
- **THEN** 仅显示尚可创建的现有四种原生桌面，不显示 Ubuntu GNOME。

### Requirement: 真实 Ubuntu GNOME 会话
系统 MUST 使用 Ubuntu 官方发行版及 Ubuntu 会话、Yaru 和扩展实现 Ubuntu GNOME。

#### Scenario: 启动 Ubuntu GNOME
- **WHEN** 安装成功并启动 Ubuntu GNOME
- **THEN** rootfs 标识为 ubuntu:noble，Shell 使用 Ubuntu mode，桌面呈现 Yaru 外观、Ubuntu Dock、状态区、桌面图标和可启动的文件管理器；用户可以启动已安装 apt 应用。

#### Scenario: 不完整或错误底包
- **WHEN** 真实发行版或版本不匹配，或必要 Shell/session/主题/扩展文件缺失
- **THEN** 明确报告错误，不使用 Debian 重命名替代，不写成功或就绪标记。

### Requirement: 独立渲染与组件隔离
系统 MUST 保持宿主与容器配置独立，Ubuntu 首版只允许宿主 System EGL、容器 llvmpipe。

#### Scenario: Ubuntu 能力选项
- **WHEN** 用户创建或设置 Ubuntu GNOME
- **THEN** 显示真实的软件渲染说明，不开放未验证的 Ubuntu Zink、USB 桥接或备份恢复，不安装 trixie 专用组件。

#### Scenario: 拒绝无效输入
- **WHEN** 外部配置或规格请求 Ubuntu 不支持的组合、底包或功能
- **THEN** 在启动部分链路之前明确拒绝，不自动回退，不停止其他桌面。

### Requirement: 生命周期与当前格式
系统 MUST 沿用当前双层配置格式与受控会话隔离，不兼容旧数据，不影响已有 Debian 桌面。

#### Scenario: 停止并重新启动
- **WHEN** 用户停止、重启或删除 Ubuntu 桌面
- **THEN** 仅清理对应会话归属资源，停止后可重新启动，用户配置保持，其他实例及 r10 渲染选择不受影响。

#### Scenario: 旧格式输入
- **WHEN** 输入旧配置、旧归档或旧启动规格
- **THEN** 明确拒绝，不迁移、不猜测、不主动删除文件。

## Ubuntu安装缺陷和基础应用（用户已确认）
实际desktop-61adcfe6安装日志显示fonts-ubuntu-classic与fonts-ubuntu互斥，apt退出100。删除清单中的classic包，保留noble默认fonts-ubuntu；不使用忽略依赖或破坏dpkg状态的方式。用户要求检查容器安装失败并补齐桌面应用，修复沿用既有安装链路授权；Ubuntu容器同时显式安装gnome-control-center、计算器、时钟、eog，因--no-install-recommends不能依赖推荐包。设置程序和真实桌面入口须纳入文件检查和会话激活。验证真实noble apt解析与安装结果，失败不写marker；既有Debian路径不变。回滚仅恢复pre-apps-container-fix.tar.gz本轮文件，保留rootfs和用户数据；无旧数据迁移。无HTTP契约，产品知识保持Required/Pending。

## 已授权安装/会话修复中的DING排障
最终APK实测容器完整安装成功，但Ubuntu DING47读取/proc/asound/cmdline被Android拒绝，必选扩展失败导致会话退出。沿用当前容器安装、会话和单会话清理授权，安装后对固定Noble DING源码做SHA256识别和原子补丁，移除全局/proc扫描，子进程由现有会话进程组清理。未知源码或文件不完整明确失败，不写成功marker；启动只验证已修复的源码，不自动迁移旧数据。补幂等、未知版本拒绝和marker故障回归，重编正常APK并在当前模拟器验证。回滚仅恢复本轮脚本及配方快照；组件旧发布不覆盖。无HTTP契约，产品文档仍Pending。
