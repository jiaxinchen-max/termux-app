## ADDED Requirements

### Requirement: LaunchTask 可持久恢复且不会重复启动

系统 MUST（必须）在调用 Host 前持久化稳定 task-id、冻结配置和初始任务状态。

#### Scenario: Activity 重建

- **GIVEN** game 已有非终态 LaunchTask
- **WHEN** 启动状态 Activity 重建并再次提交同一 game
- **THEN** 系统返回已有 task-id
- **AND** 不创建第二个 Host 任务

#### Scenario: 应用进程恢复

- **WHEN** Service 在非终态任务存在时重新创建
- **THEN** 系统重放未消费 JSONL 事件并核对 Host/lock/PID
- **AND** 无法证明存活时转为 `host_process_lost`，不得推断 RUNNING

### Requirement: LaunchSpec 是严格冻结且 shell-safe 的启动输入

系统 MUST（必须）冻结 Game 和 RuntimeProfile，MUST NOT 拼接或 eval 用户路径和参数。

#### Scenario: 特殊字符路径与参数

- **WHEN** 路径或参数含空格、单引号、美元符号或反斜线
- **THEN** 编解码后每个值保持字节语义一致
- **AND** EXE 及每个 argument 作为独立 argv 传给 Wine

#### Scenario: 非 POSIX SAF Provider

- **WHEN** Host 无法把 tree URI 验证映射为本地 canonical directory
- **THEN** 启动在执行脚本前以 `game_root_not_posix_accessible` 阻断

### Requirement: 启动执行方式可冻结选择

主应用 MUST（必须）默认通过 AppShell runner 执行启动脚本，并允许冻结配置选择 TerminalSession。

#### Scenario: 提交启动

- **WHEN** Orchestrator 提交有效 LaunchSpec
- **THEN** Host 使用稳定 shell-name、`NO_SHELL_WITH_NAME` 和冻结 runner 调用 TermuxService
- **AND** TerminalSession 模式不强制打开 TermuxActivity

### Requirement: 启动状态由 JSONL 事件驱动

脚本 MUST（必须）输出带 task-id 和单调 sequence 的 JSONL；Android MUST 严格验证后持久化再通知 UI。

#### Scenario: 重复或部分事件

- **WHEN** reader 遇到已应用 sequence 或末尾不完整 JSON 行
- **THEN** 重复事件被忽略，不完整行等待后续写入
- **AND** LaunchTask 不回退 stage/progress

#### Scenario: 尚无 X11 首帧

- **WHEN** Wine 已创建但后续 X11 会话尚未确认首帧
- **THEN** 任务最高阶段为 `WAITING_FIRST_FRAME`
- **AND** 不进入 `LaunchStage.RUNNING`

### Requirement: 取消、超时和清理幂等

系统 MUST（必须）在任意启动阶段响应取消，并允许重复清理。

#### Scenario: 用户取消

- **WHEN** 用户请求取消
- **THEN** Orchestrator 写入 cancel marker，脚本终止其拥有的子进程并清理 lock
- **AND** 任务最终为 CANCELLED

#### Scenario: 启动超时或宿主死亡

- **WHEN** 超时到达或 Host shell 消失且任务非终态
- **THEN** 系统记录稳定错误码和可恢复标记
- **AND** 重复 reconcile 不产生残余 lock 或第二次启动
