# 设计

## 默认值与界面

RuntimeProfilePresets 的新配置明确包含 GAMES_DLL_DINPUT=native,builtin 与 GAMES_DLL_XINPUT=native,builtin。未设置这两个可选项时，Function Library 按相同默认值显示，其他 DLL 仍沿用原来的默认值。不批量改写既有显式选择，不迁移旧配置或补齐缺失的必需字段。

普通 Wine／Proton 的三项 builtin、native、native,builtin 可继续选择。Hangover 显示实际默认的 native,builtin，并允许在 builtin 与 native,builtin 中选择，避免纯 native 拒绝带标记的 DLL。选择保存必须与后续启动输出一致。

## 启动生效

games_controller_dll_overrides 对缺省控制器策略使用 native,builtin，分别输出 dinput/dinput8 与六个 xinput 库。两组均独立于映射及接口启用开关。Native 与普通 PRoot 继续应用显式策略；Hangover 不再无条件覆盖为 builtin，只接受其支持的 builtin 与 native,builtin；不支持的值应在游戏启动前给出明确失败，不使用其他策略继续。

配套 DLL 的安装位置、架构、builtin 标记和部署失败终止规则保持。native,builtin 是用户要求的 Wine 库加载顺序，不是应用自行降级到旧控制器载荷。

## 源码证据

已核对普通 Wine 9.15 的 dlls/ntdll/unix/loader.c 和 Hangover 固定提交 3ea3a14941b6a122b892c84141ce9662c008081e 的同名文件。load_builtin 对 wine_builtin 文件仅在 LO_NATIVE 返回 STATUS_DLL_NOT_FOUND；native,builtin 被允许，并按 Wine 的内置标记查找逻辑处理。此前把 Hangover 限制为只能 builtin 比实际加载约束更严格。此为直接源码证据，不代表模拟器已验收。

## 风险、回滚与验证

启动脚本影响所有 Wine 启动，需覆盖缺省两组、单组显式、两组不同值、两种映射、已有图形覆盖，以及 Hangover 的受支持选择和纯 native 明确失败。验证菜单显示与保存、预设默认值、LaunchSpec 传递，以及真实 controller_runtime.sh 部署测试。

回滚仅还原本变更的 UI、配置默认和启动脚本、测试，不覆盖已有无效配置隔离工作区，不修改设备资料或资产。按原名构建正常 arm64 APK，核对非 testOnly；由用户手动验证，不经 ADB 安装。仓库未提供 Harness 引导文件、严格脚本或 OpenSpec CLI，记录不可用状态，不声称严格门禁通过。
