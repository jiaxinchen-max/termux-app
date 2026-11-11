# Wine进程桥接

## ADDED Requirements

### Requirement: 提供现有应用可调用的功能替代

系统必须提供可重建的x86-64 Windows PE wine-bridge.exe，保留显式目标启动、工作目录／亲和性参数及现有UDP调用接口，供当前Native／PRoot及Hangover启动链加载；不得以Linux或Android ELF替代。

#### Scenario: 启动带空格路径和参数
- 当启动脚本传入明确Windows目标、参数及工作目录
- 则真实程序收到完整参数并在指定目录启动；失败必须以错误退出，不静默启动其他程序。

### Requirement: 按现有线格式执行运行时管理

系统必须按已核对字节偏移实现INIT、EXIT、EXEC、KILL_PROCESS、LIST_PROCESSES／GET_PROCESS、SET_PROCESS_AFFINITY、MOUSE_EVENT／CURSOR_POS_FEEDBACK、KEYBOARD_EVENT及BRING_TO_FRONT。不完整或未知指令不得执行。拒绝非法报文之后仍须处理合法请求。

#### Scenario: 查询进程并操作窗口
- 当应用请求列表或对目标执行亲和性／窗口置前操作
- 则真实Windows进程信息能被现有Java接收方解析，目标操作通过Win32执行。

#### Scenario: 截断执行请求
- 当报文声明的文件名和参数无法完整包含在实际收到的数据报
- 则不执行缺失或截断的命令，不越界读写。

### Requirement: 包装器随真实任务结束

系统必须在目标游戏以及实际派生的游戏进程结束后自然退出，启动或必要准备失败应明确终止；不得固定超时杀死正常游戏，不得用PID数值大小代替父子关系，也不得自动回退旧EXE。

#### Scenario: 启动器退出而游戏继续
- 当启动器创建游戏进程后先退出
- 则包装器持续提供管理，直到游戏进程自然结束；无关新进程不应阻止结束。

### Requirement: 保持控制器现有数据链路

替代EXE不得改变或重复接管DLL直接访问Android的手柄协议，不占用7948／7949，不修改接口开关和映射保存。

#### Scenario: 游戏运行时切换接口
- 当用户在Controller页单独启停XInput／DirectInput
- 则已有DLL与Android路径按保存值工作，EXE替换不增加额外中转或prefix初始化。
