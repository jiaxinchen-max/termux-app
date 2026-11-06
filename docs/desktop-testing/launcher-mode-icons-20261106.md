# 默认模式与桌面图标（2026-11-06）

TerminalLauncher 使用原黑底 >_ 图标，UiLauncher 使用深青底四宫格图标；保留原应用名称。只启用当前默认模式对应的一个入口，两者都经 TermuxLauncherActivity 调用原 TermuxActivity 路由。移除 TermuxActivity 自身的 LAUNCHER/LEANBACK_LAUNCHER filter，终端静态快捷方式移到 TerminalLauncher。原显式 TermuxActivity / Session / 通知入口继续可用。

启动别名单独放在无界面、无历史的 launcher task；实际 App 使用独立任务。实际 TermuxActivity 初始化时按持久化模式同步别名，正式默认模式切换在同步别名后执行现有冷重启。这样禁用旧图标不会关闭实际运行任务。第一版直接用实际 Activity 承载别名，首次升级从旧别名切至 UI 时曾退出到桌面；最终独立任务实现已修正并在小米首次升级启动复验通过。

Android 13+ 批量更新两个组件状态；较低版本先启用目标再禁用另一个，均使用 DONT_KILL_APP。同步结果只由明确的 AppExperienceMode 决定，没有失败后改用其他图标或运行模式。UI 中的 Terminal 标签页、临时打开 UI 管理页不改变默认图标。图标方案适用于桌面 launcher；系统应用详情和通知默认 application icon 保持原资源。

提供旧系统 layer-list/vector 和 Android 8+ adaptive/monochrome 资源。桌面仍负责缓存和图标位置；本轮 MIUI 保留位置，模拟器桌面曾重新排列底部推荐图标，不承诺位置固定。Android 13 以下代码路径和 TV 桌面未实机验证。

最终本地 assembleDebug 成功，现有 TermuxAppExperienceRouterTest 4 项通过。ARM64 APK SHA256 9c69fbfa980ebaf09d62533b2b0398c4e5f0b2a40372287da83af880a14d8d51，已覆盖安装 Pixel Fold API35 和小米 Mi 11 Ultra API34。正式 UI 验证包括：

- Fold：桌面图标启动；设置 UI→Terminal→UI 并冷重启；每次 PackageManager 仅列出对应的一个 launcher；实际终端/X11页与 Wine UI 符合所选默认模式；UI 终端标签页仍保持 UiLauncher；最终四宫格图标可重新打开 UI。
- 小米：新别名默认状态下首次升级启动，已保存 UI 偏好自动匹配 UiLauncher 且页面不再回桌面；设置 UI→Terminal→UI，两边冷重启与真实桌面图标（黑底 >_ / 四宫格）均通过；终端图标重开通过；最终恢复原 UI 默认模式。

两台设备最终都保留 UI 默认模式，原主题/方向设置未改。没有创建、启动、删除桌面或 Wine 配置。证据位于 /tmp/launcher-mode-20261106；构建和现有路由测试日志分别为 /tmp/launcher-mode-20261106-build.log、/tmp/launcher-mode-20261106-router-tests.log。本轮只在本地修改并构建 App，没有推送源码或发布 APK。


## 名称与桌面图标企鹅标记

按后续要求，两个 launcher 名称固定为“桌面 / 终端”；仅 UI/桌面图标右下角增加微型企鹅，原终端图标资源不变。企鹅写入共用 UI foreground vector，旧版 layer-list 与 adaptive icon 都使用该资源。

本地 assembleDebug 通过，ARM64 APK SHA256 ee94608fcf6d24e4d94e6aaa150cc462c3dcaca5268402f8678b127b3422ac01，已安装到 Fold 与小米。Fold 通过真实模式设置验证“桌面→终端→桌面”的名称切换及重启；Pixel 圆形图标和小米 MIUI 图标均实际确认右下角微型企鹅、桌面名称和原终端图标。仅资源/标签修改，本轮未重复前述完整路由单测。

本轮开始检查时小米保存的是 TERMINAL（通过实际默认模式偏好与 PackageManager 核对），临时切 UI 验证后恢复 TERMINAL；Fold 恢复 UI。主题、方向和配置数据不变。截图/布局脚本在 /tmp/launcher-penguin-20261106，构建日志 /tmp/launcher-penguin-20261106-build.log。


## Termux:GUI 风格显示器图标

后续按小米 Termux:GUI 图标重新设计桌面图标：黑底、缩小左侧命令箭头、放大右侧显示器，企鹅位于屏幕内。替换四宫格和右下角企鹅标记；“桌面 / 终端”名称及终端图标不变。新增独立 monochrome foreground，以空心显示器和企鹅轮廓保留主题图标的层次。

本地 assembleDebug 通过，ARM64 APK SHA256 79de718a365f2d7bbff5998a2ac1dcf66ec92a6f90e0e9e6807dd005e07dfec2，已覆盖安装 Fold 与小米。实际检查 Pixel 圆形和 MIUI 方形图标，箭头小于显示器、企鹅位于屏内，裁切正常；小米原 Termux:GUI 与新桌面图标同屏对比确认。验证前两台均为 UI 默认模式，本轮不改变默认模式。单色资源完成构建检查，未切换系统 Launcher 的 themed-icons 设置。

截图与脚本在 /tmp/launcher-monitor-20261106，构建日志 /tmp/launcher-monitor-20261106-build.log。


## 最终黑白直角显示器与等宽底座

根据连续微调要求，桌面图标使用占主体的直角显示器；命令提示符位于屏幕左上角，企鹅居中且保持前版 0.9 缩放。普通 foreground 仅含纯黑/纯白，屏幕、企鹅嘴脚和轮廓均取消彩色填充。显示器与底座左右边界都为 viewport x=26..82，底座靠近显示器，以便圆形 Launcher 裁切后仍保持两端对齐。monochrome 资源同步直角轮廓和等宽底座。终端图标、两种名称及模式逻辑不变。

本地 assembleDebug 通过，最终 ARM64 APK SHA256 c03da198d0d224116fa13d9b196e18bc41e12c9337ad463d34931a34abed761c，已安装 Fold 和小米。实际通过桌面界面检查 Pixel 圆形与 MIUI 方形图标：大显示器、屏内左上提示符、同尺寸居中企鹅、黑白配色、直角外框及等宽底座均符合当前设计。两台默认模式均未修改。本轮仅资源微调，没有重复之前的完整模式切换矩阵。

最终截图在 /tmp/launcher-aligned-base-20261106；构建日志 /tmp/launcher-aligned-base-20261106-build.log。前两轮黑白/直角中间截图分别保留于 /tmp/launcher-bw-20261106、/tmp/launcher-square-20261106。


## 启动图标名称国际化

去掉 launcher_name_desktop / launcher_name_terminal 的 translatable=false 和默认资源中的中文硬编码。默认 values 使用 Desktop / Terminal，values-zh-rCN 使用桌面 / 终端，与现有 App 的中英文资源范围一致。别名仍引用同一资源 ID，图标与模式逻辑不变。

本地 assembleDebug 通过，ARM64 APK SHA256 14dcc65025e69b13d87e8b4e13f5de8fe09d01baff3a3bfec9e2cc39cb331590，已更新 Fold 和小米。资源核对覆盖两个模式的中英文名称；实际桌面 UI 验证英文 Fold 显示 Desktop、中文小米显示桌面。没有改变设备语言或默认模式。截图/布局脚本在 /tmp/launcher-i18n-20261106，构建日志 /tmp/launcher-i18n-20261106-build.log。
