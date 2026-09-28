# Games X11 会话设计

## Owner 边界

- `:games` 拥有 GameSessionActivity、任务显示证据、INGAME UI 和退出决策。
- `:termux-x11` 只暴露连接、renderer 首帧和现有输入控制能力，不感知 Games 领域。
- LocalGameOrchestratorService 是 LaunchTask 唯一写 Owner；Activity 通过显式命令提交显示证据。
- 启动脚本继续拥有 Wine/X11/音频进程与清理 trap，不依赖 AppShell 或 TerminalSession 容器生命周期。

## 首帧链路

`rendererRedrawLocked -> eglSwapBuffers success -> LorieView -> RuntimeRegistry -> RuntimeController -> GameSessionActivity -> Service command -> LaunchTaskStateMachine`

renderer 对每个新 Android Surface 只报告一次首帧。首帧必须同时满足真实 root buffer 绘制和 swap 成功；Surface created、X11 socket connected 均不足以进入 RUNNING。

## 持久状态

LaunchTask schema v3 新增 `displayConnected`、`displayUpdatedAt` 和 `firstFrameAt`。连接可反复变化，首帧时间只首次写入。脚本事件 cursor 不因显示事件递增，避免与 JSONL sequence 冲突。

若首帧先于 `WAITING_FIRST_FRAME` 到达，状态机先保存证据；随后应用脚本等待事件时再提升到 RUNNING。终态不接受显示事件。

## 输入

LaunchSpec schema v2 冻结 `inputProfileId`。格式为 `xinput`、`dinput` 或 `<mapper>:<touch-profile-id>`。RuntimeController 设置 WinHandler mapper，并按可选数字 id 加载既有触控 profile；找不到 profile 时保持画面可用并显示输入告警。

## 生命周期与退出

- 旋转重建 Surface/controller，不新建 LaunchTask，不重启隐藏任务。
- `onPause` 不退出游戏。
- 返回键打开安全退出确认；普通 Activity destroy 不写 cancel。
- 确认退出后先持久 cancel marker，再向 Host runner PID 发送 TERM；脚本按 Wine、wineserver、图形/音频、锁顺序幂等清理。
- 强制退出仍由启动状态页显式提供。

## 受保护路径

涉及 `x11-input-critical`、`android-entry-critical`、`runtime-script-critical` 和 `build-boundary-high`；父 change 已取得用户批准，本子 change 记录同一授权。
