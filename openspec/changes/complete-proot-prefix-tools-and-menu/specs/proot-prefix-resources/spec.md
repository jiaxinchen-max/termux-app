# PRoot Wine 公共工具资源

## ADDED Requirements

### Requirement: PRoot 预构建 prefix 提供可用的公共工具菜单

Hangover、PRoot Wine 10 和 PRoot Proton 的预构建 prefix 必须包含适配所选运行时的公共工具入口及其必要资源。每个保留入口必须有实际可用目标，不得依赖 Native 专用主机路径。

#### Scenario: 首次准备 PRoot 程序

- **WHEN** 用户通过 App 保存并准备受支持 PRoot 运行时的程序
- **THEN** 恢复后的 prefix 包含公共工具菜单及对应工具资源
- **AND** 菜单目标和工作目录使用该 prefix 或所选 Wine 的实际路径

#### Scenario: 适配 Native 工具入口

- **WHEN** 原始入口引用 Native 专用目录、安装命令或不支持的工具
- **THEN** 只有目标和依赖完成 PRoot 适配后才能加入菜单
- **AND** 不添加指向缺失程序的入口

### Requirement: 保持 Wine 架构与预构建恢复边界

工具补齐必须保持 Hangover 与普通 Wine 的架构相关系统文件来源，不得直接用 Native 的系统 DLL 覆盖。资源通过现有资产包和缓存恢复，不重跑 wineboot，不增加自动升级、历史迁移或初始化回退。

#### Scenario: 恢复不同 Wine 运行时

- **WHEN** 分别恢复 Hangover、Wine 10 和 Proton 的预构建包
- **THEN** 各自获得对应公共工具和菜单，原运行时系统 DLL 来源保持正确
- **AND** APK 无需内嵌完整工具安装包或整套 Native prefix

#### Scenario: 验证已存在的 prefix

- **WHEN** 本次验证涉及此前已准备的 prefix
- **THEN** 通过显式移除或重新准备使用新资产
- **AND** 不以运行时自动补丁、版本号升级或 wineboot 掩盖资产缺失
