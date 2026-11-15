# 容器普通用户会话与恢复名称

## 背景与目标
Discover 安装的 VLC 在当前容器 root 桌面中被自身 root 检查拒绝。实机 runuser 切换为非 root 后，vlc --version 成功。用户明确要求所有新容器默认使用普通用户 termux，初始密码 termux；桌面信息显示账户提示；导出保存名称，恢复沿用，重名追加数字后缀；不考虑旧数据兼容。

## 范围
新建容器安装阶段建立 termux 账户和用户目录；桌面、应用、容器终端使用该身份。覆盖 Plasma、GNOME、Ubuntu GNOME、XFCE 与 Hyprland 容器客户端。安装、初始化中确需管理权限的任务，以及容器内 PackageKit/PolicyKit 管理服务，仍使用容器 root；Native 桌面运行方式保持现状。
备份元数据已经包含 name，并由引擎恢复；补齐最终清单发布时的名称去重，不修改容器路径别名。

## 排除
不迁移旧容器 /root 内容、不为缺少 termux 的旧备份创建兼容回退；不修改 VLC 二进制、不采用此前讨论的 VLC 专用用户或 dpkg-divert 包装方案。PRoot 普通身份不作为 Android 权限隔离承诺。

## 文档分类
api_docs: N/A，本次没有 HTTP API 合同变化。
product_docs: Required，验收后更新 docs/product/desktop-management.md（当前尚不存在，届时创建）；一致性 Pending。

## 确认边界
本轮用户明确了功能需求。此方案待需求/设计检查点确认，确认前不编辑业务代码；此前要求任务结束推送进度的授权继续适用。实现完成后才请求功能验收，不在本阶段归档。
