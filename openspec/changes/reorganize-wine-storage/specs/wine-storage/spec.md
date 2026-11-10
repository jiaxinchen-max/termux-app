# Wine 存储布局

## ADDED Requirements

### Requirement: Wine 工作数据统一目录
系统 MUST 在 front/wine 下保存容器配置与可写工作目录。

#### Scenario: 创建和读取容器
- **WHEN** 保存或读取容器及其 Native/Proot prefix
- **THEN** 使用 front/wine 下的对应容器路径，不访问旧 front/containers

### Requirement: 共享模板独立保护
系统 MUST 在 front/wine/templates 保存 Proot 预构建 prefix 缓存，并保护共享模板。

#### Scenario: 准备和备份恢复
- **WHEN** 准备 prefix、备份或恢复模板
- **THEN** 统一使用新缓存路径，不重建 prefix 或回退旧路径

#### Scenario: 清理单个游戏或容器
- **WHEN** 枚举容器、执行孤儿清理或卸载游戏
- **THEN** templates 不被当成容器，也不删除共享模板

#### Scenario: 全新布局
- **WHEN** 新版本访问存储
- **THEN** 不迁移旧数据，不兼容旧路径；RootFS、桌面发行版及 Native 组件存储不变
