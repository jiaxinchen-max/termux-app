# 桌面日志入口

## MODIFIED Requirements

### Requirement: 统一的卡片日志入口
所有桌面卡片更多菜单 MUST 仅通过 Logs/日志提供日志入口。归档记录存在、状态或所属桌面 MUST NOT 导致追加独立 View progress and log；其他按能力和会话状态定义的正常操作保留。

#### Scenario: Plasma 存在历史或活动归档
- **WHEN** 最近归档记录属于 Plasma，且处于运行、成功或失败状态
- **THEN** Plasma 与同模式同状态其他受支持桌面的日志菜单一致，均无额外归档日志项。

### Requirement: 保留归档进度查看
桌面日志窗口 MUST 保留该桌面的原运行时日志。若存在归属于该 ID 的归档记录，MUST 允许从该窗口打开原实时归档进度与日志；无记录或属于其他 ID 时不得误关联。导入卡片自身进度入口 MUST 保留。

#### Scenario: 从统一日志入口恢复查看
- **WHEN** 用户选择 Logs 并选择窗口内的归档进度按钮
- **THEN** 打开原归档实时窗口，显示正确状态和日志；无归档时仍可正常查看桌面运行时日志。
