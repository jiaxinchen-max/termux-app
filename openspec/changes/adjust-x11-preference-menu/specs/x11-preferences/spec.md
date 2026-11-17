## MODIFIED Requirements

### Requirement: X11 主菜单顺序
主菜单 MUST 将 UI Mode 显示在紧邻 Other 之前，Data 位于 UI Mode 之前。

#### Scenario: 打开主偏好设置
- **WHEN** 集成终端用户打开 X11 Preferences
- **THEN** 顺序为 Output、Pointer、Keyboard、Data、UI Mode、Other、Version。

### Requirement: 桥接入口命名
Other 中桥接入口及其弹窗 MUST 在默认和中文语言显示 Render bridge，并继续调用现有安装操作。

#### Scenario: 打开桥接设置
- **WHEN** 用户点击 Other 内 Render bridge
- **THEN** 打开同名弹窗，确认后使用原有桥接安装回调。
