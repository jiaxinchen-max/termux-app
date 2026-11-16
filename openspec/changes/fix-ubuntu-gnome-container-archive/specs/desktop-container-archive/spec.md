# 桌面容器归档

## ADDED Requirements

### Requirement: Ubuntu GNOME 当前容器归档
系统 SHALL 支持 Ubuntu Resolute 的 Ubuntu GNOME PRoot 完整导出与恢复，并保留普通用户数据、应用、名称及显式渲染设置。

#### Scenario: 完整往返
- **WHEN** 停止的受支持 Ubuntu GNOME 容器导出后恢复
- **THEN** 系统使用新桌面 ID 和新 alias，保留名称，重名添加数字后缀，并在恢复完成前准备可直接启动的会话。

### Requirement: 恢复阶段准备
系统 SHALL 仅在恢复前置阶段准备宿主组件，在恢复初始化阶段重建机器相关状态；普通启动不得新增依赖检查、下载、修复或重试。

#### Scenario: 恢复到全新宿主环境
- **WHEN** 宿主缺少 GNOME50 组件
- **THEN** 从既有对应发布源安装必要宿主组件，不执行容器 apt、不创建另一容器、不替换备份内应用。

#### Scenario: 恢复新身份
- **WHEN** 原容器含旧会话文件或旧 runtime 链接，备份后恢复到新身份
- **THEN** 原用户配置保持，受管理会话文件按新身份生成；正式启动的 ready 路径与准备结果相同。

#### Scenario: 恢复准备失败
- **WHEN** 宿主依赖或会话初始化失败
- **THEN** 系统不发布成功桌面记录，保留原备份并按原事务清理本次目录。

### Requirement: 入口与归档范围一致
系统 SHALL 统一导出入口、发行版校验和元数据校验的支持范围。

#### Scenario: 未适配桌面
- **WHEN** 桌面为 Hyprland、独立废弃 GNOME 或 Native
- **THEN** 本次不开放归档，既有 XFCE 和 Plasma 归档行为保留。
