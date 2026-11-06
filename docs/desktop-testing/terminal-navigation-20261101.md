# 终端会话导航入口（2026-11-01）

- 移除设置页中的“打开终端会话”行，移至文件管理器与设置之间。
- 竖屏底栏与横屏图标导航保持同序；底栏使用短标签 Files/文件、Terminal/终端，完整打开动作保留在无障碍描述中。
- 沿用 LocalGamesHost.openTerminal()；入口不切换当前页面或导航高亮，返回后保留原页面。
- 本地 :app:assembleDebug 成功。
- emulator-5554 通过 App 界面验证横竖屏入口顺序、实际终端打开、返回设置页及原高亮；XML 断言通过。模拟器系统方向恢复原值 0。

APK：/tmp/terminal-navigation-20261101/termux-terminal-navigation.apk。
SHA-256：b50d5f4f0925edcd9df20f8845e8b1034ae2521fbc9bc4527a3dce3e7a311d0b。

截图、XML、源码哈希及构建日志：/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/terminal-navigation-20261101
