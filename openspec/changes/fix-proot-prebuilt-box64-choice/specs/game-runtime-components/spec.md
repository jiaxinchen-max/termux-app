# PRoot Box64 候选

## MODIFIED Requirements

### Requirement: 预编译 Box64 可按运行后端选择
版本页 MUST 展示与当前后端兼容的预编译 Box64。

#### Scenario: PRoot 尚未安装预编译版本
- **WHEN** 普通 Wine/Proton 用户打开 PRoot Box64 版本页且没有相应有效安装记录
- **THEN** 展示可下载的 Box64 0.4.5，不要求手动导入文件

#### Scenario: 独立组件已安装
- **WHEN** 组件库中存在对应版本的有效安装记录和目录
- **THEN** 展示已安装可选项，选择返回 rootfs_box64_0.4.5，默认使用该版本时正确标记当前选择

#### Scenario: 每游戏选择持久化
- **WHEN** 用户选择预编译版本并保存游戏设置、重新打开编辑器
- **THEN** 版本 ID 保留，并通过已有依赖和绑定流程用于启动，不重建 prefix 或替换其他游戏的版本

#### Scenario: 后端和翻译模式隔离
- **WHEN** 用户打开 Native 或 Hangover 翻译模式的版本列表
- **THEN** PRoot 独立 Box64 不作为兼容候选；Native 继续保留自己的 0.2.7/0.4.4
