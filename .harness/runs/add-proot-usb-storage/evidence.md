# 调研证据（2026-11-16）

## 当前源码观察

本轮检查基于 `master-x11-submodule-tmp`、业务代码基点 `37022d88`。不修改已有七个 dirty C/C++ 子模块。

| 来源 | 可直接核对的观察 |
| --- | --- |
| `termux-usb/README.md`、`termux-usb/src/main/java/com/termux/usb/EmbeddedUsbApi.java` | 主 APK 内置枚举/Android 系统授权/同 UID FD 传递；授权要求 App 可见。 |
| `termux-ui/src/main/java/com/termux/ui/desktop/api/DesktopUsbStorageTasks.java` | supported 仅允许 XFCE/旧 GNOME；检查 host 与 guest GIO 依赖；控制操作为 authorize/mount/unmount/eject。 |
| `termux-ui/src/main/java/com/termux/ui/desktop/runtime/DesktopRendererPolicy.java` | Java 配置还有独立 USB 支持限制。 |
| `termux-ui/src/main/assets/local-games/desktop_install.sh`、`desktop_start.sh`、`desktop_rendering.sh` | Plasma/Ubuntu GNOME/Hyprland 有不同早期限制，Hyprland 安装为单独分支，不能仅修改 UI。 |
| `termux-ui/src/main/assets/local-games/desktop_usb_storage.py` | App 专属只读协调器、关闭相机/手柄、guest helper 参数、同会话运行及先卸载再清理；当前轮询允许自动请求授权，候选方案改为显式用户操作。 |
| `termux-ui/src/main/assets/local-games/desktop_install_gio.py` | 安装并验证固定 Resolute AArch64 glibc GIO 产物，不可让 Bionic 模块进 guest。 |
| `termux-ui/src/main/assets/local-games/desktop_xfce_session.py` | 短 host/guest IPC bind 和分开的总线地址；guest helper 的口令参数仍需实际挂载验证。 |
| `termux-ui/src/main/assets/local-games/desktop_gnome_launch.py` | Ubuntu profile 强制 USB off；旧 USB 分支在 host PREFIX 对应 rootfs 路径下 mkdir，须替换。 |
| `termux-ui/src/main/assets/local-games/desktop_hyprland.py` | guest Thunar 客户端存在，每个 wrapper 当前另起 dbus-run-session；需要一致 guest USB 会话总线。 |
| `tools/termux-usb-bridge/README.md`、`desktop.py` | BOT/SCSI FAT/exFAT 用户态文件服务、鉴权 WebDAV 与 GIO；Solid/UDisks2 不在模块覆盖范围；相机/SDL 各有专门桥接。 |
| `docs/desktop-testing/desktop-path-permission-audit-20261116.md` | 已记录当前桌面的 PRoot 隔离路径和待启用 GNOME USB 的 mkdir 风险。 |

bridge README 开头仍面向独立使用 Termux:API 的用法；App 实际路径以 EmbeddedUsbApi 与显式 termux-usb-app 参数为准。旧 GNOME 验证资料含过时发行版，不宣称当前四类桌面已完成实物 USB 验证。

## 工具和验证边界

已直接确认仓库无 `.harness/agents/application-owner.md`、`.harness/workflows/HARNESS_WORKFLOW.md`、`.harness/rules/agent-rules.md`、`.codex/skills/codex-owner/SKILL.md`、`.harness/scripts/verify.sh`，环境无 openspec CLI。仓库 `openspec/config.yaml` 指定 spec-driven，规划按现有目录结构人工创建。

`openspec/specs/`、`docs/product/`、`docs/product/README.md` 当前不存在。新能力资料只登记为规划目标，不冒充已存在产品基线。本轮未运行桌面、未接入物理 USB、未修改 App 或 guest，也不构建/分发新 APK。

已检查四份中文规划、每项 Requirement 的 MUST/Scenario/WHEN/THEN 结构、8 个待办任务及资料空白，结果通过。实际为 4 项需求和 10 个场景；初次检查脚本误写数量，已纠正检查逻辑。没有本地 YAML 解析库，字段仅人工核对，不冒充解析/strict 验证。
