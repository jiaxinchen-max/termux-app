# 桌面库存

## ADDED Requirements

### Requirement: Native 固定入口完整且配置安全
管理页 MUST 在空库存和部分库存下提供 XFCE、Plasma、Ubuntu GNOME、Hyprland Native 固定入口；MUST 保留所有已有桌面记录和自定义值；重复刷新 MUST 不新增重复配置。初始化 MUST 不安装软件、不启动会话、不写成功标记。

#### Scenario: 全新数据
- **WHEN** 打开 Native 管理页
- **THEN** 四个环境可见且可使用主操作安装，计数为 4，无 Native 新建或删除固定入口操作。

#### Scenario: 已有配置和标识碰撞
- **WHEN** 已有自定义 Native、PRoot 或占用默认标识的记录
- **THEN** 保留所有记录，仅补缺失的 Native 类型，新增标识不覆盖任何文件。

### Requirement: 两种模式与布局可达
管理页 MUST 在网格、列表、窄屏、横屏及反复刷新、切换模式后显示可达卡片。PRoot MUST 保留新建、导入及实例删除；停用旧 GNOME 仍允许删除。更多菜单 MUST 不恢复 Install。

#### Scenario: 空 PRoot 与 Native 互切
- **WHEN** 在空 PRoot 和 Native 间反复切换并切换布局
- **THEN** PRoot 显示新建和导入，Native 显示固定入口，卡片有正尺寸且处于滚动内容内。
