# 变更要求

## ADDED Requirements

### Requirement: 核心组件入口集中环境操作
应用 SHALL 不再展示独立组件目录入口或目录页，核心组件下 Native/PRoot 保留环境状态、Build from network 和现有文件安装恢复等操作。

#### Scenario: 无游戏时管理环境
- **WHEN** 用户进入设置的核心组件并选择 Native 或 PRoot
- **THEN** 可执行现有环境构建，不出现组件分类菜单；PRoot 的现有备份恢复操作可见。

### Requirement: 修复跳转与版本选择保持可用
启动失败的组件修复操作 SHALL 跳转游戏对应后端的核心组件设置页。游戏编辑页 SHALL 继续支持各组件版本选择与手动导入。

#### Scenario: 修复后端环境
- **WHEN** 用户通过启动失败界面打开修复入口，包括复用已有 Activity
- **THEN** 展示对应后端环境设置，返回按设置层级导航，不自动构建或跳转旧目录。

#### Scenario: 选择游戏版本
- **WHEN** 用户编辑 Native 或 PRoot 游戏的 Wine、Box64 配置
- **THEN** 仍使用现有版本选择界面及独立多版本安装能力。
