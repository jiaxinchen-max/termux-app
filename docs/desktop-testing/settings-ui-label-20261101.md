# 设置页入口与模式名称（2026-11-01）

- 删除设置根页的 X11 会话方向菜单；保留应用 UI 方向设置。
- 默认模式的 Game 显示名称改为 UI，中文为 UI 模式；同步更新帮助和终端说明。
- 底层模式枚举及已保存的模式值不变，兼容现有配置。
- APK 在本地 macOS 执行 :app:assembleDebug 成功。
- emulator-5554 通过 App 界面验证：设置页无 X11 session orientation；保留 App UI orientation；当前模式为 UI，选择列表为 Terminal/UI；选择当前 UI 后正常返回设置页。

APK：/tmp/settings-ui-label-20261101/termux-settings-ui.apk。
SHA-256：fc36f616fb261e147a100ca8754cb36ebabd40232c5206476c7cb3b90e9b37a4。

截图、XML、断言与构建日志：/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/settings-ui-label-20261101
