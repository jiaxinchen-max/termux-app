# 本地游戏中心提案

## 背景

当前 TermuxBox 已具备 Wine、Box64、图形/音频组件、容器配置、X11 和手柄输入基础，但主要交互仍围绕容器、组件和终端 Session。目标用户关心的是“导入游戏、判断环境、启动、游玩和安全退出”，不应把终端或容器作为主要任务对象。

## 目标

- 新增独立 Android Library 模块 `:local-games`，由模块内的 `LocalGamesActivity` 承载本地游戏功能，不继续扩张 `TermuxActivity` 主界面职责。
- 主 `app` 通过 Gradle module dependency、入口调用和窄执行桥接集成；`:local-games` 不得依赖 `:app` 或引用 `com.termux.app.*`。
- 提供游戏库、游戏详情、本地导入、游戏级配置、启动进度、运行中控制和安全退出。
- Termux 终端作为隐藏执行环境，正常用户流程不展示 TerminalSession。
- 复用 TermuxBox 的 Wine/Box64/驱动组件管理、prefix 初始化和 X11/输入能力。
- 网络只用于运行组件索引与下载，支持持久任务、断点续传、校验、安装和失败恢复。
- 任务状态在 Activity 重建或应用进程恢复后仍可查询。

## 非目标

- Steam、EPIC 或其他商店账号接入。
- 游戏本体在线下载、DLC 和在线更新。
- 云存档、社区配置、在线兼容数据库和内容推荐。
- 云游戏、串流和复古模拟。
- 本 change 不重写 Termux 终端、X11 Server 或 Wine/Box64 本体。
- 规划确认前不修改业务代码。

## 用户可见行为

- 用户从独立 Activity 浏览本地游戏库，并导入包含 EXE 的本地目录。
- 导入流程给出候选主程序、路径权限、可写性和存储空间检查。
- 游戏详情显示本地封面、启动入口、运行组件、兼容预设和最近状态。
- 首次启动显示预检、组件准备、prefix 初始化、图形/音频启动、游戏进程和首帧等阶段。
- 游戏画面由 X11 承载，运行中可管理输入、基础性能设置和退出。
- 退出时执行 Wine、音频、X11、锁和临时文件清理；异常退出提供错误摘要和恢复动作。
- 运行组件下载可以暂停、继续，并在应用重启后恢复；完成后校验大小和 SHA-256。

## 影响范围

- 新增：`:local-games` Android Library，包含 Activity、UI、领域模型、持久化、组件任务和运行编排。
- 修改：`settings.gradle` 和 `app/build.gradle` 引入模块；主应用新增最小 `LocalGamesHost` 实现和入口。
- Manifest：Activity/模块内 Service 优先由 library manifest 声明并通过 manifest merge 集成；主 manifest 只处理主应用专属权限或组件。
- 适配：把可复用的 TermuxBox 组件/配置能力迁移或包装为不依赖 `app` 的模块实现；主应用只保留 TermuxService 执行桥接。
- 复用：`TermuxService`、`LorieViewRuntimeController`、WinHandler、组件包索引。
- 可能修改：`app/build.gradle`，仅在持久化或测试依赖无法使用现有能力时。

## 产品文档

- 状态：Required。
- 计划路径：`docs/product/local-games.md`。
- 在功能完成并经用户接受后写入最终能力、限制、导入/启动/退出流程和兼容性说明。

## 风险与回滚

- 风险：Activity 与 Service 生命周期错位导致游戏中断或状态丢失。
- 风险：shell 路径引用错误、组件半安装、X11/音频/输入残留。
- 风险：共享 Termux prefix 和运行组件的变更影响原有终端生态。
- 回滚：移除 `implementation project(':local-games')` 和主应用入口即可关闭新功能；保留现有 TermuxBox 入口和脚本；新增数据使用独立目录；组件安装保留旧版本并原子切换。
