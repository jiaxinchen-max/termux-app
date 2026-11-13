# 运行时资产按需下载提案

## Why

用户明确纠正：主题包和 termux-api.deb 应由组件资产库分发。当前将独立主题及旧字体同时内嵌，并把“Linux 下随 App 构建 API CLI”与“打入 APK”绑定，导致 APK 增大且组件交付边界不符合要求。

## What Changes

- 两套主题包、字体和 Android API 配套 CLI/deb 改由组件资产库发布；APK 只保留安装逻辑、可信元数据和 Android API 的 Java/IPC 功能。
- Linux 下 API CLI/deb 仍随 App 构建和配套校验，构建产物放入 APK 资产目录之外。各 ABI 与当前 APK 的版本和路由契约匹配。
- 主题只在安装/切换时获取所选包。API CLI 在明确的初始化/修复任务获取当前 ABI 的匹配组件，不在桌面启动时联网。
- **BREAKING** 全新安装或本地组件缺失时需要联网；通过 SHA/大小验证的缓存或已安装组件可离线复用。下载失败明确呈现可重试状态，不把半成品当作初始化成功。
- 移除旧重复主题字体和无消费者的旧主题资源；保留许可证与源资源用于独立组件构建。
- APK 名称保持 termux-app_apt-android-7-debug_arm64-v8a.apk。

## Capabilities

### New Capabilities

- `runtime-asset-delivery`：运行时资源的外置交付、APK 配套身份、校验缓存和失败恢复。

### Modified Capabilities

无已归档 baseline specs。与未归档 separate-hyprland-runtime-theme / embed-complete-termux-api 的交付描述存在冲突：本提案明确取代其“内嵌主题/CLI”的分发约定，保留 Android API 宿主实现、主题选择和运行时兼容约束。实施确认后同步相关活动规划，不改写历史已发布证据。

## Impact

涉及主题生成器/安装器、LaunchScriptInstaller、termux-api 构建配置与 EmbeddedApiInstaller、TermuxInstaller 的 bootstrap 失败恢复、运行时安装任务入口及资产库清单。

不更改 HTTP 服务合约；api_docs 为 N/A，原因是只消费既有 GitHub 静态资产。product_docs 为 Required，最终验收后维护 docs/product/desktops.md 和 docs/product/android-api.md；目前路径不存在，仅登记规划，不创建已生效产品声明。

不改变主 APK 的 API 接收端、包名、权限、签名或已有 USB 能力，不外置 Android 平台类。不重编未变化的 Hyprland/KWin；原生生态包仍使用指定编译机。主要风险是首次初始化断网、错误 ABI、与 APK 不匹配以及升级后误用旧文件。回滚保留上个可用组件和旧 APK，不清空用户配置或现有终端。
