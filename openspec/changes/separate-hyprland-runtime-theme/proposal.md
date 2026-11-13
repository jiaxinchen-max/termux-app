# Hyprland 合成器与主题包独立管理

## Why

当前 App 在 Java、shell 和 Python 多处固定 r7 合成器身份，主题脚本则用 r7 表示经典外观、m3ww-v1 表示新外观，容易混淆合成器、主题与 App 修订。已公开的 r8 README 和 source-lock 明确复用 r7 原生二进制；不能把 r8 当作另一个合成器版本。

## What Changes

- 分别管理合成器清单、主题清单和 App 修订；同一合成器可搭配经过验证的经典主题或 m3ww 主题。
- App 的 Hyprland 创建与设置展示合成器和主题两项，持久化选择并将明确身份传递到安装、检查、启动及日志。
- 将经典配套和 m3ww 的资源、依赖与兼容关系独立登记；主题资源以单独可校验包分发，主题更新不重复安装合成器。
- 保留现有已自定义桌面的外观和配置；主题切换在停止会话后生效，失败保留上一个可用组合。
- 当前已核验合成器只有 0.57.0-r7；r8 是 App/主题交付修订。未来新增合成器须有真实资产与校验和，不预设 r8 合成器下载地址。

## Capabilities

### New Capabilities

- `desktop-hyprland-components`：Hyprland 合成器与主题的身份、兼容、独立安装、选择持久化、会话启动和回滚。

### Modified Capabilities

无。当前 openspec/specs 尚无基线；已有 add-hyprland-desktop-compositor 与 theme-hyprland-end4-m3ww 活跃变更作为关联约束，不改写其历史验收状态。

## Impact

规划涉及 termux-ui 的 DesktopDistro、配置仓库、创建/设置界面、DesktopLaunchSpec、DesktopEnvironment、DesktopTasks、LaunchScriptInstaller，以及 desktop_install.sh、desktop_start.sh、desktop_hyprland.py、desktop_hyprland_shell.py、desktop_hyprland_theme.py、测试与主题打包工具。保持 Activity → 任务 → TermuxService → shell 边界。

API 文档为 N/A：不新增或修改 HTTP 合约，仅消费现有发布资产。产品文档为 Required，最终验收后维护 docs/product/desktops.md；当前缺失该文档，不能宣称已同步。发布新主题资产、Git 提交/推送及设备部署分别遵循授权。

非目标：重编译或虚构 r8 合成器、改变图形/输入后端、放开渲染模式或 USB/归档支持、发布新版本。风险包括旧配置识别、主题依赖缺失和不兼容组合；通过固定清单、配置保留、候选校验后切换及保留旧组件回滚。界面按独立选择设计，用户已批准此范围。

## 用户追加授权：组件仓库发布与 App 配置同步

用户明确要求使用本次认证更新 jiaxinchen-max/termux-app-packages，并同步 App 配置；APK 文件名保持 termux-app_apt-android-7-debug_arm64-v8a.apk。授权范围包含组件仓库相关文档、目录和源材料提交/推送及独立主题资产发布；App 保留当前分支，本轮不据此推送 App 仓库。保留旧 r7/r8 资产与历史验证材料。

## 已确认的交付方式修订（download-runtime-assets-on-demand）

用户已确认主题与 API 配套资源改为资产库按需下载，取代本文件此前内嵌主题/CLI/deb 的交付要求。Android API 宿主保留 APK；Linux 下随 App 构建配套包与是否打入 APK 分开处理。最终要求及验收场景以 download-runtime-assets-on-demand 的四项规划为准，历史发布与验证记录保留原意，不声称旧 APK 已外置。
