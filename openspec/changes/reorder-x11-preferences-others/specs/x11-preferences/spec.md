## ADDED Requirements

### Requirement: Others 紧邻版本信息

X11 偏好设置主菜单 MUST 将 Others 放在 Version 紧前一项，Version 保持最后一项；其余条目的相对顺序和功能不变。

#### Scenario: 终端宿主
- **WHEN** 终端模式打开 X11 偏好设置
- **THEN** 界面切换和数据入口之后依次显示 Others、Version

#### Scenario: 普通宿主
- **WHEN** 不提供终端扩展动作的宿主打开偏好设置
- **THEN** Others 和 Version 仍在菜单末尾且相邻
