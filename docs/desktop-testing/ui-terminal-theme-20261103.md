# UI 终端主题适配（2026-11-03）

UI Terminal 沿用现有 Auto / Light / Dark 设置，浅色使用暖浅底深色文字，深色使用暖深底浅色文字。ANSI 0–15、光标、扩展按键和输入页同步适配。安装准备会话框复用同一终端配色。渲染层覆盖仅作用于视图，保留 256 色及真彩色语义，不修改共享 emulator 或全局 TerminalColors；传统终端继续使用原有配色。

验证：

- 本地 `:app:assembleDebug` 成功，新 APK 安装到 emulator-5554。
- 正式 App 菜单进入 UI Mode，在 Settings → UI theme 切换 Light / Dark 后打开 Terminal，背景、文字、光标、扩展按键和标签栏配色一致。
- UI 设置恢复 Auto；停留在 Terminal 时切换系统夜间模式，页面同步显示深色，再恢复系统浅色后页面同步恢复。原系统设置为 `Night mode: no`，验证后已恢复。
- `UiTerminalThemeTest` 通过：浅深主题渲染及取消覆盖均不修改共享会话颜色或全局默认颜色，传统 TerminalView 保留会话背景。

构建日志：`/tmp/terminal-area-20261103/ui-terminal-theme-build.log`。测试日志：`/tmp/terminal-area-20261103/ui-terminal-theme-test.log`。界面截图：`ui-terminal-light.png`、`ui-terminal-dark.png`、`ui-terminal-auto-light.png`、`ui-terminal-auto-dark.png`、`ui-terminal-auto-restored.png`，位于同一目录。
