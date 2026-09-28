# Games X11 会话提案

## 目标

在独立 Games Activity 中承载 Termux:X11 Surface，把连接和 renderer 首帧证据持久化到 LaunchTask，并提供游戏级输入映射、运行中覆盖层和安全退出。

## 范围

- X11 Surface、连接状态和真实首帧回调。
- LaunchTask 显示证据迁移及 `WAITING_FIRST_FRAME -> RUNNING`。
- LaunchSpec 冻结输入 mapper/触控 profile 选择。
- INGAME 覆盖层、软键盘、输入控制和安全退出。
- Activity 旋转、前后台、返回键和任务终态恢复。

## 非目标

- 不重写 X11 Server、Wine、Box64 或输入协议。
- 不实现 FPS/性能采样、存档快照和网络功能。
- 不声明真实游戏兼容性或真机 GPU 矩阵已通过。

## 兼容与回滚

LaunchTask v1/v2 和 LaunchSpec v1 可继续读取；移除 Games 会话 Activity、termux-x11 依赖和首帧回调即可回滚，不改变原 TermuxActivity X11 路径。
