## ADDED Requirements

### Requirement: 组件目录表达产品类型与真实交付

系统 MUST 将组件的产品类型、展示身份与下载交付字段分开建模，并且不得为缺少可验证 URL、大小和 SHA-256 的资源创建可下载条目。

#### Scenario: 展示现有 TermuxBox 资源

- **WHEN** 组件目录加载 Wine、Turnip、DXVK、Box64 或内部基础包
- **THEN** 页面按容器、GPU、DirectX、转译器或运行支持类型展示
- **AND** 主标题使用可读名称，技术 ID 仅作为辅助信息
- **AND** 下载任务仍使用原 package ID、版本、URL、大小和 SHA-256

#### Scenario: GameHub 槽位没有真实资源

- **WHEN** FEX、VKD3D、Steam 或其他槽位没有经过校验的交付资源
- **THEN** 系统不得将其显示为可下载或已支持组件
- **AND** 页面 MAY（可以）以不可操作的能力说明展示缺口

### Requirement: 组件索引向后兼容

系统 MUST 接受既有 schema v1 索引，并 MUST 严格校验 schema v2 的产品元数据，不得迁移或破坏既有任务和安装指针。

#### Scenario: 读取 v1 索引

- **WHEN** parser 读取既有 Runtime/Wine 条目
- **THEN** 系统按稳定 ID 规则补齐类型和展示元数据
- **AND** 交付字段与旧版本完全一致

#### Scenario: 读取非法 v2 条目

- **WHEN** type、runtime backend、字段集合或交付摘要不合法
- **THEN** 整个索引加载失败
- **AND** 系统不得启动该条目的下载或安装

### Requirement: 组件维护页面按 GameHub 槽位分组

系统 MUST 按 ImageFS、容器、GPU、DirectX、转译器、通用组件和运行支持的固定顺序呈现目录，并继续展示下载、暂停、恢复、重试、取消和回滚状态。

#### Scenario: 同类存在多个版本

- **WHEN** 多个 Wine descriptor 属于容器类型
- **THEN** 它们 MUST 位于同一容器 section
- **AND** 每项 MUST 显示版本名、框架、用途、安装或任务状态

### Requirement: 游戏配置复用组件目录

系统 MUST 使用同一目录为 Wine、GPU 和 DirectX 配置项提供真实可用选项，并按运行后端过滤不兼容项。

#### Scenario: 编辑 GLIBC 游戏配置

- **WHEN** 用户打开 GLIBC_TERMUX_BOX 游戏配置
- **THEN** Wine、GPU 和 DirectX 选择器只列出声明支持该 backend 的 descriptor
- **AND** 保存值使用 descriptor 的稳定技术 ID

#### Scenario: 旧配置引用目录外条目

- **WHEN** 已持久化配置引用当前目录不存在的条目
- **THEN** 页面 MUST 保留原值供用户识别
- **AND** 保存或预检 MUST 给出明确的不支持错误，不得静默替换

### Requirement: 物理归档不得被虚假拆分

系统 MUST 以真实归档和校验摘要作为安装原子；共享同一归档但无法独立安装的内容不得伪装成多个可独立下载条目。

#### Scenario: 展示 prefix-apps

- **WHEN** 目录只有 `prefix-apps` 整包且没有成员 manifest
- **THEN** 页面显示一个通用组件包
- **AND** 不生成 Mono、Gecko、字体或 PhysX 的独立安装任务
