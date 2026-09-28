# Games 持久启动链提案

## 背景

Games 已具备游戏库、SAF 导入、组件交付和游戏级 RuntimeProfile，但 LaunchTask 尚未持久化，也没有可供主应用执行的冻结 LaunchSpec。直接复用可见 TermuxSession 会污染终端列表，现有 `start_termux_box.sh` 也无法输出任务级结构化事件。

## 目标

- 持久化 LaunchTask、稳定 task-id、事件序号和环境指纹，Activity 重建及应用进程恢复时不重复启动。
- 将 Game、RuntimeProfile 和 Host 解析出的文件系统目录冻结为 LaunchSpec；路径和参数通过无 `eval` 的 Base64 行协议传入脚本。
- 主应用 Host adapter 默认使用 Termux AppShell；后续增量允许按冻结配置选择命名 TerminalSession。
- 启动脚本直接执行目标 EXE，并以 JSONL 输出有序状态、PID、退出码和稳定错误码。
- 支持取消 marker、超时、宿主进程死亡核对和幂等清理。
- 提供独立启动状态 Activity，可恢复观察已有任务。

## 非目标

- 不挂载 X11 Surface，不接入输入，不把 Wine 进程创建等同于首帧或 `LaunchStage.RUNNING`。
- 不实现任意 SAF Provider 到 POSIX 路径的隐式复制；本分片仅支持 Host 可验证映射的本地外部存储树。
- 不修改 TermuxService、现有 TermuxBox 启动入口或旧 container 数据。
- 不在本分片实现 prefix 快照和资产管理。

## 用户可见行为

用户从游戏详情点击启动后进入独立状态页。页面恢复同一 game 的活动 task-id，展示结构化阶段、进度、错误和日志引用，并可取消。目录无法映射、profile/组件/runtime 不满足或脚本异常时，在启动 Wine 前失败并给出稳定错误码。

## 影响范围

修改 `:games` 的 API、domain、data、runtime、Service、Activity、资源、模块资产和测试；扩展 app 内 Games Host adapter；同步父变更任务和 Harness 台账。

## 风险与回滚

- 路径风险：Java 和 shell 双重校验 canonical containment，LaunchSpec 字符串 Base64 编码，参数逐项恢复，不拼接命令。
- 重复启动：先持久化 task/spec，再以稳定 shell-name 和 `NO_SHELL_WITH_NAME` 提交选定 Termux runner。
- 进程恢复：JSONL 可重放，序号幂等；锁/PID 不一致时转为可恢复失败，禁止猜测运行成功。
- 回滚：移除新 Activity/Service/API/asset 和私有 `files/games/launches` 数据即可；不影响游戏目录、旧 TermuxBox 和终端会话。
