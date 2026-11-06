# 修复当前脚本回归夹具

## Why

以用户截图指定的 `4c4229d2` 为接续基线，`docs/project-status.md` 明确保留了六个失败脚本。本次 Linux 环境已逐项复现：测试夹具仍依赖旧代码块、旧启动规格和缺失的音频前置条件，导致当前生产契约无法得到有效回归。

## What Changes

- 只修复六个现有脚本及确有必要的测试辅助文件，使其匹配当前生产代码；不删除失败场景，不以放宽断言或吞掉错误取得通过。
- 更新控制器 override 提取边界，保留真实 DLL、导出环境和幂等断言。
- 补齐 Native resolver 与配置夹具，保留用户变量高于调试预设的断言。
- 对照当前 LaunchSpec 编解码器和启动器更新 Native/RootFS 夹具、相邻脚本、配置及持久路径，保留 argv 安全、取消、错误与清理覆盖。
- 为 GNOME 和桌面生命周期夹具显式提供当前音频模式、PulseAudio 地址和局部模拟依赖，增加缺失音频配置的失败断言。
- 保存修复前后结果，并更新工程进度中六项脚本失败的记录；只有本次实际通过的检查才能标记完成。

## Capabilities

### New Capabilities

- `script-regression-contracts`：开发者可通过现有六个脚本，在临时隔离环境验证当前 Wine 启动、控制器、用户环境优先级和桌面音频/生命周期契约。

### Modified Capabilities

无。当前没有 `openspec/specs/` 基线；本 change 描述测试工具的可观察行为，不改写历史产品需求。

## Impact

- 计划修改 `termux-ui/scripts/test-controller-runtime.sh`、`test-launcher-env-precedence.sh`、`test-start-local-game.sh`、`test-start-rootfs-game.sh`、`test-desktop-gnome-scripts.sh`、`test-desktop-managed-lifecycle.sh`，以及验证后的 `docs/project-status.md` 测试状态。
- HTTP API 文档：`N/A`，没有 HTTP 方法、路径、鉴权或请求响应变更。
- 产品文档：`N/A`，没有用户可见能力、业务规则、权限或兼容性变化；只修复内部回归工具与测试证据。
- 用户可见行为与兼容性：保持当前生产启动、渲染器显式选择、音频要求及损坏环境保护规则。测试适配当前契约，不能要求生产代码兼容过时 fixture。
- 非目标：真实 USB、Dolphin 集成、PRoot Plasma 真机 Stop、phantom-process、并发 owner 修复、组件安装/发布、设备空间清理，以及恢复完整 Harness 框架。
- 风险：提取边界可能截断代码，mock 可能掩盖真实回归；通过执行真实函数/脚本、保留负向场景和故障敏感性检查控制风险。
- 回滚：撤销本 change 的测试和证据改动；不更改运行时资产、设备数据、子模块指针或已发布组件。

## 接续与确认

- 独立工作目录：`/home/user/workspace/termux-app-continue`，基线 `4c4229d2`；主仓库仍锁定 `567c9135`。
- 当前状态：用户已明确确认“开干，缺啥跟我说”；按本提案实施六项测试夹具修复，不修改生产实现。
- `.harness/` 被版本库忽略，权威规则/strict verifier 不在当前 checkout。本次新增的 run ledger 仅记录本次规划、原始复现与实施验证，不能冒充恢复的旧 Harness 或 strict 通过证据。
