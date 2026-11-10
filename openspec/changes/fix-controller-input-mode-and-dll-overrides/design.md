# 设计

## DLL 策略与映射分离

controller_runtime.sh 的 games_controller_dll_overrides 分别读取 GAMES_DLL_DINPUT 和 GAMES_DLL_XINPUT。非空项生成各自 DLL 的覆盖值；空项不额外改变当前加载策略。不依据 mapper 删除另一组设置。同步检查 Native 与 PRoot 调用路径。

Hangover 当前强制清空控制器覆盖的分支需要独立核对 ARM64 DLL 的 builtin 标记与实际加载方式；不得为套用普通 Wine 的 native 策略而破坏其加载。如果用户策略在该后端不适用，必须正确呈现可用策略，不能静默忽略。最终实现与验证必须说明该分支处理。

实施核对结果：Hangover 的 DLL 保留 builtin 标记，安装至客体 Wine 的 aarch64-windows/i386-windows 和 prefix。编辑页将两项库显示为固定内置；启动时为两组显式输出 builtin，避免 native 拒绝标记。普通 Native/PRoot 保留用户的独立加载策略；空策略维持现有默认行为。Wine9.3 默认可同时控制两种 API 是用户提供的对照事实，不得改变为互斥模式。

## 映射配置

在 Advanced 中增加独立模式选项，直接更新 RuntimeProfile.inputProfileId 的 xinput/dinput 前缀，保留可选的 :触控配置编号。Function Library 仅管理 DLL 加载策略。不新增重复环境变量或新配置格式。

沿用 GameSessionActivity → X11SessionView → LorieViewRuntimeController.applyGameInputProfile 的路径，将模式应用到 GamepadHandler。启动脚本不再用映射值筛选加载策略；Native 中原有派生的 TERMUX_BOX_GAMEPAD_MAPPER 记录不作为 DLL 策略开关，不依赖用户手写该变量。

映射改变轴和按钮布局，不承诺关闭另一种 Windows 输入 API。切换不重建 prefix、不重新下载资产、不修改 Wine 或 Box64 选择。

## 风险与回滚

同时加载两组补丁可能暴露既有桥接或设备枚举问题，须分别验证两个 API。Native 与 PRoot 的 DLL 位置不同，验证应覆盖各自启动链。回滚仅撤销本次 UI、脚本和测试改动；保留上一轮 Box64 工作树与设备数据。

## 验证边界

本地补充有差异策略、仅一组配置、两组配置、空配置的脚本回归，以及映射切换保留布局编号和保存还原的测试。构建检查正常 APK。设备带宽低且用户负责手动测试，不自动启动游戏、安装 APK 或更改设备配置。若 DirectInput 仍不响应，继续定位其枚举与状态链，不回退、重建或以缺 DLL 为未经验证的解释。
