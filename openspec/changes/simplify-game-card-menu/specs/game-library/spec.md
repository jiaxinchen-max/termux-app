# 卡片菜单要求

## MODIFIED Requirements

### Requirement: 卡片菜单仅保留编辑删除
游戏卡片右下角弹出菜单 SHALL 按顺序仅显示 Edit、Remove；横竖屏、Native/PRoot和文件授权状态不改变此菜单项集合。

#### Scenario: 打开卡片菜单
- **WHEN** 用户点击游戏卡片右下角菜单
- **THEN** 仅显示 Edit 和 Remove 两项，尺寸按现有窗口规则适配。

### Requirement: 保持原操作语义
Edit SHALL 进入该游戏编辑页；Remove SHALL 保留删除确认和既有强杀删除流程。编辑器内部功能保持。

#### Scenario: 编辑或取消删除
- **WHEN** 用户选择 Edit 或选择 Remove后取消确认
- **THEN** Edit进入相应游戏编辑器，取消Remove不删除记录；编辑删除的既有强杀处理不被改变。
