# Hangover 模式要求

## ADDED Requirements

### Requirement: 显式选择内置模拟器
PRoot Hangover 游戏编辑首页 SHALL 展示 32 位翻译模式和当前选项，支持 WowBox64、FEX，默认 WowBox64。说明 64 位仍使用 FEX。普通 Wine/Proton 和 Native SHALL 不展示该项。

#### Scenario: 保存与取消
- **WHEN** 用户选择 FEX 或 WowBox64 并保存或取消
- **THEN** 保存后重开显示对应游戏的模式，取消不修改已保存值，其他游戏不受影响，Activity 重建保留未保存草稿。

### Requirement: 启动实际应用选择
Hangover 启动 SHALL 按配置设置 HODLL，WowBox64 使用 wowbox64.dll，FEX 使用 libwow64fex.dll；HODLL64 明确为 libarm64ecfex.dll。不得以独立 Box64/FEXInterpreter 替代 Hangover 内置 DLL。

#### Scenario: 正常切换
- **WHEN** 使用任一有效模式启动 Hangover 游戏
- **THEN** 直接调用 Hangover Wine，实际进程收到所选 DLL 参数，复用已有 prefix 和运行时资产。

#### Scenario: 无法使用所选模式
- **WHEN** 模式值非法或所选 DLL 缺失
- **THEN** 明确失败并停止，不启动其他模拟器、不 wineboot、不构建 prefix。
