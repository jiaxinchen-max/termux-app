## ADDED Requirements

### Requirement: 用户可以查看组件目录与持久状态

系统 MUST 在组件页展示 bundled index 中的全部组件，并从持久任务和 active pointer 恢复状态。

#### Scenario: Activity 重建

- **WHEN** 用户停留在组件页并发生 Activity recreate
- **THEN** 组件 tab 保持选中
- **AND** 页面从 Repository 重新显示现有 task-id 的状态和进度
- **AND** 不重新创建下载任务

### Requirement: 组件页面只提交持久任务命令

系统 MUST 通过 ComponentTasks API 执行下载、暂停、继续、重试、取消和回滚，Activity 不得直接下载或解压。

#### Scenario: 下载任务失败

- **WHEN** 组件任务状态为 FAILED
- **THEN** 页面显示失败状态与错误摘要
- **AND** 用户可以对相同 task-id 执行 retry

#### Scenario: 回滚已安装组件

- **WHEN** active pointer 存在 previous 版本且用户确认回滚
- **THEN** 页面向前台 Service 提交 rollback 命令
- **AND** 完成后重新读取 active pointer 展示当前版本

### Requirement: 组件目录保持分组和可操作性

系统 MUST 按 Runtime/Wine 分组展示组件 ID、版本、体积、状态和可用动作。

#### Scenario: 读取现有 fixture

- **WHEN** 页面读取当前 `index-v1.json`
- **THEN** 展示 16 个组件且不遗漏 Runtime/Wine 分类
