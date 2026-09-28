# Design: Optional TerminalSession launch mode

## Model

`LaunchExecutionMode` 仅允许：

- `APP_SHELL`：后台 AppShell，保持当前默认行为。
- `TERMINAL_SESSION`：创建名称为 `local-games-<task-id>` 的终端会话。

RuntimeProfile 持久化用户选择。创建 LaunchSpec 时将其冻结，Service 重启或任务重放只能读取 LaunchSpec 中的值。

## Host boundary

Games 通过 `LaunchRequest` 将执行模式传给 `LocalGamesHost.startLaunch()`。主 app adapter 继续发送 `TermuxService.ACTION_SERVICE_EXECUTE`：

- `APP_SHELL`：runner=`APP_SHELL`、background=true。
- `TERMINAL_SESSION`：runner=`TERMINAL_SESSION`、background=false、session action 仅切换到新会话，不主动打开 Activity。

两种模式使用同一脚本、参数、working directory、JSONL、日志文件、取消标记和任务状态机。`TermuxService` 无需修改。

## Compatibility

- RuntimeProfile v1 缺少字段时迁移为 `APP_SHELL`，新写入使用 v2。
- LaunchSpec v1/v2 缺少字段时迁移为 `APP_SHELL`，新写入使用 v3。
- 保留旧 Java 构造器，默认 `APP_SHELL`。

## UI

运行配置页面增加执行模式下拉框。选择 TerminalSession 后，用户可切换到 Termux 查看命名会话；游戏启动流程本身不被 TermuxActivity 抢占。
