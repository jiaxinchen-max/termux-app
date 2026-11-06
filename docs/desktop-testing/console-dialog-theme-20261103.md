# 会话日志窗口主题适配（2026-11-03）

运行环境准备、Native 前缀准备和组件安装窗口共用 ConsoleDialogTheme。窗口面板、准备日志、真实 TerminalView、状态文字同步使用当前 UI 主题资源；组件安装进度条和操作按钮同步更新，失败状态使用主题错误色。FollowingLogView 更新文字及选区颜色时保留内容和滚动跟随状态；终端使用视图本地配色，不重置共享会话。

窗口创建、启动和配置变化时重新应用配色。遮罩重复应用先清理旧监听及视图，浅深色切换不积累暖色遮罩。浅色关闭全屏暗遮罩及全屏背景模糊，保留面板内背景模糊，避免 DisplayPolicy 将状态栏区域按暗色处理；深色保留暗遮罩。系统栏配色在窗口附着后再次应用。

验证：

- 本地 APK 构建成功。
- ConsoleDialogThemeTest、FollowingLogViewTest 和 UiTerminalThemeTest 共 3 项通过，覆盖浅深色往返、日志保留、遮罩数量及移除、浅色禁止全屏暗遮罩/模糊、系统栏图标标志、终端配色隔离。
- emulator-5554 通过正式 UI Mode → Wine 配置 → Engine → Common → Wine package → Install from file，导入本次创建的 24 字节无效归档。浅深色日志窗口显示对应背景、正文、光标和错误色；归档被 custom_archive_format_unsupported 拒绝，没有安装或替换组件。
- 最终版本确认浅色状态栏使用深色图标，DisplayPolicy 的状态栏区域为 LIGHT_STATUS_BARS；验证后关闭窗口，恢复 Auto 和 Wine 列表，并清理本轮临时失败任务及归档。原有配置与已安装组件保留。

构建、测试、UI 操作日志与截图位于 `/tmp/terminal-area-20261103/console-theme-*`。
