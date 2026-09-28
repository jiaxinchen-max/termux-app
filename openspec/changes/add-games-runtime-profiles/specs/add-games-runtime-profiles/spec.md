## ADDED Requirements

### Requirement: 每个游戏拥有独立且可恢复的运行配置

系统 MUST（必须）按 game-id 持久化 RuntimeProfile，并保存可选的上次成功快照。

#### Scenario: 首次打开配置

- **WHEN** Game 尚无持久 RuntimeProfile
- **THEN** 系统返回推荐默认配置
- **AND** 不在用户保存前隐式写文件

#### Scenario: 恢复上次成功配置

- **GIVEN** 该游戏存在上次成功快照
- **WHEN** 用户选择恢复
- **THEN** 表单切换到该快照
- **AND** 当前持久配置只在用户保存后改变

#### Scenario: 配置记录损坏

- **WHEN** schema、键集合、文件名/id、环境键或组件版本无效
- **THEN** Repository MUST 拒绝该记录
- **AND** MUST NOT 使用默认值覆盖损坏文件

### Requirement: TermuxBox 配置通过 Host 只读映射

Games module MUST（必须）通过公开 Host DTO 读取旧配置，MUST NOT 依赖 `com.termux.app.*`。

#### Scenario: 显式导入 container

- **WHEN** 用户选择一个 TermuxBox 配置
- **THEN** Wine、图形、DX、音频、分辨率、Box64、环境和输入字段被映射到当前表单
- **AND** 原 container.conf、当前 container 指针和 prefix 不发生变化

#### Scenario: 单条旧配置无效

- **WHEN** Host 无法映射某条 container
- **THEN** 该条被隔离为可诊断错误
- **AND** 其他有效配置仍可使用

### Requirement: 系统提供预设和确定性变更摘要

系统 MUST（必须）提供推荐、稳定、兼容、自定义四种模式，并输出字段级差异。

#### Scenario: 应用预设

- **WHEN** 用户选择推荐、稳定或兼容
- **THEN** 已定义字段被替换为该预设值
- **AND** 摘要按稳定字段顺序展示旧值与新值

#### Scenario: 选择自定义

- **WHEN** 用户选择自定义
- **THEN** 当前配置保持不变

### Requirement: 启动前预检阻断不可运行配置

系统 MUST（必须）在启动任务创建前检查目录权限、Host runtime、组件和存储预算。

#### Scenario: 目录不可访问

- **WHEN** SAF 权限丢失或 Provider 不可用
- **THEN** 预检返回对应稳定错误码
- **AND** 结果为 blocked

#### Scenario: 组件缺失或不匹配

- **WHEN** 必需组件不在 index、未安装或 active receipt 的版本/SHA 不匹配
- **THEN** 预检列出每个组件及原因
- **AND** 结果为 blocked

#### Scenario: 空间不足

- **WHEN** 可用私有存储小于新 prefix 保留量加缺失组件峰值预算
- **THEN** 预检返回 requiredBytes 与 availableBytes
- **AND** 结果为 blocked

#### Scenario: 全部条件满足

- **WHEN** 目录、runtime、组件和空间均满足
- **THEN** 预检结果为 ready

### Requirement: 配置页面不拥有启动或下载任务

Activity MUST（必须）只编辑配置和展示预检，不直接执行 shell、Wine 或网络下载。

#### Scenario: Activity 重建

- **WHEN** 配置页面因旋转或进程内重建重新创建
- **THEN** 未保存表单被恢复
- **AND** 不创建组件任务或 LaunchTask
