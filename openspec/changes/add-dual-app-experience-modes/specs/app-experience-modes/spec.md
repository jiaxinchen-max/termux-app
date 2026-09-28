# App experience modes

## Requirements

### Requirement: App 必须提供互斥的 Terminal 与 Games 模式

系统 MUST 持久化 `TERMINAL` 或 `GAMES`，缺失或非法值 MUST 回退 `TERMINAL`。

#### Scenario: 现有用户升级

- GIVEN 模式尚未写入
- WHEN App 冷启动
- THEN 打开原 TermuxActivity 并保留内嵌 X11

#### Scenario: Games 模式冷启动

- GIVEN 已持久化 `GAMES`
- WHEN 用户从 Launcher 打开 App
- THEN LocalGamesActivity 成为根交互页面

### Requirement: Games 模式终端不得集成 X11

Games 模式下显式打开终端时，TermuxActivity MUST 保留 TerminalSession 管理，MUST NOT 创建 X11 runtime、挂载 Display Surface、注册 X11 Host 或开放 X11 侧栏。

#### Scenario: 查看游戏 TerminalSession 日志

- GIVEN App 运行在 Games 模式且某次启动选择 TerminalSession
- WHEN 用户显式打开终端
- THEN 可查看和管理 session，游戏画面仍只由 GameSessionActivity 承载

### Requirement: 模式切换必须冷重启

系统 MUST 在用户确认后持久化新模式并冷重启 App，MUST NOT 在当前进程内热替换 X11 所有权。

#### Scenario: 用户取消切换

- WHEN 用户在重启确认框取消
- THEN 模式值和当前 Activity 均不变化

### Requirement: App 模式与 Launch 执行模式必须正交

`APP_SHELL/TERMINAL_SESSION` MUST 只控制 TermuxService runner，不得隐式改变 `TERMINAL/GAMES` App 模式。
