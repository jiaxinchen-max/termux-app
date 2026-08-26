# Local Game Runtime Domain

## 概念

- Game：用户可识别的本地游戏资产，引用外部目录和主 EXE。
- RuntimeProfile：游戏运行所需的 Wine、Box64、图形、音频、分辨率、环境和输入配置。
- Component：可下载、校验、安装和回滚的共享运行组件。
- LaunchTask：一次从预检到清理的持久运行任务。
- GameSession：X11 首帧出现后到安全退出完成前的可操作会话。
- SaveSnapshot：配置、prefix 或存档的本地恢复点。

## Owner 边界

- UI Owner：`LocalGamesActivity` 及页面，只负责用户意图和状态展示。
- Task Owner：Orchestrator Service，负责任务状态、恢复、取消和清理。
- Execution Owner：Termux adapter 与 shell，负责进程和运行环境。
- Display/Input Owner：Termux:X11 runtime，负责 Surface、输入和控制器。
- Component Owner：组件仓库与下载服务，负责版本、partial、校验和原子安装。
- Integration Owner：主 `app` 的 `LocalGamesHost` adapter，只负责把 app 内 TermuxService 能力提供给 `:local-games`。

## 模块边界

- `:local-games` 拥有产品 UI、Activity、领域、数据、组件和任务编排。
- `:app` 依赖并初始化 `:local-games`，提供 Termux 执行 Host。
- `:local-games` 可以依赖 `termux-shared` 和 `termux-x11`，但不得依赖 `:app`。
- 只有 `com.termux.localgames.api` 是主应用可依赖的稳定接口。

## 不变量

1. Activity 销毁不得等价于游戏任务结束。
2. 一个 task-id 最多对应一个有效游戏进程组。
3. 未通过大小和摘要校验的组件不得进入已安装状态。
4. 组件安装失败不得破坏上一可用版本。
5. 终端显示内容不是任务状态事实源。
6. 游戏卸载默认不删除外部游戏目录和存档。
7. RUNNING 必须同时具备有效游戏进程和可操作 X11 会话证据。
8. 任务进入终态后必须完成或明确记录未完成的清理动作。
9. `:local-games` 不得引用 `com.termux.app.*`，主应用能力必须经过公开 Host 契约。
