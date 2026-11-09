# 运行组件清理

## ADDED Requirements

### Requirement: 运行目录只展示并分发当前可用组件
系统应保留真实运行依赖与可选功能，保留自编译可选Box64 0.4.4，移除镜像预装后不再需要下载的独立Hangover包。

#### Scenario: PRoot选择Hangover
- **WHEN** 用户打开Wine选择器
- **THEN** Hangover作为容器预装项可选择，显示基础镜像状态，不提供独立Hangover下载。

#### Scenario: Native恢复prefix
- **WHEN** 用户准备任一支持的Native Wine版本
- **THEN** 保留fonts、scripts和prefix-apps及对应prefix依赖，所有清单来源可用，中文字体和DirectX恢复不受损。

### Requirement: 只支持当前RootFS配方
系统应只使用v12预装镜像配方，不打包旧v10/v11安装资产，不为旧任务执行安装或兼容回退。

#### Scenario: 旧配方输入
- **WHEN** 输入已废弃配方版本
- **THEN** 立即明确失败，不继续下载旧组件或调用旧安装脚本。

### Requirement: 仓库运行目录与APK一致
组件仓库两个公开目录应与APK的29项当前目录一致，包含当前镜像、全部prefix和Proton v2。

#### Scenario: 保留新版Box64
- **WHEN** 清理重复与过时安装条目
- **THEN** 保留自编译Box64 0.4.4的目录、探测和远端归档，不将版本选择接线缺口误判为无用组件；镜像构建输入、符号和桌面组件仍保留。

### Requirement: 内部自动依赖不提供重复安装条目
系统应从通用安装列表隐藏fonts、scripts、prefix-apps、默认Native Box64和PRoot基础镜像，自动依赖解析仍保留这些记录。设置提供环境构建与有意义的Wine、图形及Box64管理。用户后续确认的native-box64-multi-version变更要求在Box64专用版本页面提供内置0.2.7、0.4.4及自定义版本选择；这不恢复通用内部依赖安装条目。

#### Scenario: 无游戏环境准备
- **WHEN** 用户打开组件管理
- **THEN** 不显示字体、脚本、附加程序与基础镜像的单独安装项，仍能进入Wine/图形/自定义Box64管理。

#### Scenario: 保存游戏配置
- **WHEN** 所选游戏运行所需的内部依赖缺失
- **THEN** 既有自动准备流程安装依赖并恢复prefix，用户无需识别或手动安装内部组件。
