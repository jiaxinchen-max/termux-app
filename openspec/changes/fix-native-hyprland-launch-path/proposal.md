# 修复 Native Hyprland 固定入口启动失败

用户报告 Native Hyprland 即使重新安装仍无法启动（已更正最初 Plasma 的表述）。模拟器 120.24.191.156:8057 的 session.log 明确记录主题 CLI 的 Invalid desktop path，随后监督器终止整个会话。固定入口 ID 为 native-hyprland，实际工作目录为 HOME/.termux/hyprland-native-hyprland；两个 CLI 仅接受旧 desktop-* ID。

本次修复主题及桌面操作脚本对固定入口目录的识别，同时保持旧 Native 记录、容器目录以及无效路径隔离。既有配置、主题、容器数据与 APK 原名均保留。不涉及 HTTP API，api_docs=N/A；产品文档 Required，目标 docs/product/desktops.md，一致性 Pending，用户验收前不更新基线。

授权依据：用户明确要求处理 Native Hyprland 重装后仍无法启动，作为同范围修复直接授权 Approved_Direct_User_Request。没有 Git 提交、推送、归档或设备部署授权。

## 用户复测后的范围补充

用户再次反馈无法启动。15:03 现场 APK SHA256 仍为 4af6e6c9d5b6b379a2e84167b11a43f8ebcee331c74831c1c7075b81025343b1（上一版），已释放脚本仍是旧目录规则。同时直接源码审查发现 start() 仅使用运行目录，不会更新 APK 中的修复脚本。因此补充覆盖升级兼容：启动时只刷新应用自带受管脚本，不执行安装、下载、预检或重写桌面规格及用户配置。延续用户本次直接修复授权，验收仍 Pending。

## 应用无法打开的继续修复

用户反馈当前 Native Hyprland 只能打开终端与文件管理器。现场 session.log 显示 D-Bus 激活的 org.gnome.TextEditor 报 Failed to open display，org.kde.kwalletmanager 报 Failed to create wl_display。私有 D-Bus 在合成器确定 WAYLAND_DISPLAY 前启动，激活环境未同步，且继承合成器私有 LD_LIBRARY_PATH。补充修复激活环境，保持标准 desktop/GIO 启动机制，避免为单个应用写死启动命令。此为同一桌面不可用问题的继续修复，沿用用户修复授权。HTTP 文档 N/A，产品文档及验收仍 Pending。
