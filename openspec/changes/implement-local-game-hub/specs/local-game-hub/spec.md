## ADDED Requirements

### Requirement: 本地游戏功能由独立 Activity 承载

系统 MUST（必须）使用独立的本地游戏 Activity 承载游戏库、导入、详情、配置、任务和运行会话，不得要求用户进入终端页面完成正常流程。

#### Scenario: 打开本地游戏中心

- **WHEN** 用户从应用入口打开本地游戏功能
- **THEN** 系统进入独立 Activity
- **AND** TermuxActivity 的终端会话和导航状态不被替换

### Requirement: 本地游戏功能具有独立 Gradle 模块边界

系统 MUST 使用独立 Android Library 模块封装本地游戏 Activity、UI、领域模型、持久化、组件任务和运行编排，并保持从主应用到功能模块的单向依赖。

#### Scenario: 主应用集成功能

- **WHEN** 主应用启用本地游戏功能
- **THEN** 主应用通过 Gradle module dependency 引入功能
- **AND** 通过模块公开 API 初始化 Host 并打开 Activity
- **AND** 不需要在 TermuxActivity 中实现游戏页面和任务状态

#### Scenario: 校验模块独立性

- **WHEN** 执行模块边界检查
- **THEN** `:local-games` 不依赖 `:app`
- **AND** 模块源码不引用 `com.termux.app.*`
- **AND** app 专属 TermuxService 能力通过模块定义的接口注入

### Requirement: 用户可以导入本地 Windows 游戏

系统 MUST 支持通过 Android 文件访问能力选择本地目录或 EXE，保存持续访问权限，并让用户确认主程序、工作目录和启动参数。

#### Scenario: 目录包含多个 EXE

- **WHEN** 导入目录中存在主程序、卸载器、崩溃报告器或启动器等多个 EXE
- **THEN** 系统显示排序后的候选和识别依据
- **AND** 用户可以手动选择并在之后修改

#### Scenario: 持久权限失效

- **WHEN** 已导入目录的访问权限失效
- **THEN** 游戏保留在库中但启动被预检阻断
- **AND** 系统提供重新授权入口

### Requirement: 网络仅用于运行组件

系统 MUST 将网络能力限制在运行组件索引、下载和校验，不得在本 change 中接入商店账号、游戏本体下载或云存档。

#### Scenario: 组件下载中断

- **WHEN** 下载在部分字节完成后被网络或进程中断
- **THEN** 系统保存 partial 和远端对象标识
- **AND** 恢复时使用 Range 从有效 offset 继续
- **AND** 远端对象变化或服务端不支持续传时安全重新下载

#### Scenario: 组件校验失败

- **WHEN** 文件大小或 SHA-256 与索引不一致
- **THEN** 系统不得安装或替换当前可用组件
- **AND** 提供重试和错误证据

### Requirement: 游戏启动是持久化结构化任务

系统 MUST 使用稳定 task-id 和显式状态机管理预检、组件、prefix、显示、音频、游戏进程、首帧、运行、停止和清理。

#### Scenario: Activity 重建

- **WHEN** Activity 因旋转或系统回收而重建
- **THEN** Activity 重新观察既有 task-id
- **AND** 不重复创建游戏进程或下载任务

#### Scenario: 启动失败

- **WHEN** 任一启动阶段失败
- **THEN** 系统保存失败阶段、错误码、日志引用和可恢复性
- **AND** UI 提供与错误相符的重试、修复或回滚动作

### Requirement: 终端只作为隐藏执行环境

系统 MUST 通过 Termux 执行后端运行 shell、Wine 和 Box64，但不得以终端缓冲区或终端 Activity 作为正常用户状态源。

#### Scenario: 启动游戏

- **WHEN** 用户点击启动
- **THEN** 用户看到游戏启动阶段 UI 或 X11 画面
- **AND** 不需要查看或操作终端 Session

### Requirement: X11 会话具有明确生命周期

系统 MUST 将 X11 连接和首帧状态纳入 LaunchTask，并在 Activity 与 Service 生命周期变化时保持一致。

#### Scenario: 进程已创建但没有首帧

- **WHEN** Wine 游戏进程存在但 X11 尚未出现可操作画面
- **THEN** 状态保持在等待首帧
- **AND** 超时后提供诊断而不是错误标记为运行成功

### Requirement: 安全退出保护本地状态

系统 MUST 提供安全退出，按顺序结束游戏、Wine、音频、X11 和输入，并清理锁和临时状态。

#### Scenario: 用户从 INGAME 退出

- **WHEN** 用户选择安全退出
- **THEN** 系统显示退出和清理阶段
- **AND** 完成后返回游戏详情或游戏库
- **AND** 不残留错误的运行任务、Wine 锁或输入映射

### Requirement: 原有 Termux 能力保持兼容

系统 MUST 保持既有终端会话、TermuxBox 容器和组件管理基本流程可用。

#### Scenario: 不使用本地游戏功能

- **WHEN** 用户按原路径使用 Termux
- **THEN** 终端启动、会话切换和命令执行行为不因新 Activity 改变
