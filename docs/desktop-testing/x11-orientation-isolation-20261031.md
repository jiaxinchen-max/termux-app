# 应用与 X11 会话方向隔离（2026-10-31）

本记录的游戏方向继承规则已被后续修改替换：游戏只读取自身方向，缺省横屏，不再提供“跟随 X11”。最新验证见 `game-session-interaction-20261031.md` 的“本轮方向隔离复验”。桌面方向规则保持本记录所述行为。

## 设置合同

- “应用界面方向”保存在 `local_games_settings.orientation`，用于游戏库、桌面列表、文件管理器及配置页面。进入或恢复页面时重新应用，退出会话后不会沿用会话方向。
- “X11 会话方向”独立保存在 `x11_orientation_settings.orientation`，用于游戏会话及未指定独立方向的桌面；首次使用为自动，不复制应用界面设置。
- 桌面现有方向覆盖保持有效；原“跟随全局设置”方向选项改为“跟随 X11 方向设置”。修改桌面覆盖不写回上述两项设置。
- 自动模式明确使用 `SCREEN_ORIENTATION_FULL_USER`，遵循系统旋转设置，避免 `UNSPECIFIED` 让应用界面沿用前一会话方向。
- 触控设置、桌面导入导出的独立方向字段和运行模式均保持现有合同。

## 模拟器验证

设备：`emulator-5554`，Android 15 arm64，1080×2400。游戏库 0 项。设置、启动、覆盖切换和停止全部通过 App 界面；ADB 仅部署 APK、操作界面及读取诊断。

| 应用方向 | X11 默认方向 | GNOME 独立覆盖 | 会话实际方向 | 停止后桌面列表方向 |
|---|---|---|---|---|
| 竖屏 | 横屏 | 跟随 X11 | 横屏，2400×1080 | 竖屏，1080×2400 |
| 横屏 | 竖屏 | 跟随 X11 | 竖屏，1080×2400 | 横屏，2400×1080 |
| 横屏 | 竖屏 | 横屏 | 横屏，2400×1080 | 恢复跟随 X11 后再停止，列表为横屏 |

每一步读取两个 SharedPreferences 确认值独立。覆盖切换只改变 `desktop-a15bd707.orientation`。最终 APK 更新后再次验证相反方向组合和返回列表行为；重启 App 后两项设置保留。测试结束恢复应用自动、X11 自动及 GNOME 原有竖屏覆盖，通过 App 停止会话。

本次验证针对窗口方向隔离。Native GNOME 输出画布仍固定为 1280×720；未实现输出缓冲和显示器模式动态调整。模拟器无游戏，本次未运行 Wine 游戏；未进行小米实机复验。

## 构建

使用指定 Linux 编译机的现有 Android 工程构建，未在本机编译。`:termux-ui:testDebugUnitTest` 517 项、`:termux-x11:testDebugUnitTest` 108 项通过，`:app:assembleDebug` 成功。

最终 APK：`/tmp/x11-orientation-20261031/termux-x11-orientation.apk`。

SHA256：`fe10f4e9731e5ae0bfb7300c9dcdfa7b254f0424415471ed476a315ed6c7ac05`。

诊断与截图：`/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/x11-orientation-20261031/`。
