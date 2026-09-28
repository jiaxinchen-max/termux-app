# Proposal: Optional TerminalSession launch mode

## Why

后台 AppShell 适合正常游戏流程，但排查 Wine、Box64 和组件启动问题时，创建命名 TerminalSession 能直接在 Termux 终端查看实时输出。启动模式需要可选，同时保持 Games 模块不依赖主 app 实现。

## What changes

- RuntimeProfile 增加每游戏启动执行模式，默认 `app_shell`。
- LaunchSpec 冻结执行模式并兼容旧 schema。
- Host adapter 根据冻结模式选择 `APP_SHELL` 或 `TERMINAL_SESSION`。
- RuntimeProfile UI 提供后台运行与终端会话选项。
- 不修改 TermuxService，不强制打开 TermuxActivity。

## Non-goals

- 不从终端文本解析任务状态。
- 不以 TerminalSession 生命周期替代 LaunchTask、JSONL 或 PID 管理。
- 不改变 X11 Session Activity 的生命周期。
