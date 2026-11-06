# Desktop PulseAudio 验证（2026-11-04）

- 原因：Desktop 启动脚本没有启动宿主 PulseAudio；XFCE/Plasma 的 `proot-distro login` 及 GNOME 的清空环境启动链路没有传入 `PULSE_SERVER`。
- 参数：App spec 显式写入 `DESKTOP_AUDIO_MODE=host-pulseaudio`。启动前确认 Termux PulseAudio、loopback TCP endpoint 和 Android OpenSL ES/AAudio sink 可用。失败记录日志及非零状态，不继续无声启动。
- 容器连接：三种桌面均显式接收 `PULSE_SERVER=tcp:127.0.0.1:4713`；宿主服务共享，停止桌面不杀死其他客户端的音频服务。
- 安装及容器恢复支撑组件检查包含 `pulseaudio`，同时检查服务端与 `pactl`。

验证：

1. 本地 APK 构建、8 项 DesktopLaunchSpec 测试通过。
2. 5 项音频初始化测试通过：冷启动、复用 TCP 服务、已有 local-only 服务、启动/协议失败及 null sink 拒绝、组件缺失及非法模式拒绝。
3. 原 36 项安装合同测试及新增的恢复时补装音频组件测试通过。
4. emulator-5554：通过 App 创建临时桌面条目，复用已有 `debian-xfce4-1`，显式选择 System EGL，从卡片 Open 启动。冷启动日志显示 PulseAudio 就绪，容器 `xfce4-session` 环境包含目标 `PULSE_SERVER`。
5. 通过 XFCE GUI 的 Alt+F2 运行框启动 `paplay /tmp/desktop-audio-check.wav`，没有在测试命令中额外指定音频服务器。PulseAudio 显示容器 root 的 paplay TCP 客户端、未静音且未暂停的播放流，`OpenSL_ES_sink` 为 RUNNING；Android AudioFlinger 显示 Termux 客户端的 44100 Hz 活跃音轨、无 underrun、未内部静音。
6. 从 App Stop 后再次 Open，PulseAudio PID 保持 30021，桌面复用服务。再次通过 App Stop 结束测试。
7. 仅清理本轮临时桌面记录 `desktop-22a571f9`、其状态/日志/spec 及测试 WAV；原有容器保留。

实际 UI 音频播放验证范围为 PRoot XFCE；Plasma/GNOME 本轮完成参数传递修复，未分别执行播放验证。

本地证据：`/tmp/terminal-area-20261103/desktop-audio-build.log`、`desktop-audio-proof.log`、`desktop-audio-proof.spec`、`desktop-audio-run-command.png`、`desktop-audio-flinger-playing.txt`。

## Fold 全新安装补充

Native Plasma通过System Settings扬声器测试；Native XFCE通过实际终端执行paplay，冷启动与复用均取得Pulse和Android活跃音轨（44100 Hz、未静音、underrun0）。内置API/X11以外的旧独立APK均禁用。

发现升级后的共享服务识别缺陷：GNOME安装重装pulseaudio后，旧进程15786仍提供TCP服务，`/proc/15786/exe`指向`pulseaudio.dpkg-tmp (deleted)`；`pulseaudio --check`误判不存在，旧初始化器又启动19221，形成两个实例。初始化器现先检测指定`tcp:127.0.0.1:4713`，健康时直接复用并校验Android sink，不再依赖旧可执行文件的PID识别。

新增包替换场景，6项音频执行型回归通过，本地APK构建成功；新版UI验收通过：App终端pkg reinstall pulseaudio退出0，旧--check返回1但TCP健康；从GNOME卡片Open后仍只有原PID20537，exe为deleted，TCP健康且日志不含再次启动消息。证据pulse-reuse-ui-result.json及pulse-deleted-exe-healthy-endpoint-in-app.png。首次XFCE曾观察到TCP模块消失，但未取得当时卸载日志；不把重复实例修复等同于已证明消除所有间歇故障。
