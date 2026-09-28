# Design: Games 真实运行时预检

## 状态口径

`LocalGamesHost.isRuntimeComponentAvailable(componentId)` 是 Games 到主应用的只读能力接口。Termux Host adapter 同时识别当前 `glibc/termux-box/package-manager/installed` 和旧版 `glibc/opt/package-manager/installed` 元数据，并要求组件对应的运行时能力文件存在。

预检以 Host 返回的实际可执行环境为准，将可用能力归一化为当前索引描述后交给纯 `LaunchPreflightEvaluator`。模块私有 staging 回执不再被误认为已经激活到 TermuxBox。

## UI 与诊断

运行配置页复用 `AndroidLaunchPreflight`，避免配置页和启动 Service 结论不同。失败码使用 `preflight_<code>:<subject>`；旧任务中无 subject 的错误仍提供兼容文案。可恢复终态显示“重新启动”，创建新任务，不复用已终止任务。

启动脚本从自身 canonical path 推导 Termux files 根目录，兼容 `/data/user/0` 与 `/data/data` 私有目录别名。Games 会话页在 X11 Surface attach 后注册连接监听，并保留自身横屏策略；方向变化由 Activity 原位处理，不销毁已连接的原生 runtime。

## 安全边界

- component id 必须通过固定格式校验。
- 只读取应用私有目录中的版本、文件清单、校验清单和能力文件。
- 不信任单独存在的版本文件；元数据和能力文件必须同时满足。
- 不修改 TermuxService、TermuxBox 包内容或用户导入目录。
