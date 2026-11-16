# 设计

## 当前事实
DesktopCreationCatalog 将 Ubuntu GNOME 映射到 noble、Plasma 映射到 resolute，其余映射 trixie。desktop_install.sh 的 Hyprland 分支单独创建 debian:trixie，其他分支按桌面验证 os-release。desktop_ubuntu_gnome.py 显式校验 Ubuntu Noble、GNOME 46 扩展元数据与 DING 源码哈希，DesktopEnvironment 亦校验 noble。Mesa/Turnip 与 glibc GIO 资产包含 Debian Trixie 构建身份，不能假定与 Resolute ABI 相同。

## 目标目录与身份
所有可创建的 PRoot 桌面固定 ubuntu:resolute，codename resolute；安装后同时验证 ID=ubuntu 和 VERSION_CODENAME=resolute。列表为 Plasma、Ubuntu GNOME、XFCE、Hyprland 客户端。GNOME 枚举仍可供 Native 使用，但支持矩阵拒绝 PROOT+GNOME；UI 不提供该组合，直接脚本调用与归档恢复也拒绝。移除仅属于旧 PRoot GNOME 的分支；与 Native/Ubuntu GNOME 共用逻辑不得误删。

## 版本相关适配
先从 Resolute 实际软件源确认 GNOME/Ubuntu session、Yaru、扩展与 XFCE 软件包版本和文件布局。用实测版本替换 GNOME 46/Noble 假设，DING 补丁若不再需要则移除，否则基于该版本重新制作并钉住校验值；不跳过完整性校验。Ubuntu GNOME 按下述已确认的 Android 原生显示链路改造，不因发行版同名推定兼容。
Plasma 沿用已在 Resolute 使用的体系。Hyprland 容器仅提供普通客户端，不将完整合成器迁入容器。Mesa/Zink 和 USB GIO 按 Resolute 验证 ELF 依赖/GLIBC/LLVM/模块加载；缺少匹配资产时在原编译机原构建链生成，记录哈希和发布元数据，不在 APK 加入大资产。不允许将 trixie 二进制重新命名伪装成 Resolute，不能通过静默 llvmpipe 回退掩盖 Zink 失败。

## 数据与边界
不自动发行版升级，不迁移 /root，不清除设备数据或旧容器。旧底包/已废弃的 GNOME PRoot 在启动、导入等入口明确拒绝并提示重建，删除/清理入口保留。保留当前归档能力限制，不借此次变更扩充 GNOME 家族备份功能。普通用户、密码、sudo、PolicyKit 与授权代理故障隔离保持原约束。

## 验证与回滚
聚焦测试覆盖完整类型/模式矩阵、统一镜像/codename、直接调用拒绝、归档身份和失败消息、GNOME 版本/扩展、图形组件及 Native 不回归。实机每次功能验证前 force-stop App；四类保留桌面分别全新创建并冷启动，验证终端身份、渲染、文件管理器与支持的 USB 能力。Plasma 验证 Discover 正确/错误密码及取消后桌面存活。Ubuntu GNOME 验证 session、Yaru 和扩展真实加载。Zink/llvmpipe 按各桌面当前支持矩阵测试。构建 arm64 可直接安装 APK，不设置 testOnly、不新增大型内置资产。
回滚代码及组件元数据到上次提交，已存在容器/备份不动；用户按对应版本重新创建容器，不自动降级现有系统。

## 工具限制
当前仓库缺少 .harness 的规定指令、strict gate 脚本、OpenSpec CLI 和 docs/product 基线，按实际可用命令记录证据，不伪造通过。

## 实施发现与已取代的设计限制
Ubuntu Resolute arm64 仓库实际提供 GNOME Shell/Mutter 50.1，Ubuntu session 50.0，仅安装 wayland-sessions/ubuntu.desktop，不提供旧 ubuntu-xorg.desktop。Mutter 50.1 的 meta-context-main.c 仅创建 native 或 headless backend，参数中已无 --x11，既有 gnome-shell --x11 启动链无法沿用。不能以替换版本字符串完成升级。
用户随后已明确授权保留 Ubuntu GNOME、升级现有原生组件并排除 PipeWire；执行以下已确认设计。底包映射正在修改，完整验证前不构建声称完成统一升级的 APK。
独立 GNOME 下线使用 supportsProot() 与 Native 支持分离。安装、启动和 Python 直接调用拒绝；删除生成仅含模式、删除方法、容器别名和状态路径的独立 spec，不受已退休类型或渲染器校验阻断。

## 用户确认的原生组件升级（替代显示桥）
用户明确要求“升级现有组件进行支持，pipewire不用考虑”。本轮新增的 headless/Devkit/PipeWire/Openbox 显示桥不进入交付；此限制针对显示链路，不删除其他桌面既有音频/相机功能。
升级现有 Bionic Mutter 与 GNOME Shell 为 50.1，GJS 为 1.88（配套 SpiderMonkey 140），保留现有 Android backend、System EGL/AHB 输出与 App render/input 协议。生产生态包仅在指定编译机 /workspace/termux-packages 原链构建。新 ABI 的 GIR 必须由真实 Android 类型 dump 生成，不重命名旧 GIR 冒充。
Ubuntu GNOME 的普通用户会话仍在 Resolute PRoot 内：绑定可读 Android 平台目录及原 Termux PREFIX，在同一 PRoot 文件系统视图内启动 Bionic Shell/Mutter；子应用、DING、dconf、GVfs 和会话总线使用容器程序。Bionic 与 glibc 在不同进程，严禁互相加载共享库/typelib。先验证这种执行机制，再接入正式安装和启动；若实际验证失败，明确保留未完成状态，不回退 PipeWire。
容器保留 Ubuntu 50 的主题、扩展、应用和普通用户；Native Ubuntu 外观亦需更新为匹配 GNOME 50 的真实资源，不能继续加载 48 扩展。渲染参数明确区分宿主 System EGL 输出与容器应用 llvmpipe/Zink，不将容器 renderer 传给原生合成器。

## 原生升级验证与回滚
保护范围包括原生 Mutter/Shell/GJS 配方、Android 后端、组件清单与原生/PRoot 启动参数；用户当前明确授权升级。先审查上游 API 变化和依赖版本，再在原链构建、检查 ELF 与新 GIR，模拟器 force-stop 后验证两类 Native GNOME 及 Ubuntu PRoot 的冷启动、真实显示、键鼠、容器终端身份和清理。物理高通 GPU 结果单独记录，不用模拟器代替。
不发布未验证的新组件。回滚恢复已发布组件清单、配方和启动代码；旧容器/备份不动，不自动迁移用户数据。APK 不携带 GNOME/主题大资产。

## 保存时准备、启动时执行（用户已明确授权）
GNOME 保存／安装阶段生成该桌面的会话脚本、私有 D-Bus 激活目录、Android 链接配置快照、PRoot 绑定参数与用户默认设置，并执行版本／依赖／主题／Glycin 完整性检查。保存完成后发布小型会话执行描述。启动只读取保存结果，创建本次会话进程并维护瞬态身份／就绪／日志；不重新扫描组件、执行版本命令、写默认设置、修复安装或回退启动。保存结果缺失、会话进程退出或真实显示失败均直接失败，保留日志，等待用户主动重新保存／安装。私有总线地址及 PID 是每次会话的运行态，不能在保存时伪造。

保存路径同时负责 Java 运行脚本与桥接文件部署、全局 USB autostart 屏蔽、音频端点与默认输出设置。Hyprland 的资源哈希检查、默认配置、主题脚本、字体缓存及客户端包装器在保存时生成，包装器在执行时读取本次会话 token，不能保存上一轮 token。Native Plasma 的 kactivitymanagerd 路径和 kbuildsycoca 应用缓存也在保存时确定。XFCE USB 会话的静态总线配置及会话脚本在保存时生成。启动／停止及 Hyprland 控制操作不再部署运行文件。每次会话的 PID、总线地址、socket、锁、渲染输出就绪与日志属于瞬态运行状态，仍由实际会话建立和清理。

启动失败还需关闭本次会话页面并返回列表，简短提示失败且保留日志；不能停在等待画面或在终态退出后继续等待启动宽限期。此行为不改变用户主动停止失败时的错误处理。
