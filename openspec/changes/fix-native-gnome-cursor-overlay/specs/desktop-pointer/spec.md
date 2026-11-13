# 桌面指针

## ADDED Requirements

### Requirement: 原生 GNOME 显示可用鼠标指针
原生 GNOME 在有效鼠标或触控板输入时 SHALL 显示软件指针，移动位置和点击位置一致，使用已配置的鼠标主题。

#### Scenario: 原生 Ubuntu GNOME 操作
- **WHEN** 用户打开原生 Ubuntu GNOME 并使用触控板或实体鼠标移动、点击
- **THEN** 指针可见且跟随输入，点击命中对应位置

#### Scenario: 输入设备切换
- **WHEN** 真正触摸输入隐藏指针后，再次发生鼠标输入
- **THEN** 指针恢复显示，不得通过全局强制可见破坏触摸行为

#### Scenario: PRoot 对照
- **WHEN** 用户打开既有 PRoot Ubuntu GNOME
- **THEN** 其鼠标指针移动和点击保持正常
