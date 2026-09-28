# Games 盖世式本地游戏体验重构设计

## 总体方案

```text
LocalGamesActivity / Games Shell
  ├─ portrait: local tabs + compact shortcuts + game grid + bottom navigation
  └─ landscape: top navigation + large game cards + shortcut tiles
       ├─ GameLaunch presentation: dialog / bottom sheet
       └─ GameActions presentation: dialog / bottom sheet

GameRuntimeProfileActivity
  └─ landscape master-detail: category rail + setting content + recommended preset

GameLaunchActivity
  └─ stage 1 check → stage 2 prepare → stage 3 start → GameSessionActivity

GameSessionActivity
  ├─ GameHub-style session shell + right control drawer
  └─ X11SessionView (standalone widget)
       └─ game-only adapter → shared X11 renderer/input primitives

TermuxActivity
  └─ existing terminal + integrated-X11 implementation (unchanged)
```

业务动作使用统一模型，横竖屏只决定 presentation。游戏库、配置、组件和启动继续通过现有 Repository/API 访问。

## Activity 与导航

- `LocalGamesActivity` 只保留 Library 作为主内容；组件维护和应用模式设置进入工具菜单。
- 游戏库复用 `layout/activity_local_games.xml`，由 Activity 按方向重排共享视图；卡片使用 `layout/item_local_game.xml` 与 `layout-land/item_local_game.xml` 分别承载紧凑网格和横屏大封面。
- 横屏顶部导航只展示本地范围可用入口，未实现网络能力的图标不创建无效页面。
- 游戏卡提供独立的详情和更多点击目标，不再整卡跳转到重型详情 Activity。
- `GameDetailActivity` 暂保留为编辑/诊断兼容入口；主链路改用 `GameLaunchDialog` 和 `GameActionsDialog`。
- 横屏 Dialog 使用居中宽面板；竖屏 Window 使用底部 gravity、全宽圆角背景。

## PC 引擎设置

- `GameRuntimeProfileActivity` 横屏使用固定左侧分类和右侧滚动内容。
- 第一版分类映射现有 profile：通用、图形、兼容性、输入、组件/诊断。
- 配置保存沿用现有 Repository 和校验逻辑，不引入全局 Save 状态。
- “推荐方案”调用现有 preset/diff/apply 能力；缺失组件继续走组件任务。

## Service 与任务状态

- Activity 不直接启动 shell。
- `GameLaunchActivity` 观察持久 `LaunchTask`，将内部 `LaunchStage` 映射为三段用户阶段。
- error code 映射为标题、说明和单一修复动作；原始 code/logRef 放在可展开诊断区域。
- TerminalSession 保持可选；不会因进入启动页自动创建，除非用户配置为 session 模式或主动查看终端日志。

## 数据、存储与迁移

- 不修改 `Game`、`RuntimeProfile`、`LaunchSpec`、`LaunchTask` 序列化。
- 增加的 UI selected category、drawer section 和展开状态只保存在 `savedInstanceState`。
- 游戏 Action Dialog 每次按 game-id 加载数据，不缓存可变 Repository 对象。

## 组件下载与安装

- 主游戏库不再把 Components 作为一级导航。
- 组件维护入口保留在工具菜单；启动和 profile 校验继续展示缺失/版本不匹配组件。
- 本 change 复用现有断点续传、摘要校验、staging 和回滚，不修改组件任务协议。

## X11、输入与运行会话

- 游戏模式与终端集成 X11 使用两套独立上层实现；两者只共享 `termux-x11` 的渲染、输入和连接原语。
- `GameSessionActivity` 只持有独立 `X11SessionView` 控件，不实现 `LorieViewRuntimeApi.Host`、`ActivityIntegration`，也不引用终端版 `LorieViewRuntimeController`。
- `X11SessionView` 封装 X11 Surface、连接、输入、软键盘和进程能力，以窄接口向 Games 暴露；终端抽屉、侧栏、Fragment 和 Activity 回调不进入 Games Activity。
- Games X11 偏好使用独立命名空间，修改鼠标辅助、触控等游戏设置不得覆盖终端模式偏好。
- 新布局分三层：X11 Surface、常驻会话工具按钮、右侧 Drawer/遮罩。
- Drawer 第一版提供操作、设置、键盘、进程和退出；性能页面只展示当前可支持的能力，不伪造底层能力。
- 返回键/返回手势由游戏壳统一处理：关闭 Drawer 或打开 Drawer；只允许显式退出。
- 进程管理通过 `X11SessionView` 的游戏会话接口调用，Games 不引用 X11 内部 `ProcessInfo`。

## 受保护路径

- 修改 `games/**`、`termux-x11` 的中立 Java 控件/偏好注入层，以及本 change 的 Harness/OpenSpec 产物。
- 不修改 `TermuxActivity`、`TermuxService`、`termux-x11` native/X11 协议和启动脚本。
- 2026-08-28 用户明确要求游戏模式与终端+X11 为两套独立实现，并要求 X11 重构为独立控件；据此扩展 protected-path approval。

## 测试策略

- JVM：阶段映射、错误映射、Action Model 和 profile 分类映射。
- AndroidTest：横竖屏游戏库、Dialog/Bottom Sheet、配置分类、启动状态、Session Drawer 返回键。
- 构建：`:games:testDebugUnitTest`、`:games:assembleDebug`、`:app:assembleDebug`。
- 静态：Games module boundary 和 Harness strict-run。
- 设备：真实导入游戏，验证横屏库、详情/更多、profile、启动、X11 首帧、Drawer、退出；至少保存横屏和竖屏截图。

## 风险与回滚

按 proposal 执行。UI presentation 和状态映射均可独立回滚；任务、组件和游戏数据不迁移。
