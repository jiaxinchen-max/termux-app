# Proposal: 双 App 交互模式

## 背景

当前 TermuxActivity 同时承载终端和 X11 Surface，而 Games 又有独立的游戏库与 GameSessionActivity。目标产品需要两套互斥的 App 交互形态，避免游戏模式仍暴露 Termux/X11 混合界面。

## 变更

- 增加 `TERMINAL` 与 `GAMES` 两种持久 App 体验模式，进程启动后冻结。
- Terminal 模式保持现有 TermuxActivity + 内嵌 X11 行为。
- Games 模式以 LocalGamesActivity 为 Launcher 落点，GameSessionActivity 独占游戏 X11 UI。
- Games 模式显式打开终端时只初始化 TerminalView 和 session 管理，不创建 TermuxActivity X11 runtime、Display Surface 或 X11 侧栏。
- 模式切换必须确认并重启 App；不得在当前进程内热切换。
- 每个游戏任务的 `APP_SHELL/TERMINAL_SESSION` 仍是独立执行选项，不与 App 体验模式耦合。

## 非目标

- 不拆分 TermuxService 进程。
- 不改变 Termux 软件包、prefix 或组件下载模型。
- 不删除 Terminal 模式原有 X11 能力。
