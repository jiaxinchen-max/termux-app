## ADDED Requirements

### Requirement: 组件索引由 Games 模块严格解析

系统 MUST 拒绝未知 schema、重复/非法 ID、非 HTTPS、无效大小和摘要。

#### Scenario: 读取现有索引

- **WHEN** Games 读取 `termux-box-packages/index-v1.json`
- **THEN** 现有组件全部映射为 ComponentDescriptor
- **AND** 原 TermuxBox asset path 保持可用

### Requirement: 安装失败不得破坏当前版本

系统 MUST 在 staging 完成和 receipt 验证后切换 active pointer，并保留 previous 版本用于回滚。

#### Scenario: 解压或 pointer 切换失败

- **WHEN** 新版本安装任一步骤失败
- **THEN** 旧 active pointer 和版本目录保持不变
- **AND** 任务记录失败证据

### Requirement: 组件任务由持久前台 Service 持有

系统 MUST 在 Activity 销毁或进程重建后根据持久任务恢复活动组件任务。

#### Scenario: Service 重建

- **WHEN** 存在 QUEUED、DOWNLOADING、VERIFYING、VERIFIED 或 INSTALLING 任务
- **THEN** Service 重新提交相同 task-id
- **AND** 不恢复 PAUSED、CANCELLED 或 INSTALLED 任务
