## ADDED Requirements

### Requirement: Games 游戏库按方向自适应

Games MUST 使用同一游戏库状态提供横竖屏布局。横屏 MUST 使用顶部导航和大尺寸游戏内容，竖屏 MUST 使用底部导航和紧凑游戏网格。

#### Scenario: 横屏进入 Games

- **WHEN** 用户在 Games 模式横屏进入游戏库
- **THEN** 页面 MUST NOT 展示“游戏库/组件/设置”手机式底部三栏
- **AND** MUST 展示游戏封面、详情、更多和导入快捷入口

#### Scenario: 旋转为竖屏

- **WHEN** Activity 配置重建为竖屏
- **THEN** 已加载游戏和当前库状态 MUST 保持
- **AND** 主导航 MUST 使用竖屏底部形态

#### Scenario: 手动切换横竖屏

- **WHEN** 用户点击游戏库主界面的横屏或竖屏按钮
- **THEN** Activity MUST 请求目标方向并按对应布局重建
- **AND** 游戏库数据和当前页面状态 MUST 保持

### Requirement: 游戏操作保留游戏库上下文

详情和更多操作 MUST 覆盖在当前游戏库上。横屏 MUST 使用居中 Dialog，竖屏 MUST 使用 Bottom Sheet。

#### Scenario: 打开游戏详情

- **WHEN** 用户点击一个游戏的详情操作
- **THEN** MUST 展示封面、名称、描述、布局入口和启动主操作
- **AND** 关闭后 MUST 返回原游戏库位置

#### Scenario: 打开更多操作

- **WHEN** 用户点击更多
- **THEN** MUST 可进入 PC 引擎设置、修改信息、权限修复、移除和按键设置
- **AND** 移除 MUST 二次确认

### Requirement: PC 引擎设置使用主从布局

PC 引擎设置 MUST 在横屏显示固定分类栏和独立滚动内容区，并 MUST 通过现有 RuntimeProfile Repository 持久化。

#### Scenario: 切换配置分类

- **WHEN** 用户从通用切换到图形或兼容性
- **THEN** 左侧选中状态 MUST 更新
- **AND** 右侧 MUST 只显示对应配置项
- **AND** 未保存的有效修改 MUST 已按现有规则持久化或显式提示

### Requirement: 启动状态面向用户表达

启动页 MUST 将持久 LaunchTask 映射为检查环境、准备环境、启动游戏三个用户阶段。主错误 MUST NOT 直接展示内部 `preflight_*` 名称。

#### Scenario: 目录权限失效

- **WHEN** preflight 返回 permission lost
- **THEN** MUST 显示目录权限失效的用户文案
- **AND** MUST 提供重新授权动作
- **AND** 原始错误和 logRef MUST 可从诊断区域查看

#### Scenario: 组件缺失

- **WHEN** preflight 返回缺失或版本不匹配组件
- **THEN** MUST 指明组件
- **AND** MUST 提供进入下载/修复链路的动作

### Requirement: 游戏会话提供覆盖式控制中心

GameSessionActivity MUST 在不离开 X11 会话的情况下打开右侧控制中心，至少提供键盘、输入设置、进程管理、日志和安全退出。

#### Scenario: 会话中按返回键

- **WHEN** 控制中心未打开且用户按返回键或执行系统返回手势
- **THEN** MUST 从右侧打开控制中心
- **AND** MUST NOT 展示退出确认或结束游戏

#### Scenario: 控制中心中按返回键

- **WHEN** 控制中心已打开
- **THEN** 返回键或返回手势 MUST 先关闭控制中心
- **AND** MUST NOT 直接结束游戏

#### Scenario: 切换控制中心分类

- **WHEN** 用户选择操作、性能、设置或键盘
- **THEN** MUST 在同一右侧容器内切换内容
- **AND** MUST 保持 X11 Surface、游戏进程和 LaunchTask 不变
- **AND** 可执行项 MUST 接入真实输入、进程、音量、亮度、键盘或日志能力，不得提供无效开关

#### Scenario: 请求退出

- **WHEN** 用户选择退出
- **THEN** MUST 展示未保存数据风险确认
- **AND** 确认后 MUST 通过 Orchestrator 取消持久 LaunchTask

### Requirement: 游戏 X11 与终端集成隔离

Games MUST 通过独立 X11 控件承载游戏画面和输入，不得让 `GameSessionActivity` 实现终端集成 Host/ActivityIntegration 协议；终端模式和游戏模式只能共享底层 X11 渲染、连接和输入原语。

#### Scenario: 游戏模式启动 X11 会话

- **WHEN** Games 打开持久 LaunchTask 对应的运行会话
- **THEN** `GameSessionActivity` MUST 只依赖独立游戏 X11 控件的窄接口
- **AND** MUST NOT 引用 `LorieViewRuntimeController`、`LorieViewRuntimeApi.Host` 或终端侧栏回调
- **AND** 游戏控制中心行为 MUST NOT 触发或改变 TermuxActivity 的抽屉、Session 管理和集成 X11 交互
- **AND** X11 画面 MUST NOT 显示终端 Extra Keys、终端偏好入口或终端工具栏占位

#### Scenario: 修改游戏会话 X11 设置

- **WHEN** 用户在游戏控制中心修改鼠标辅助、触控或其他 X11 会话设置
- **THEN** 设置 MUST 写入 Games 独立偏好命名空间
- **AND** 重启并进入终端+X11 模式后 MUST 保持终端模式原有偏好

### Requirement: 终端只作为可选运行日志

正常游戏流程 MUST NOT 依赖 TerminalSession。启用 session 运行模式或用户主动查看终端日志时 MAY 创建/打开 session。

#### Scenario: 默认隐藏终端启动

- **WHEN** 启动模式为隐藏终端
- **THEN** 成功启动 MUST 直接进入 GameSessionActivity
- **AND** 侧边栏 session 管理 MUST 保持原有职责
