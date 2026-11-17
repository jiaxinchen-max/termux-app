# 修订方案审查

用户进一步要求 Native 只提供合成器、其余在容器处理。因此此前宿主扫描/应用 JSON 快照/镜像 .desktop 方案已整体替换，不实施两套方案。现有 XFCE 源码证实宿主 X server、容器 xfce4-session 分工；Hyprland 源码证实现有面板和通知运行宿主、每个 guest 应用单独路由，需要调整完整桌面会话而非只移动静态主题。

修订明确了 Bionic/glibc 组件来源、Hyprland exec 的执行方、宿主可见配置与用户 dotfiles、IPC、统一 guest session bus、包管理权限、Android 及 USB 桥接和失败处理。容器外观不与合成器固定配对，但配置/IPC 能力仍须验证，不声称任意 dotfiles 通用。没有遗留规划级 MUST_FIX；组件供应和真实 Wayland 支持为首个实施验证任务，未声称已可在设备运行。

本轮只修改中文规划与运行台账，业务代码未改。仓库缺少 baseline specs、产品知识目录、Harness bootstrap/严格门禁和 OpenSpec CLI；直接源码与历史规格作为上下文，人工校验规划。等待用户确认后实施，不包含外部资产发布、Git 发布或部署授权。
