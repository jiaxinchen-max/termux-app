# 对齐独立手柄接口开关

## 问题与目标

用户已确认上一轮 DirectInput 枚举修复有效，但当前 Advanced 的 XInput/DirectInput 选项只是轴键映射，不能分别控制两个接口是否提供手柄。本次对齐 Winlator CMod 的独立启用能力，支持同时启用、仅 XInput、仅 DirectInput、全部关闭。

## 变更范围

- Advanced 增加“启用 XInput”“启用 DirectInput”，默认均开启；独立保留“DirectInput 映射”，不放到 Function Library。
- 设置按游戏保存并随启动快照传到桥接；改变映射或触控布局不能覆盖接口开关。
- 应用桥接与配套手柄 DLL 同步使用接口标志，覆盖 Native Wine/Proton、PRoot Wine/Proton 与 Hangover。
- 不禁用整个 dinput 库，不影响 DirectInput 键鼠；库加载顺序仍单独配置。
- 不重建 prefix、不引入兼容回退、不自动升级组件、不修改组件版本；APK 保持原名并供用户手动安装测试。

## 文档与依据

HTTP 契约未变化，api_docs 为 N/A。product_docs 为 Required，验收后维护 docs/product/game-controllers.md，一致性当前为 Pending。

固定参考为 Pipetto-crypto/winlator@da12b7a0a9168261656a71a6d68f9f08f77d6304；证据见 /home/user/workspace/artifacts/winlator-input-api-modes-20261110/findings.md。不能将 CMod 的独立开关表述为所有官方 Winlator 版本的行为。

编译机已只读确认 Wine/Hangover 源码及工具链可用；相关目录未发现独立手柄补丁，当前仓库保存了 Hangover 补丁和构建脚本，Wine 9.15 补丁亦已留存。编译前须固定源版本并检查补丁，不能直接替换为未经核对的第三方 DLL。
