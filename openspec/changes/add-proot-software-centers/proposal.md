# PRoot 桌面应用中心

## 问题与范围
目前仅 Plasma 配置 Discover。用户批准：Plasma 保留 Discover；Ubuntu GNOME、XFCE、Hyprland PRoot 安装 GNOME Software，使用 Ubuntu 26.04 DEB/APT 后端。Native 不变，不加入 Snap App Center 或 Flatpak。

## 已确认约束
用户“批准执行”确认上述方案。所有依赖、目录、默认配置在安装、保存或恢复阶段完成；普通启动不修复、不安装。PackageKit、PolicyKit、D-Bus 是每次会话运行的服务。使用容器内服务和真实 PAM 密码认证，桌面与应用保持普通 termux 用户。

## 文档分类
api_docs 为 N/A：没有 HTTP 合约变化。product_docs 为 Required，目标 docs/product/desktop-software-center.md；一致性 Pending，用户最终验收后再固化，当前不归档。
