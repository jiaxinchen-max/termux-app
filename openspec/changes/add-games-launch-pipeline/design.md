# Games 持久启动链设计

## 总体方案

```text
GameLaunchActivity
  -> LocalGameOrchestratorService
       -> FileLaunchTaskRepository
       -> LaunchSpecFactory / FileLaunchSpecStore
       -> LaunchEventLogReader / LaunchTaskStateMachine
       -> LocalGamesHost
            -> TermuxService ACTION_SERVICE_EXECUTE (APP_SHELL / TERMINAL_SESSION)
                 -> start_local_game.sh -> JSONL / lock / cancel marker
```

Activity 仅提交 launch/cancel command 并轮询持久 task snapshot。Service 是 Android 侧唯一编排者；shell 是 Wine 子进程和清理的 owner。任一事件先写入 Repository，再广播 UI 刷新。

## Activity 与导航

- `GameDetailActivity` 增加启动入口，只传 game-id。
- `GameLaunchActivity` 首次进入提交 launch；若 Repository 已有同 game 非终态任务则复用其 task-id。
- recreate 保存 task-id，页面恢复时只观察，不再次提交。
- 取消写入 task 私有 cancel marker；强制取消由 Host 终止稳定 shell-name 对应进程。

## Service 与任务状态

- `LocalGameOrchestratorService` 是模块私有前台 Service，串行处理 launch、cancel 和 reconcile。
- LaunchTask schema v2 增加 `lastEventSequence`、`updatedAt` 和 `environmentFingerprint`；Repository 支持 v1 只读迁移并采用 `.tmp` + rename。
- 合法阶段只能单调前进；终态不可变；重复/旧序号事件忽略；跳过关键阶段、task-id 不匹配或非法终态转移被拒绝。
- 本分片最多进入 `WAITING_FIRST_FRAME`。`LaunchStage.RUNNING` 仅允许后续 X11 首帧信号触发。
- 服务恢复时重放未消费 JSONL，核对事件、lock 和 Host shell；不能证明进程存活时写入 `host_process_lost` 可恢复失败并清理。

## LaunchSpec 与路径

- LaunchSpec 冻结 task/game、canonical game root、EXE/working-directory 相对路径、逐项 arguments、RuntimeProfile、prefix、事件/日志/锁/cancel 路径和 timeout。
- 文件格式是严格固定键 Base64 行协议：文本值为 URL-safe Base64，无 padding；计数有上限；未知键、重复键和路径逃逸均拒绝。
- SAF `content://` 不传给 shell。Host 仅解析 `com.android.externalstorage.documents` 的 primary/volume tree，canonical path 必须位于对应卷且可读；其他 Provider 返回 `game_root_not_posix_accessible`。
- 环境指纹由规范化 LaunchSpec 的 SHA-256 计算，用于恢复时识别配置漂移。

## Termux Host adapter

- `LocalGamesHost.startLaunch()` 构造显式 TermuxService intent，runner 来自冻结 LaunchSpec、shell-name=`local-games-<task-id>`、create-mode=`NO_SHELL_WITH_NAME`。
- executable 固定为 Termux prefix `/bin/sh`，arguments 仅含已部署的模块脚本和私有 spec 路径。
- TerminalSession 模式只创建命名会话，不强制导航 TermuxActivity；两种模式均不修改 TermuxService。
- `isProcessAlive()` 与 `stopLaunch()` 通过 PID 查询/终止进程；若进程已消失，取消仍通过 marker 和持久状态幂等完成。

## JSONL 事件与脚本

- 每行包含 `schemaVersion/taskId/sequence/state/stage/progress/timestamp/pid/exitCode/errorCode/recoverable/message/logRef`。
- 脚本以私有 lock `mkdir` 实现单任务互斥，trap 负责结束子进程、删除 lock 和临时状态。
- 脚本不 `source` LaunchSpec、不 `eval` 参数；逐键 Base64 解码并通过 shell positional arguments 调用 EXE。
- 启动链发出 PRECHECK、PREPARING_PREFIX、STARTING_DISPLAY、STARTING_AUDIO、STARTING_GAME、WAITING_FIRST_FRAME；游戏退出后 CLEANING 和终态。脚本不发 `RUNNING`。
- 取消、超时、prefix 缺失、runtime 缺失、目录逃逸和 Wine 非零退出使用稳定错误码。

## 数据、存储与迁移

```text
files/games/launches/tasks/<task-id>.properties
files/games/launches/specs/<task-id>.launchspec
files/games/launches/events/<task-id>.jsonl
files/games/launches/logs/<task-id>.log
files/games/launches/locks/<task-id>/
files/games/launches/cancel/<task-id>.cancel
files/games/prefixes/<game-id>/
files/games/runtime/start_local_game.sh
```

文件名和记录 id 必须一致。私有脚本从 module asset 按 SHA-256 复制到应用私有目录后 chmod 0700；更新采用临时文件原子替换。终态任务保留 spec/event/log 供诊断，本分片不做历史淘汰。

## 组件下载与安装

启动前复用既有 LaunchPreflightEvaluator；缺失/不匹配组件阻断，不在启动 Service 内隐式下载。

## X11、输入与运行会话

脚本可启动显示/音频后端，但本分片不创建 X11 Activity 或 Surface。`WAITING_FIRST_FRAME` 是本阶段的最高活动阶段；后续会话分片通过结构化首帧事件进入 RUNNING。

## 受保护路径

- `runtime-script-critical`：新增 `games/src/main/assets/local-games/start_local_game.sh`。用户当前指令明确批准结构化启动脚本；要求 shell quoting、兼容和回滚测试。
- 不修改 `termux-service-critical` 路径；使用既有公开 service action 和 app 内 adapter/broker。
- Games library manifest 新增模块私有 Activity/Service，不修改 `app/src/main/AndroidManifest.xml`。

## 测试策略

- JVM：LaunchTask v1/v2 持久化、状态机事件序号/非法转移、LaunchSpec 编解码/指纹、路径逃逸、JSONL partial/replay、取消/超时映射。
- shell：临时假 runtime 验证空格、单引号、美元符号和逐项 argument；验证 JSONL、lock、cancel 和 cleanup。
- AndroidTest：Activity recreate 不重复 task、manifest 私有组件、Host fake 下恢复/取消。
- 回归：Games JVM/AndroidTest/lint/assemble、app compile、module boundary、子/父 Harness strict。

## 风险与回滚

按 proposal 执行。真实设备 X11 首帧、输入和完整 Wine prefix 生命周期明确留到下一分片，不以本分片测试替代。
