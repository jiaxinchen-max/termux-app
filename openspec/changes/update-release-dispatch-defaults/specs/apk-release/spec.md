## ADDED Requirements

### Requirement: 手动发布使用本期默认版本及整体功能说明

系统 SHALL 在现有手动发布表单中将 release_tag 与 release_name 默认值均显示为 v1.0.8.1，且发布说明按整个新增 termux-ui 模块的主要能力概述。

#### Scenario: 打开手动发布表单

- **WHEN** 用户打开 Attach Debug APKs To Release 的手动执行表单
- **THEN** 默认 tag 和标题均为 v1.0.8.1，说明概述 Wine、桌面、组件、文件、输入显示及备份恢复能力

#### Scenario: 保留现有发布执行方式

- **WHEN** 仅更新本次发布元数据
- **THEN** 触发方式、构建与上传作业保持原有配置，不增加 tag push 自动发布

#### Scenario: 中英文双语发布说明

- **WHEN** 用户打开默认发布说明或使用默认说明发布
- **THEN** 同时展示中文和English两段，每段以相同粒度概述相同的六类新增能力
