# Box64版本选择

## ADDED Requirements

### Requirement: 内置Box64版本独立安装
系统应将每套Native内置Box64安装在按组件ID隔离的目录，安装、重装或准备一个版本不得覆盖其他版本或共享bin/box64。

#### Scenario: 安装两个版本
- **WHEN** 依次安装0.2.7与0.4.4并重装其中一个版本
- **THEN** 两个可执行文件与各自安装记录独立保留，另一个版本内容不变。

#### Scenario: 安装输入不完整
- **WHEN** 归档没有可执行的bin/box64
- **THEN** 立即安装失败，不破坏已有版本或发布新就绪记录。

### Requirement: 每游戏使用准确版本
系统应保存每个游戏的Box64版本ID，自动准备对应组件，并通过Native glibc loader调用它。未指定时明确选择0.2.7，不以“当前共享文件”决定版本。

#### Scenario: 两游戏选择不同版本
- **WHEN** 游戏A选择0.2.7，游戏B选择0.4.4
- **THEN** 各自依赖只要求相应内置Box64，启动分别使用对应路径，切换B不改变A的执行版本。

#### Scenario: 指定版本文件缺失
- **WHEN** 指定版本不存在但其他版本或共享bin/box64存在
- **THEN** 明确失败，不改用其他Box64，不重建Wine prefix。

### Requirement: 保持后端与自定义边界
系统应继续支持独立自定义版本，Native内置ID不得在PRoot选择中生效，PRoot既有版本路径和Hangover限制保持。

#### Scenario: 自定义版本
- **WHEN** 游戏指定已安装的custom-box64版本
- **THEN** 准备不下载内置Box64，启动仍使用自定义独立目录。
