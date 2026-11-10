# 修复手柄映射模式与 DLL 设置传递

## 问题

Function Library 可以分别设置 DirectInput 和 XInput，但启动函数按手柄映射模式只输出其中一组 DLL 覆盖，另一组设置被忽略。设备上的 Native/PRoot Proton 均配置为 xinput:3；编辑页没有独立映射选择入口。用户反馈 XInput 可控制、DirectInput 无响应。

已确认 Native Proton 的补丁 DLL 已部署且加载；DirectInput 补丁在两种映射下均有实现。因此不能把映射模式当作禁用另一种 API 的开关，也不能在未验证前宣称此次配置修复必然解决全部 DirectInput 问题。

## 变更范围

- 两组 DLL 加载策略分别输出，不再按映射模式互斥。
- 编辑页 Advanced 增加“手柄映射模式”：XInput、DirectInput；使用现有 inputProfileId 保存，保留触控布局编号。
- 保存后下次启动，Native/PRoot 脚本与 Android 手柄桥接使用相同模式。
- 保持 Hangover 的 ARM64 DLL 部署及加载约束，不混用 x86 DLL。

## 文档与验证

无 HTTP API 变化，api_docs 为 N/A。用户可见能力变化，product_docs 为 Required；验收后更新 docs/product/game-controllers.md。

先验证两组独立策略与映射保存，再编译 APK，由用户分别验证 Proton Native/PRoot 的 DirectInput 与 XInput。不得以 XInput 可用代替 DirectInput 验证。
