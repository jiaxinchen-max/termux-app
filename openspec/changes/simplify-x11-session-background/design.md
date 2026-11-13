# 设计

## 现状
X11SessionView 创建 IdlePresentation.UI 的 TermuxScreenView，view_x11_idle_ui.xml 使用黑色背景和 X/雷达图标。view_x11_display.xml 的根、frame、stub 也有黑色底。activity_game_session.xml 的 game_session_waiting 背景使用 local_games_session_waiting，日夜色均含透明度；GameSessionActivity 根据任务阶段切换等待层。游戏菜单外层已经透明。

## 实现方案
1. UI 空闲布局改为纯色空容器，不再包含 X 与雷达装饰；终端空闲布局不改。雷达控件已无任何布局使用，同步删除 LorieViewRuntimeController 中仅服务于该装饰的雷达更新方法与调用。
2. 为嵌入会话使用主题色背景，并覆盖 UI 模式对应的根、frame 与 stub 的黑底，确保留白与空闲背景一致。以主题属性或日夜资源提供不透明颜色，避免 termux-x11 反向依赖 termux-ui。
3. 游戏等待层背景改为透明，保留现有进度、状态提示及可见性逻辑，不移除任务状态反馈。真实画面不得被新背景覆盖。

## 边界
Activity 只改视觉配置；Service、任务状态机、shell、X11 连接与首帧判断、输入、存储、组件下载均无行为变更。终端模式保持现状。未发现额外受保护路径清单；不修改权限、签名、构建发布配置。

## 验证与限制
审查资源引用和空控件处理，运行 Java/资源编译；检查浅色/深色背景均不透明且等待层透明。人工回归覆盖游戏和桌面的连接前、首帧后、断线，确认输入与菜单正常。仓库 Harness bootstrap、strict gate、OpenSpec CLI 与基线 specs 缺失，只记录真实可执行的检查，不声称完整门禁通过。业务代码在规划确认后修改。
