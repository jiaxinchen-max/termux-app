# IDE 按需组件

## ADDED Requirements

### Requirement: 按需获取重型组件
系统应在首次使用 Gradle 项目同步时获取固定版本 tooling，打开 IDE 与普通编辑不依赖网络下载。

#### Scenario: 首次同步
- **WHEN** 用户启动同步且没有匹配的完整本地组件
- **THEN** 显示下载进度并支持取消、错误反馈及重试，下载校验完成后才运行同步

#### Scenario: 缓存复用
- **WHEN** 匹配版本及摘要的组件已存在
- **THEN** 直接复用，不重复联网下载，断网仍可启动该组件

### Requirement: 校验和事务
系统应以主 APK 固定的大小、SHA256 和协议约束组件，外部缓存不可直接执行。

#### Scenario: 损坏或不兼容
- **WHEN** 缓存被修改、下载不完整或协议不匹配
- **THEN** 拒绝运行并提供恢复方式，不覆盖上一份完整安装，不修改用户项目

### Requirement: 保持标准安装包
系统应继续生成原命名和类型的标准 APK，保留可离线查看的许可证，不自动安装 APK。

#### Scenario: 构建交付
- **WHEN** 执行标准 assembleDebug
- **THEN** arm64 输出仍为 termux-app_apt-android-7-debug_arm64-v8a.apk，且不内含重型 tooling JAR
