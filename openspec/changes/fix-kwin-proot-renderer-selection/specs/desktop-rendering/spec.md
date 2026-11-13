# KWin PRoot 分层渲染规格

## Purpose

让用户独立配置宿主 KWin 合成器与 Debian Plasma 容器的渲染方式，按照实际设备能力完成初始化与错误诊断，避免模拟器因被假定为 Adreno 设备而无法安装，并保留真实硬件与软件后端的明确区别。

## ADDED Requirements

### Requirement: KWin PRoot 独立组合

系统 MUST 在创建和设置中提供宿主 System EGL/Zink 与容器 LLVMpipe/Zink 的独立选择，支持四种组合的配置和对应执行路径，不要求两层同名；保留已有显式宿主 LLVMpipe 配对且不得静默改值。

#### Scenario: 新建默认模式
- **WHEN** 用户新建 KWin PRoot
- **THEN** 默认宿主 System EGL、容器 LLVMpipe，分别显示职责，不宣称默认使用硬件加速。

#### Scenario: 选择四种组合
- **WHEN** 用户选择 System EGL/LLVMpipe、System EGL/Zink、Zink/LLVMpipe 或 Zink/Zink
- **THEN** 配置可独立保存并逐层传入初始化；是否就绪由实际后端与窗口验证决定，不因两层不同名而拒绝。

### Requirement: 依据实际后端验证

系统 MUST 依据所选层实际 EGL/Vulkan 能力验证 KWin PRoot，不把 Zink 无条件等同于 Freedreno；宿主和容器库 ABI 必须正确。软件后端必须明确标记，不得冒充硬件；其他桌面的专属硬件门禁不受影响。

#### Scenario: 模拟器使用 System EGL
- **WHEN** 模拟器选择宿主 System EGL 与容器 LLVMpipe
- **THEN** 不要求 Freedreno ICD，验证该层选定能力，成功后才允许记录初始化完成。

#### Scenario: 模拟器选择 Zink
- **WHEN** 模拟器显式选择某层 Zink 且该层具有可运行的 Vulkan 后端
- **THEN** 使用并记录明确后端验证所选 Zink 路径，软件 Vulkan 必须显示其 CPU 属性，窗口成功后才可声称此组合在该设备可用。

#### Scenario: 后端错误
- **WHEN** ICD 缺失、ABI 错误、探针失败或窗口不能工作
- **THEN** 报告具体失败层与原因，保持所选配置，不自动切换渲染器、不写就绪成功。

### Requirement: 保留安装与交付状态

系统 MUST 在失败与重试之间保留基础 prefix、目标容器及用户数据；最终 APK 必须可普通安装，保持既有签名和资产外置约定。

#### Scenario: 包安装后初始化失败
- **WHEN** Plasma 包已安装而渲染初始化失败
- **THEN** 显示初始化失败原因并支持显式重试，不以删除容器作为修复步骤。

#### Scenario: 交付 APK
- **WHEN** 构建 ARM64 交付包
- **THEN** 名称保持 termux-app_apt-android-7-debug_arm64-v8a.apk，最终包无 testOnly=true 限制、证书正确且不重新内嵌主题/API deb。
