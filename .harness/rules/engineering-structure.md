# Engineering Structure Rules

## 模块职责

- `app`：Android 应用、Activity/Fragment、TermuxService 接入和产品编排。
- `termux-shared`：跨模块 Termux 公共能力，不放本地游戏产品逻辑。
- `terminal-emulator` / `terminal-view`：终端内核与显示，不承载游戏状态。
- `termux-x11`：X11 Surface、输入、窗口和控制器运行时。
- `termux-render`：渲染能力；使用前必须明确与 X11 的边界。
- `float-ball`：运行中悬浮入口；不得成为任务状态唯一来源。

## 本地游戏边界

- 新功能由独立 `LocalGamesActivity` 承载，避免继续扩张 `TermuxActivity`。
- 游戏库、游戏详情和组件 UI 不直接创建 TerminalSession。
- 长任务由 Service/Orchestrator 拥有，Activity 通过 task-id 观察状态。
- shell 只负责实际执行，使用结构化事件传递阶段、进度、错误和退出结果。
- 终端 UI 仅作为可选开发者控制台，不参与正常用户流程。
- `Game`、`RuntimeProfile`、`LaunchTask`、`ComponentTask`、`SaveSnapshot` 分离建模。
- 组件包、游戏文件、Wine prefix、缓存和存档分区管理，卸载不得隐式删除存档。
