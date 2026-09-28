# Games 组件管理页面提案

## 背景

组件下载、断点续传、校验、安装、回滚和前台任务已具备，但 LocalGamesActivity 尚无组件入口，用户无法查看 16 个运行组件的安装与任务状态，也无法执行下载、暂停、继续、重试和回滚。

## 目标

- 在 LocalGamesActivity 增加“游戏库/组件”一级切换，默认保持游戏库。
- 从 bundled index、持久 ComponentTask 和 active/previous pointer 组合页面状态。
- 支持下载、暂停、继续、重试、取消和回滚，Activity 只提交命令。
- Activity 重建后重新读取持久状态，不重复创建任务。

## 非目标

- 不实现游戏导入、详情、RuntimeProfile 或启动链。
- 不修改下载、归档格式和组件 CDN。
- 不引入 Compose、数据库或新的网络依赖。

## 用户可见行为

用户可在组件页按 Runtime/Wine 分类查看版本、体积、安装状态和下载进度。任务失败可重试，暂停可继续，已安装且存在 previous 版本时可确认回滚。

## 影响范围

- `:games` Activity/layout/string/drawable。
- 组件 UI snapshot repository、active pointer reader 和路径集中定义。
- 既有 ComponentInstaller/Service 改为使用统一路径与读取模型。

## 风险与回滚

- UI 轮询只读私有 Repository，不持有 worker；移除组件 tab 即可回滚 UI。
- active pointer reader 严格校验，异常只显示错误，不修改版本目录。
- 保留现有游戏库空态与主 app 集成路径。
