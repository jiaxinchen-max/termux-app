# Games 消费级 UI 重设计

## 总体方案

采用竞品调研中的“内容优先 + 技术能力渐进披露”原则：

```text
Games dark shell
  ├─ Library: 状态胶囊 → 导入 Hero → 封面网格
  ├─ Components: 页面说明 → 分类组件卡片 → 任务动作
  └─ Settings: 模式卡片 → Terminal 入口

Game detail
  └─ 16:9 cover → 状态 → primary launch → quick actions → advanced fields
```

颜色和文字样式全部由 `Theme.TermuxLocalGames.Dark.NoActionBar` 提供，避免 Activity 内写颜色。

## Activity 与导航

- `LocalGamesActivity` 移除顶部 TabLayout，使用固定底部 `BottomNavigationView`。
- Library 顶部展示产品标题和小型运行环境状态；导入 Hero 是唯一高权重入口。
- 游戏项使用两列 `GridLayout`，卡片以横向封面为主体，下方展示名称、EXE 和权限状态。
- 页面重建继续保存整数 selectedTab；重新选择组件页仍触发刷新。
- 详情布局保留现有 ViewBinding id 与事件处理，仅调整视觉层级。

## Service 与任务状态

不变。组件下载、暂停、恢复、回滚和启动操作继续调用现有 API。

## 数据、存储与迁移

不变。没有 schema、偏好或用户数据迁移。

## 组件下载与安装

组件页面沿用现有目录和任务状态，卡片改为无高对比边框的深色 surface，进度和主操作使用青色强调。

## X11、输入与运行会话

不变。本 change 不触碰 X11、输入或会话 host。

## 受保护路径

不修改主 app manifest、TermuxActivity、TermuxService、X11、native 和脚本。修改范围不命中 protected path policy。

## 测试策略

- AndroidTest：底部导航切页和 recreation；两列游戏库加载与权限状态。
- 编译：`:games:testDebugUnitTest`、`:games:assembleDebug`、`:app:assembleDebug`。
- 静态：module boundary、Harness strict run。
- 设备：1080×2400 真机检查主库、组件、设置、详情，保存截图并核对首屏层级、暗色状态栏、底部导航和点击链路。

## 风险与回滚

按 proposal 执行；所有行为 id 和调用路径保持，回滚不影响持久数据。
