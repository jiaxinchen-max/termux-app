## ADDED Requirements

### Requirement: Games 使用独立的消费级暗色视觉体系

Games Activity MUST 使用近黑背景、分层深色 surface、高对比正文和单一青色主强调色，系统状态栏和导航栏 MUST 与页面一致。

#### Scenario: 打开游戏库

- **WHEN** 用户进入 Games 主界面
- **THEN** 首屏 MUST 先展示游戏内容和导入操作
- **AND** MUST NOT 以白底表单或顶部文本 Tab 作为主视觉

### Requirement: 游戏库以封面网格呈现

游戏库 MUST 使用两列封面卡片，卡片 MUST 展示名称、可执行文件摘要和目录权限状态，并保持现有详情跳转与异步封面加载行为。

#### Scenario: 已导入游戏

- **WHEN** 游戏库加载一个或多个游戏
- **THEN** 每个游戏 MUST 作为独立封面卡片显示
- **AND** 点击卡片 MUST 打开对应游戏详情

### Requirement: 主导航位于底部

Library、Components、Settings MUST 通过底部图标和文字导航切换，重建 Activity 后 MUST 恢复已选页面。

#### Scenario: 切换到组件页并重建

- **WHEN** 用户选择 Components 后 Activity recreate
- **THEN** Components MUST 保持选中
- **AND** 组件目录 MUST 继续加载和刷新

### Requirement: 详情首屏优先回答运行状态

详情页 MUST 在配置字段之前展示封面、目录权限、启动主操作和运行配置/存储快捷入口；编辑字段 MUST 位于高级设置区。

#### Scenario: 打开可访问游戏详情

- **WHEN** 目录权限可用
- **THEN** 用户 MUST 能在技术字段之前看到状态与启动按钮
- **AND** 原保存、封面、重授权、删除行为 MUST 保持
