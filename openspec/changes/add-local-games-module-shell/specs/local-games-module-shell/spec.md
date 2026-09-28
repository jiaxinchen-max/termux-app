## ADDED Requirements

### Requirement: 本地游戏模块可独立编译

系统 MUST 提供独立 Android Library 模块 `:games`，并保持主 app 到功能模块的单向依赖。

#### Scenario: 编译模块

- **WHEN** 执行 `:games:assembleDebug`
- **THEN** 模块独立编译成功
- **AND** 模块不依赖 `:app`
- **AND** 模块源码不引用 `com.termux.app.*`

### Requirement: 主应用通过公开 API 集成

主应用 MUST 只通过模块公开 API 初始化 HostFactory 和创建启动 Intent。

#### Scenario: 应用进程启动

- **WHEN** `TermuxApplication` 初始化
- **THEN** 注册无 Activity 引用、无阻塞 I/O 的 HostFactory

#### Scenario: 创建本地游戏入口 Intent

- **WHEN** 主应用调用模块公开入口
- **THEN** 返回指向模块内 `LocalGamesActivity` 的显式 Intent
