# Design: 双 App 交互模式

## 模式与进程边界

`AppExperienceMode` 仅允许 `TERMINAL`、`GAMES`。主 app 持有持久化；Games 通过 `LocalGamesHost` 查询、切换和显式打开纯终端。模式在 Application/Activity 创建阶段读取，切换后由主 app 安排冷重启，不在已初始化 X11 runtime 的进程内修改。

## 入口路由

TermuxActivity 保持系统 Launcher Activity，进入 `onCreate` 后在任何 X11/Terminal UI 初始化前路由：

- `TERMINAL`：按现有路径初始化完整 Termux + X11。
- `GAMES` 且无显式纯终端标记：清空当前入口并打开 LocalGamesActivity。
- `GAMES` 且由 Games Host 显式请求终端：初始化纯 TerminalView/session 页面。

LocalGamesActivity 在 Games 模式下是产品根页，隐藏返回导航；在 Terminal 模式下仍作为从 Termux 进入的普通二级页。

## TermuxActivity 能力裁剪

纯终端路径不得：

- 创建或注册 `TermuxX11HostClient`、`LorieViewRuntimeController`；
- 创建 `TermuxScreenView` 或 attach Display Surface；
- 注册 X11 广播接收链、启动 X11 server、浮球、X11 偏好与右侧面板；
- 响应终端/X11 Surface 切换手势。

纯终端路径保留 TermuxService 绑定、TerminalView、TerminalSession client、左侧 session 列表、新建/切换/关闭 session 和终端输入工具栏。
左侧栏隐藏设置、Games 入口、备份/恢复、Shortcut 和 Wine/容器区域；返回键在软键盘关闭后结束纯终端 Activity，恢复 Games 根页。

## Games X11 所有权

Games 模式下只有 GameSessionActivity 挂载 `LorieViewRuntimeController`。游戏启动进程仍由 TermuxService 承载，可按 LaunchSpec 选择 APP_SHELL 或 TerminalSession。选择 TerminalSession 仅决定是否生成可查看的 session，不改变 X11 UI 所有者。

## 模式切换

Games 设置页展示当前模式。选择另一模式后显示确认：重启会终止当前 UI 进程，活跃游戏/终端任务必须先安全退出。确认后主 app 原子写入模式并安排 Launcher 冷启动；取消不写入。

## 兼容

无持久值时默认 `TERMINAL`，保持升级用户现有行为。非法值回退 `TERMINAL`。模式存储位于主 app 私有 SharedPreferences，不写入 Termux prefix。
