# 设计

## 底包与目录
DesktopCreationCatalog 按运行方式分别提供候选项，不能仅把 UBUNTU_GNOME.isCreatable 改为 true 导致原生列表误展示。为每个桌面集中映射底包：XFCE/GNOME/Hyprland 为 debian:trixie，Plasma 为 debian:forky，Ubuntu GNOME 为 ubuntu:noble。创建时按所选卡片计算 alias（ubuntu-ubuntu-gnome-N）、默认值与启动规格；支持底包集合为 Debian/Ubuntu，UI 不显示选择。安装与启动均校验真实 os-release，不能仅依赖 alias。

## 官方 Ubuntu 会话与外观
Ubuntu noble 官方 ubuntu-session_46.0-1ubuntu4_all.deb 包含 ubuntu.session 与 ubuntu-xorg.desktop，后者指定 GNOME_SHELL_SESSION_MODE=ubuntu、--session=ubuntu、DesktopNames=ubuntu;GNOME;。来源 https://packages.ubuntu.com/noble/ubuntu-session；下载证据见 artifacts/ubuntu-gnome-planning/（工作区 artifacts 目录）。
容器按最小 apt 包清单安装 ubuntu-session、ubuntu-settings、gnome-shell、gnome-settings-daemon、nautilus、gvfs-backends、dbus-x11、Mesa 软件渲染及 Ubuntu 默认字体/背景、Yaru GTK/Shell/图标/声音主题、Ubuntu Dock、AppIndicator、DING 与 Ubuntu Tiling Assistant。逐一核对扩展 UUID、版本、session mode，显式验证必需文件；不依赖推荐包隐式安装，也不安装整个 ubuntu-desktop 元包以避免 GDM、Snap 等系统服务引入。许可证按各上游组件保留，不宣称可选专有驱动均开源。
复用受控 GNOME 进程管理，新增显式 session profile 参数，区分 Debian GNOME 与 Ubuntu GNOME。Ubuntu 设置 XDG_CURRENT_DESKTOP=ubuntu:GNOME、XDG_SESSION_DESKTOP=ubuntu、GNOME_SHELL_SESSION_MODE=ubuntu，采用 Ubuntu Shell mode，加载 Ubuntu schema 默认值、Yaru 主题与 Dock。不得继续强制 --mode=user 或 Adwaita 覆盖 Ubuntu。保留会话私有 D-Bus、配置目录与严格就绪检查；无系统服务的 PRoot 不直接照搬依赖登录管理器的完整 gnome-session 启动。按需管理 settings-daemon 的 XSettings 等无特权服务，真实检查 GTK 主题与设置生效。

## 渲染、组件与权限边界
DesktopRendererPolicy 为 Ubuntu 独立声明 SYSTEM/LLVMPIPE；规范、安装器和 Python 启动器一致校验。宿主 Xlorie 继续 System EGL，容器使用 noble 仓库 Mesa llvmpipe。拒绝 Ubuntu Zink、USB read-only，不执行 trixie Turnip/GIO 安装；不回退其他桌面或底包。宿主仅下载所需 Xlorie/公共依赖，不为容器 Ubuntu 安装 native GNOME Shell 二进制。当前 schema3 与版本3启动规格不增加旧数据适配。
Activity 负责卡片、两层设置及错误说明；Service/任务状态沿用当前任务调度，安装失败不得写成功 marker。DesktopEnvironment 校验会话、扩展、主题、Shell 必需文件，不把文件缺失视为可启动。desktop_install.sh 完成受控 apt/下载与发行版校验；desktop_start.sh 和 desktop_gnome_launch.py 管理宿主 X11、容器 Shell 与就绪；停止/删除仅处理该会话拥有的进程和目录。输入、音频沿用现有 GNOME 桥接，实际验证点击、键盘、分辨率与应用窗口。存储使用独立 proot 实例与配置目录；无用户数据迁移，无任意路径删除。

## 高风险路径与回滚
规则来源 /home/user/workspace/AGENTS.md 的 Protected path approval；checkout 无可发现的机器 policy id，记录为 Unavailable，不编造策略编号。拟修改 DesktopDe、DesktopCreationCatalog、DesktopRendererPolicy、DesktopLaunchSpec、DesktopEnvironment、DesktopTasks 相关能力校验，创建/设置/卡片文案资源，以及 desktop_rendering.sh、desktop_install.sh、desktop_start.sh、desktop_stop.sh、desktop_remove.sh、desktop_gnome_launch.py、desktop_ubuntu_gnome.py、desktop_container_plasma_apt.sh 和关联测试；仅按实际耦合修改。归档仅增加 Ubuntu 不支持的显式校验，如现有实现已正确拒绝则不改。
影响为新建底包、组件安装和会话生命周期；实施前保存相对 r10 工作树的快照，回滚仅撤销本变更，不丢弃未提交 r10 和 native 子模块状态。不改变现有桌面进程归属。不执行 Git stage/commit/push、部署或发布。

## 验证与交付边界
覆盖原生四卡片/容器五卡片、底包与 alias、默认渲染、未知组合拒绝、os-release 错配、Ubuntu 不触发 trixie 组件、Shell mode/env/theme/扩展契约、必要资产缺失、安装失败 marker、停止重启隔离以及现有 Debian 回归。构建并校验 ARM64 APK 内资产与摘要。真实设备验收必须包含 noble 身份、Ubuntu Shell mode、Dock/状态栏/桌面图标、Nautilus/apt 应用、输入、停止重启；fixture 不算设备通过。测试设备安装及运行作为独立部署动作，在执行前取得具体环境授权。
当前 baseline specs 与产品文档缺失，使用已有 change-local 桌面规范及实际代码对照并记录缺口；不把待实现行为写入 canonical 产品文档。完整 Harness 门禁缺失如实记录，用户验收前保持 Pending。

实施补充：Ubuntu验证终端、编辑器的D-Bus激活服务并加入该会话的受控激活清单；检查Dock等四扩展为Active、DING与XSettings持有会话总线名后才写就绪。安装器发现Ubuntu不完整marker时撤销marker并使用显式--reinstall修复，保留真实错误码；Debian调用不携带该新增选项。

## Ubuntu安装缺陷和基础应用（用户已确认）
实际desktop-61adcfe6安装日志显示fonts-ubuntu-classic与fonts-ubuntu互斥，apt退出100。删除清单中的classic包，保留noble默认fonts-ubuntu；不使用忽略依赖或破坏dpkg状态的方式。用户要求检查容器安装失败并补齐桌面应用，修复沿用既有安装链路授权；Ubuntu容器同时显式安装gnome-control-center、计算器、时钟、eog，因--no-install-recommends不能依赖推荐包。设置程序和真实桌面入口须纳入文件检查和会话激活。验证真实noble apt解析与安装结果，失败不写marker；既有Debian路径不变。回滚仅恢复pre-apps-container-fix.tar.gz本轮文件，保留rootfs和用户数据；无旧数据迁移。无HTTP契约，产品知识保持Required/Pending。

## 已授权安装/会话修复中的DING排障
最终APK实测容器完整安装成功，但Ubuntu DING47读取/proc/asound/cmdline被Android拒绝，必选扩展失败导致会话退出。沿用当前容器安装、会话和单会话清理授权，安装后对固定Noble DING源码做SHA256识别和原子补丁，移除全局/proc扫描，子进程由现有会话进程组清理。未知源码或文件不完整明确失败，不写成功marker；启动只验证已修复的源码，不自动迁移旧数据。补幂等、未知版本拒绝和marker故障回归，重编正常APK并在当前模拟器验证。回滚仅恢复本轮脚本及配方快照；组件旧发布不覆盖。无HTTP契约，产品文档仍Pending。
