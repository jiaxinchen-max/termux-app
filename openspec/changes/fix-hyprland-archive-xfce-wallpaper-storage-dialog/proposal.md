# 修复 Hyprland 归档、XFCE 壁纸和外接存储弹窗

## 问题与现场证据

1. `DesktopArchiveDistribution.supports` 排除了 Hyprland，管理菜单因此没有导出入口。该限制还存在于恢复初始化和宿主前置安装，归档元数据也没有保存 Hyprland 合成器与主题身份。只增加菜单会生成无法完整恢复的交付。
2. 模拟器 `ubuntu-xfce4-1` 已安装 xfdesktop4 与壁纸资源，但日志明确报错：`/usr/share/xfce4/backdrops/xubuntu-wallpaper.png` 不存在。普通用户没有设置过替代壁纸，默认配置因而无法显示背景。
3. 截图为 USB 外接存储弹窗，区别于 Android 共享目录授权。其背景颜色只有约 55% 不透明度，设备没有呈现有效背景模糊。开关和安装按钮因环境不支持而被禁用，但所有环境都错误复用了 Plasma 的解释，按钮还使用了 Activity 上下文下的普通控件。

证据位于 `/home/user/workspace/artifacts/desktop-three-issues-20261116/`。本阶段仅诊断和规划，不修改业务代码或设备配置。

## 拟实施范围

- 为当前 Ubuntu Resolute Hyprland PRoot 打通导出及独立恢复，保存名称、显式渲染设置、合成器和主题身份。恢复前补齐必要宿主组件，恢复阶段按新身份生成会话，保留容器应用和用户文件。
- 在 XFCE 容器安装、保存与恢复阶段补齐有效的默认壁纸路径，使用发行版已有图像，保留用户自选壁纸。启动阶段不安装、不检查修补配置。
- 为外接存储弹窗使用不依赖模糊能力的实色深浅主题表面和主题控件，清楚呈现可操作状态、忙碌状态和当前桌面的具体限制，异步失败后恢复可操作状态。

## 范围与确认

历史 Hyprland 规划刻意未开放归档；本变更拟按本轮反馈扩展这一能力。USB 后端范围不随归档扩展：当前支持 XFCE；Hyprland、当前 Ubuntu GNOME 和 Plasma 的真实限制必须准确显示，不通过放开开关伪装支持。扩展这些后端的 USB 能力另行讨论。

不修改音频生命周期、默认入口、容器普通用户策略或其他桌面的归档格式；不删除、迁移或重新安装用户原容器。APK 继续使用固定名称，不内嵌桌面或主题资产。

无 HTTP 契约变化，`api_docs=N/A`。用户可见能力变化，`product_docs=Required`，拟验收后维护 `docs/product/desktop-container-archive.md`、`docs/product/desktop-runtime.md`、`docs/product/external-storage.md`，一致性为 Pending。仓库没有基线 specs、产品文档目录、指定 Harness 流程文件、严格门禁脚本或 OpenSpec CLI，手工建立规划并如实记录验证限制。

当前状态：用户已明确回复“批准”，四份规划已确认，进入实施。沿用用户任务完成后推送保存进度的授权；验收前不归档。
