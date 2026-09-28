# Games X11 会话增量规格

## Requirement: RUNNING 需要 renderer 首帧

系统 MUST 只在 X11 renderer 成功向当前 Android Surface 提交首帧后将 LaunchTask 标记为 RUNNING。

### Scenario: 仅建立 X11 连接

- **WHEN** X11 socket 已连接但 renderer 尚未成功提交帧
- **THEN** LaunchTask 保持 WAITING_FIRST_FRAME

### Scenario: 首帧早于脚本等待事件

- **WHEN** 首帧证据先到达，随后 JSONL 进入 WAITING_FIRST_FRAME
- **THEN** 系统持久保留证据并在应用等待事件时进入 RUNNING

## Requirement: 会话 Activity 不拥有进程

系统 MUST 在独立私有 Activity 中承载 Surface；Activity 重建或进入后台 MUST NOT 重启或终止隐藏任务。

### Scenario: 旋转设备

- **WHEN** GameSessionActivity 因配置变化重建
- **THEN** 新 Activity 观察同一 task-id 并重新挂载 Surface

## Requirement: 输入配置按 LaunchSpec 冻结

系统 MUST 使用 LaunchSpec 中冻结的 DInput/XInput mapper 和可选触控 profile，不在会话中隐式读取已变化的 RuntimeProfile。

### Scenario: 选择 DInput

- **WHEN** LaunchSpec inputProfileId 为 dinput
- **THEN** WinHandler 使用 DInput mapper

## Requirement: 安全退出显式且幂等

系统 MUST 在返回键时请求确认；只有用户确认后才提交取消，后台和普通销毁不触发退出。

### Scenario: 用户进入后台

- **WHEN** Activity 收到 onPause
- **THEN** 游戏任务继续运行且不写 cancel marker

### Scenario: 用户确认退出

- **WHEN** 用户在 INGAME 覆盖层确认安全退出
- **THEN** Orchestrator 提交取消，脚本完成进程和锁清理，任务进入 CANCELLED 或明确失败终态
