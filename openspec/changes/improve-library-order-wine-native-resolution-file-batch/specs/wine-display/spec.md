# Wine 原生分辨率

## ADDED Requirements

### Requirement: Native 分辨率可保存并适用于两个后端

Wine 分辨率选择 MUST 增加本地化“Native（窗口原生尺寸）”，Native／PRoot 后端、游戏／Wine 桌面启动均适用；MUST 保留既有固定值及其默认行为。配置 MUST 持久化 `native`，不能因实际测量而改写为一次性的像素尺寸。

#### Scenario: 选择、保存与重建
- **WHEN** 用户选择 Native，保存设置后重启 App 或重建编辑页
- **THEN** 仍显示 Native，现有自定义固定分辨率也继续可用。

### Requirement: 每个新任务只有一个有效数值几何

系统 MUST 在新任务冻结前取得实际会话窗口的有效像素尺寸，并使任务请求、LaunchSpec、Wine 虚拟桌面、X server、X11 显示及坐标映射一致。MUST 尊重游戏方向、沉浸及窗口模式，MUST NOT 用 dp 或总屏幕尺寸替代可用窗口像素。

#### Scenario: Native／PRoot 下开始新游戏或 Wine 桌面
- **WHEN** 用户在任一后端使用 Native 启动
- **THEN** 启动 shell 仅收到已验证的正整数数值几何，显示与运行时尺寸一致，配置仍保存 `native`。

#### Scenario: 准备暂停恢复与无效尺寸
- **WHEN** 组件／prefix 准备使任务暂停并恢复，或启动时无法取得有效窗口尺寸
- **THEN** 恢复使用该任务已持久化的尺寸；缺尺寸必须在启动进程前明确失败，不悄悄选择固定尺寸。

#### Scenario: 重入活动任务与下一次启动
- **WHEN** 用户旋转、改变窗口后重入活动任务，或停止后重新启动
- **THEN** 活动任务继续使用已冻结尺寸；新的任务才按新窗口重新测量，不重新安装已有容器。
