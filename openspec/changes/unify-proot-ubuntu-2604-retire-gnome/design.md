# 设计

## 当前事实
DesktopCreationCatalog 将 Ubuntu GNOME 映射到 noble、Plasma 映射到 resolute，其余映射 trixie。desktop_install.sh 的 Hyprland 分支单独创建 debian:trixie，其他分支按桌面验证 os-release。desktop_ubuntu_gnome.py 显式校验 Ubuntu Noble、GNOME 46 扩展元数据与 DING 源码哈希，DesktopEnvironment 亦校验 noble。Mesa/Turnip 与 glibc GIO 资产包含 Debian Trixie 构建身份，不能假定与 Resolute ABI 相同。

## 目标目录与身份
所有可创建的 PRoot 桌面固定 ubuntu:resolute，codename resolute；安装后同时验证 ID=ubuntu 和 VERSION_CODENAME=resolute。列表为 Plasma、Ubuntu GNOME、XFCE、Hyprland 客户端。GNOME 枚举仍可供 Native 使用，但支持矩阵拒绝 PROOT+GNOME；UI 不提供该组合，直接脚本调用与归档恢复也拒绝。移除仅属于旧 PRoot GNOME 的分支；与 Native/Ubuntu GNOME 共用逻辑不得误删。

## 版本相关适配
先从 Resolute 实际软件源确认 GNOME/Ubuntu session、Yaru、扩展与 XFCE 软件包版本和文件布局。用实测版本替换 GNOME 46/Noble 假设，DING 补丁若不再需要则移除，否则基于该版本重新制作并钉住校验值；不跳过完整性校验。Ubuntu GNOME 仍沿用既有显示架构，验证宿主端与容器 GNOME 版本交互，不因发行版同名推定兼容。
Plasma 沿用已在 Resolute 使用的体系。Hyprland 容器仅提供普通客户端，不将完整合成器迁入容器。Mesa/Zink 和 USB GIO 按 Resolute 验证 ELF 依赖/GLIBC/LLVM/模块加载；缺少匹配资产时在原编译机原构建链生成，记录哈希和发布元数据，不在 APK 加入大资产。不允许将 trixie 二进制重新命名伪装成 Resolute，不能通过静默 llvmpipe 回退掩盖 Zink 失败。

## 数据与边界
不自动发行版升级，不迁移 /root，不清除设备数据或旧容器。旧底包/已废弃的 GNOME PRoot 在启动、导入等入口明确拒绝并提示重建，删除/清理入口保留。保留当前归档能力限制，不借此次变更扩充 GNOME 家族备份功能。普通用户、密码、sudo、PolicyKit 与授权代理故障隔离保持原约束。

## 验证与回滚
聚焦测试覆盖完整类型/模式矩阵、统一镜像/codename、直接调用拒绝、归档身份和失败消息、GNOME 版本/扩展、图形组件及 Native 不回归。实机每次功能验证前 force-stop App；四类保留桌面分别全新创建并冷启动，验证终端身份、渲染、文件管理器与支持的 USB 能力。Plasma 验证 Discover 正确/错误密码及取消后桌面存活。Ubuntu GNOME 验证 session、Yaru 和扩展真实加载。Zink/llvmpipe 按各桌面当前支持矩阵测试。构建 arm64 可直接安装 APK，不设置 testOnly、不新增大型内置资产。
回滚代码及组件元数据到上次提交，已存在容器/备份不动；用户按对应版本重新创建容器，不自动降级现有系统。

## 工具限制
当前仓库缺少 .harness 的规定指令、strict gate 脚本、OpenSpec CLI 和 docs/product 基线，按实际可用命令记录证据，不伪造通过。

## 实施发现与待确认设计
Ubuntu Resolute arm64 仓库实际提供 GNOME Shell/Mutter 50.1，Ubuntu session 50.0，仅安装 wayland-sessions/ubuntu.desktop，不提供旧 ubuntu-xorg.desktop。Mutter 50.1 的 meta-context-main.c 仅创建 native 或 headless backend，参数中已无 --x11，既有 gnome-shell --x11 启动链无法沿用。不能以替换版本字符串完成升级。
需要用户选择允许重做 Ubuntu GNOME 的显示链路（另行形成具体设计并验证），或同时废弃 Ubuntu GNOME PRoot 类型；此前授权只废弃独立 GNOME，不得自行扩大移除范围。在确认前，Ubuntu GNOME 及其他底包映射尚未更改，不构建声称完成统一升级的 APK。
独立 GNOME 下线使用 supportsProot() 与 Native 支持分离。安装、启动和 Python 直接调用拒绝；删除生成仅含模式、删除方法、容器别名和状态路径的独立 spec，不受已退休类型或渲染器校验阻断。
