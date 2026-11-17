# PRoot 桌面应用中心

## 问题与范围
目前仅 Plasma 配置 Discover。用户批准：Plasma 保留 Discover；Ubuntu GNOME、XFCE、Hyprland PRoot 安装 GNOME Software，使用 Ubuntu 26.04 DEB/APT 后端。Native 不变，不加入 Snap App Center 或 Flatpak。

## 已确认约束
用户“批准执行”确认上述方案。所有依赖、目录、默认配置在安装、保存或恢复阶段完成；普通启动不修复、不安装。PackageKit、PolicyKit、D-Bus 是每次会话运行的服务。使用容器内服务和真实 PAM 密码认证，桌面与应用保持普通 termux 用户。

## 文档分类
api_docs 为 N/A：没有 HTTP 合约变化。product_docs 为 Required，目标 docs/product/desktop-software-center.md；一致性 Pending，用户最终验收后再固化，当前不归档。

## 2026-11-17 已批准的 GNOME 激活修正
用户在只读诊断后回复“批准”，确认修复 Ubuntu GNOME PRoot 软件中心点击无窗口的问题。软件中心通过会话 D-Bus 激活，而现有 core 白名单遗漏 org.gnome.Software。本轮只补齐该入口、保存期缺失校验及对应回归；不改变 Native、系统总线或授权规则。

## 2026-11-17 已批准的 Hyprland 菜单修正
用户明确要求“那就修复Hyprland”，确认补齐 m3ww PRoot 启动器的软件中心入口。现有容器启动包装器和 Classic 入口已具备，仅 m3ww 固定应用列表遗漏；本轮复用既有包装器，Native 的桌面文件自动发现保持不变。解释目前 PRoot 尚未实现容器应用自动发现，不扩展为全部应用索引。交付 APK 保留 termux-app_apt-android-7-debug_arm64-v8a.apk 原始名称。
