## ADDED Requirements

### Requirement: GLIBC 组件状态以 launcher runtime 为准

系统 MUST 区分 Games 私有 verified install 与 `$PREFIX/glibc` launcher runtime 可用状态。

#### Scenario: 组件只存在于 Games 私有目录

- **WHEN** GLIBC 组件已有 private active receipt 但 Host probe 不可用
- **THEN** 组件页显示需要激活并提供激活动作
- **AND** launch preflight 报组件缺失，不得把 private receipt 当作可执行 runtime

### Requirement: RootFS 继续消费 verified private component

#### Scenario: RootFS 源组件已下载

- **WHEN** RootFS profile 执行 preflight
- **THEN** 系统从 Games immutable active receipt 读取组件版本
- **AND** 不要求该源组件发布到 GLIBC runtime

### Requirement: 激活不重复解包或转移资源所有权

#### Scenario: 激活已有组件

- **WHEN** 用户激活已有 verified component
- **THEN** Service 校验 active receipt 与当前目录
- **AND** Host 从 prepared directory 发布组件
- **AND** 不重新下载、不重复解析归档、不删除 Games 私有 version directory

### Requirement: 组件索引向后兼容

#### Scenario: app 读取组件索引

- **WHEN** bundled 或 remote index 的 schemaVersion 为 1 或 2
- **THEN** app 解析交付字段
- **AND** 其它 schema 明确失败

### Requirement: 干净 Termux 环境可创建 GLIBC 前缀

#### Scenario: 设备没有额外安装 patchelf 或 p7zip

- **WHEN** 用户从空数据下载推荐的 GLIBC 组件并创建前缀
- **THEN** Box64 interpreter 已正确时不要求 patchelf
- **AND** 基础前缀与 DirectX overlay 从组件内预展开目录安装
- **AND** 前缀写入 bootstrap 与 Wine package marker

### Requirement: Games 独立准备 X11 Host bridge

#### Scenario: 终端模式从未安装 X11 启动命令

- **WHEN** 任一 runtime 准备启动游戏
- **THEN** Orchestrator 在 preflight 前请求 Host 幂等安装 APK 内置 bridge
- **AND** preflight 只在 launcher 与 embedded loader 同时存在时认可 `termux-x11`
- **AND** Games 不依赖终端集成 X11 的 Activity 或设置动作
