# Design: Local Games app entry and quality gates

## App entry

TermuxActivity 抽屉 header 增加固定 ImageButton。点击后先关闭 drawer，再通过 app 内 `TermuxLocalGamesEntry` 调用模块公开 `LocalGames.createLaunchIntent()`。该 adapter 不保存状态，不依赖模块 Activity 实现类，也不把游戏页面嵌入 TermuxActivity。

## Regression matrix

- Games：全量 JVM、Android Instrumentation、lint、AAR 和 module boundary。
- App：全量 JVM、debug assemble；入口 adapter、AppShell/TerminalSession Host adapter 和现有 TermuxActivity 纯 JVM 回归。
- TermuxBox：app 内 repository/container 相关 JVM 测试与 app assemble。
- X11：termux-x11 Java/native debug assemble；Games 会话 Android 测试覆盖 attach/recreate/安全退出状态。
- Device：API 30 arm64 模拟器执行现有 Instrumentation。实体手柄、真实游戏与 GPU 样本保持显式未验证，不伪造完成状态。

## Acceptance boundary

自动化和模拟器门禁全部通过后，可关闭主入口、自动测试和 Harness 证据任务。实体设备矩阵只有取得对应硬件/游戏样本并记录结果后才能关闭。
