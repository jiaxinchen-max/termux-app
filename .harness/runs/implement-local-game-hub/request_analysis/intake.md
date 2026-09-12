# Request Intake

## 原始需求

- 参考盖世游戏的本地游戏交互与 UI。
- 使用 Termux App 的程序运行能力和生态，终端只作为真实任务的隐藏运行环境。
- 只考虑本地游戏，不接入 Steam/EPIC、云存档和游戏内容网络服务。
- 保留运行组件网络下载，并支持断点续传。
- 使用新的 Activity 承载功能。
- 按参考项目 `.harness` 组织开发流程。
- 将游戏模拟器功能抽离为独立 Gradle module，主 app 尽量只通过引入模块集成。

## 已确定边界

- 新入口：独立 `LocalGamesActivity`。
- 模块：独立 `:local-games` Android Library，依赖方向为 `app -> local-games`，禁止反向依赖。
- 主应用集成：module dependency、HostFactory 初始化和入口 Intent。
- 网络：仅运行组件索引、下载、续传、校验和安装。
- 执行：TermuxService/shell/Wine/Box64。
- 显示输入：Termux:X11、WinHandler 和触控 profile。
- 状态：持久 task-id 和结构化事件，不解析终端 UI。

## 人工确认

- 当前：Pending。
- 建议确认语句：`确认 proposal/design/specs/tasks，可以进入实现。`
