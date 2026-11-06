# 组件安装会话框过程信息（2026-10-31）

修复范围：Runtime / Native Prefix 的组件准备日志，以及独立组件会话框。

- 原先在 Dialog.onCreate 调用刷新，isShowing 为 false，轮询未启动；改为 onStart 投递刷新。
- 准备器按父任务 ID 增量收集当前子组件任务日志，覆盖下载字节数、百分比、校验、安装和激活；关闭再打开可读取持久日志。
- 准备输出可滚动；真正的 TerminalSession 出现后，上方保留准备输出，下方显示实际终端。
- 日志在后台读取，每次最多 48 KiB；只读关联任务，不混入其他安装。
- 入队/执行准入失败即使没有组件任务记录，也持久化具体错误并通知父安装，不再等待不存在的子任务直到超时。
- Native 环境的 Termux glibc 安装入口显式读取组件任务状态与组件日志。RootFS 仍在基础组件准备后创建正式任务，未改变安装互斥规则或运行模式。

编译和测试仅使用指定编译机 120.24.191.156:7022。

- :termux-ui:testDebugUnitTest：521 项通过。
- :termux-x11:testDebugUnitTest：108 项通过。
- :termux-usb:testDebugUnitTest、:app:assembleDebug 成功。
- 新增 4 项测试覆盖父子日志隔离/增量读取、UTF-8 有界读取/子日志截断、任务记录创建前的失败、路径校验。
- 最终 APK SHA-256：20c99497294b34e519921a727a0a54dceed29734ab3e87ad8dde417d96db6ed4。

模拟器 emulator-5554 上通过 App 正式界面操作：

1. 导入共享存储 wfm.exe；运行配置选择 Native / WineD3D / Wine 9.0-staging，保存触发真实组件准备。会话框显示校验、安装、激活；关闭后点击游戏重新打开，准备日志保留，实际 Termux 安装终端持续输出。
2. 最终 APK 中，通过运行配置选择 Wine 8.18-staging 并保存。为覆盖真实网络下载，将已有该版本缓存临时改名保留；未修改任务或就绪记录。
3. 实际下载 70,962,032 字节，组件日志记录 68 次 DOWNLOADING 状态，App 会话框显示 0% → 99%，随后 VERIFYING、VERIFIED、INSTALLING、INSTALLED、激活和前缀准备。
4. 新下载文件与原缓存 SHA-256 均为 b2fcb0974112a1e4c41ed1a9ccc9de325e0caa743cbe5c601ffba933c7b2befd；哈希一致后删除临时重复缓存。

安装结果：组件 INSTALLED，父前缀任务 SUCCEEDED（2026-10-31 12:23:33 +08:00）。通过 App 删除临时游戏记录，游戏库恢复 0 项，原始 wfm.exe 保留。

最终父任务：prefix-64c090f0-251a-460d-9071-a0203c8d10c8。
组件任务：104927d7-5b8f-49d2-91fc-87b91d72f9d4。

证据目录：/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/component-console-progress-20261031/

本次 UI 验证覆盖 Native 前缀真实组件准备和终端交接；未重建已有 RootFS，也未卸载/重装 Termux glibc。对应显示链路已编译，基础日志行为由上述单元测试覆盖。
