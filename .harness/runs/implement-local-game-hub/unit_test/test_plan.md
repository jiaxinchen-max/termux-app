# Test Plan

## 自动化测试

- 任务状态机和恢复。
- EXE 识别和 shell 参数引用。
- Range、ETag、partial、摘要校验和原子安装。
- RuntimeProfile 映射和数据迁移。

## Android 生命周期测试

- Activity 重建、横竖屏、后台/前台、Service 重绑定和应用进程恢复。

## X11/输入/运行会话测试

- 首帧判定、DInput/XInput、触控 profile、安全退出和异常清理。

## 真机矩阵

- 首期至少一个 Snapdragon/Turnip 设备，轻量 2D、DX9、DX11 游戏各一个。

## 回归

- TermuxActivity、TerminalSession、TermuxBox、组件安装和 X11 基础流程。
