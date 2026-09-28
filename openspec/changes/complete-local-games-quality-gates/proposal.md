# Proposal: Local Games app entry and quality gates

## Why

本地游戏模块已具备完整页面和运行链，但主应用仍无可见入口，总 change 的自动化回归和最终证据尚未收敛。

## What changes

- 在 TermuxActivity 左侧抽屉增加本地游戏入口，只调用 `LocalGames.createLaunchIntent()`。
- 增加主应用入口集成测试，验证显式 Intent 指向模块 Activity。
- 执行 Games、app、TermuxBox、TerminalSession、X11 和模块边界自动化回归。
- 汇总总 change 的 code/test/CI/device/pre-merge 证据与剩余实体设备矩阵。

## Non-goals

- 不在 TermuxActivity 内实现游戏业务页面或状态。
- 不删除或替换现有 TermuxBox 入口。
- 不以模拟器结果替代实体 DInput/XInput、真实 Wine 首帧和 GPU 兼容矩阵。
