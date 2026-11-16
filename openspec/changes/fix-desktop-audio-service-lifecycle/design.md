# 设计

将共享音频管理集中为 Python 标准库脚本，由 desktop_common.sh 的准备与启动入口调用。使用同一应用目录锁串行化检测/替换，避免两个桌面重复启动；锁随进程退出释放。通过显式 tcp:127.0.0.1:4713 的 pactl 查询和 Android OpenSL ES/AAudio sink 判断复用条件，不以 pulseaudio --check 或 PID 文件代替真实端点。

可用端点直接复用。不可用时枚举 /proc，只识别当前 UID、comm=pulseaudio、命令首项为本 PREFIX/bin/pulseaudio 的进程；记录并再次验证进程启动时间，避免 PID 复用误杀。先 SIGTERM，限时后才 SIGKILL；无法确认身份或无法停止则退出，不盲目抢占端口或删除 PID 文件。确认旧进程退出后执行一次 --start（15 秒），再验证目标端点和真实 Android sink（每条 pactl 3 秒）。缺组件、无实际输出或启动失败返回非零。不存在无声继续、安装、桌面重试或渲染 fallback。

保留 loopback 端点、PULSE_SERVER 传递、共享音频在正常关闭桌面后继续存活。新辅助脚本由现有 LaunchScriptInstaller 随其他运行脚本部署，恢复 wrapper 显式引用脚本路径。针对健康复用、包替换、失效服务替换、冷启动、无真实 sink、信号失败、PID 复用、并发串行及超时编写执行型测试。设备测试前停止 App，通过 App 启动链验证两个原桌面，不改库存/spec/容器。

回滚仅撤销本次音频管理脚本及调用改动。七个既有脏子模块不动，无已声明的受保护业务路径。
