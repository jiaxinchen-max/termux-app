# Spec Delta

## Purpose

为 termux-app 用户提供在 Android 设备内管理和编辑 Android 项目、准备 Termux 原生构建环境、执行构建并导出 APK 的完整路径，同时保持已有终端、桌面、游戏和安装流程的兼容性，明确工具链支持与验证边界。

## ADDED Requirements

### Requirement: 项目与编辑
系统 SHALL 提供开发入口、创建 Java Android 项目、导入或打开工作区项目、文件树与文本编辑保存，并阻止文件路径越出所选工作区。

#### Scenario: 创建并重新打开
- **WHEN** 用户创建项目、修改源码后保存并重新打开
- **THEN** 系统显示同一工程及保存内容，路径不与游戏或容器数据混用

#### Scenario: 非法路径
- **WHEN** 文件操作通过相对路径或符号链接越出工作区
- **THEN** 系统拒绝操作且不修改工作区外的数据

### Requirement: native 工具链发现
系统 SHALL 使用 Termux native 环境，发现兼容工具版本，复用已有依赖，仅为缺失项提供按需安装；NDK 就绪须经过相应兼容验证。

#### Scenario: 已有依赖
- **WHEN** 所需兼容版本已安装
- **THEN** 系统复用该安装且不重复下载，不全局覆盖其他会话的环境

#### Scenario: 仅有编译器
- **WHEN** Clang 存在但标准 NDK 目录或 externalNativeBuild 验证不通过
- **THEN** 系统显示具体限制，不把全部 NDK 能力标记为就绪

### Requirement: 构建监督
系统 SHALL 提供项目构建、持续日志、明确的任务结果和任务级取消，首次运行工程脚本前告知其执行性质。

#### Scenario: 页面重建
- **WHEN** 构建期间页面重建
- **THEN** 用户能恢复同一任务状态和日志，不重复启动构建

#### Scenario: 取消或失败
- **WHEN** 用户取消任务或构建进程失败退出
- **THEN** 系统显示正确终态，取消只影响本次任务的进程，不结束其他终端或桌面

### Requirement: APK 交付
系统 SHALL 展示实际构建产物并允许导出，APK 安装交由用户手动执行；工具链不得打包增加到主 APK 中。

#### Scenario: 本次构建失败
- **WHEN** 旧 APK 存在而本次构建失败
- **THEN** 系统不得将旧文件展示为本次成功产物

#### Scenario: 导出
- **WHEN** 构建成功且用户选择导出 APK
- **THEN** 系统将所选产物写入用户指定位置，不自动安装，不改变主应用既有 APK 命名规则

### Requirement: 兼容现有功能
系统 SHALL 保留原有终端、桌面和游戏数据及全局最低系统兼容范围；不可用的开发能力应清楚提示。

#### Scenario: 设备不满足开发要求
- **WHEN** 当前设备不能运行所选开发工具
- **THEN** 系统说明缺失条件，现有终端及其他功能仍可使用

### Requirement: 高级设置中的 IDE 开关
系统 SHALL 在 Settings 的 Advanced 子菜单中提供持久化 IDE 开关，默认关闭；仅开启时在横屏顶部和竖屏底部显示 IDE 标签。

#### Scenario: 开启并重启
- **WHEN** 用户在 Advanced 开启 IDE 并重新打开应用
- **THEN** IDE 标签保持可见，其他现有标签均保留

#### Scenario: 关闭并返回已有页面
- **WHEN** 用户关闭 IDE 后返回此前打开的根页面或 IDE 页面
- **THEN** 所有标签栏隐藏 IDE，IDE 页面返回可用根页面，不删除项目或工具链
