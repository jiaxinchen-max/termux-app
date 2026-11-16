# USB 存储实施证据（2026-11-16）

分支 `master-x11-submodule-tmp`，规划基点 `aa779c66`；用户已批准四类 Ubuntu 26.04 PRoot 只读存储范围及 Plasma Thunar 入口。七个已有 dirty C/C++ 子模块未修改、未暂存。当前证据描述实现验证，不替代最终产品验收。

## 实现对应关系

| 需求 | 实现符号/路径 | 验证 |
| --- | --- | --- |
| 四类 PRoot 可启用，Native 限制 | `DesktopRendererPolicy.supportsUsb`、`DesktopUsbStorageTasks.supported`、`DesktopUsbStorageDialog`、`desktop_rendering.sh` | Java renderer 矩阵；shell 配置八组合 |
| 成功准备后发布，失败保留可用配置 | `DesktopTasks.configureUsb`、`usbManagedFiles`、`HyprlandSelectionTransaction`、`PreparedDesktopBackup` | 四类 candidate commit/rollback；原字节/新文件/符号链接保护 |
| 组件留在资产库、保存期安装 | `desktop_install.sh/install_usb_host/install_usb_guest`、`DESKTOP_USB_CONFIGURE_ONLY` | 真 shell 入口夹具验证不重新安装桌面/目录且保留 marker；host bridge 与 guest GIO 下载经 SHA 校验 |
| 000 host PREFIX 与 guest 短路径 | `desktop_usb_storage.py/prepare_guest_mount/paths`、GNOME prepare | 文件系统夹具断言 000 不变、拒绝 guest tmp symlink；下面记录 Android 实测边界 |
| guest token 参数与 ABI 隔离 | `desktop_usb_storage.py/main` 生成 helper wrapper | 实际生成 wrapper 子进程测试映射及越界拒绝；guest 固定 glibc 模块 |
| 四会话总线/入口 | `desktop_xfce_session.py`、`desktop_gnome_launch.py`、`desktop_hyprland.py/container_command`、`desktop_plasma_session.py/main` | session 参数契约；Hyprland helper/client 共用 guest bus；Plasma USB Files 由保存期生成 |
| 不等待枚举/授权、必要服务退出 | `desktop_usb_storage.py/Session`、`desktop_plasma_session.py/supervise`、Hyprland supervision | 真子进程 IPC 就绪后硬件线程延迟 90 秒仍立即就绪；服务死亡为错误；无关进程保留；Plasma USB 先于 guest 清理 |
| 显式 Android 授权与设备状态 | `AppStorageManager.classify/poll/action` | 控制测试 `request_permission=False`，只读策略；物理授权/拔插未测 |
| 原导出限制 | 既有归档入口仍要求 USB off | 本次未改归档合约，不保存授权/口令/socket；不声称带 USB 的导出已验证 |

## 本地执行结果

最终命令及原始日志 SHA256 见 `verification.json`，日志原件位于 `/home/user/workspace/artifacts/proot-usb-storage-20261116/`。

| 验证组 | 结果 |
| --- | --- |
| `:termux-ui:testDebugUnitTest` 指定 RendererPolicy、PreparedDesktopBackup、UsbSelectionTransaction、HyprlandComponentsRepository | 15 tests，0 failures |
| `test-desktop-usb-control.py` | 7 passed |
| `test-desktop-usb-sessions.py` | 4 passed |
| `test-desktop-usb-install.py` | 1 参数化测试、8 个组合 passed |
| `test-desktop-hyprland.py` | 13 passed |
| `test-desktop-gnome-session.py` | 15 passed |
| `test-desktop-rendering.py` | 5 passed |
| `test-container-plasma-startup.py` | 11 passed |
| `test-desktop-ubuntu-plasma.py` | 11 passed |
| `test-desktop-install-gio.py` | 11 passed |
| 变更 Python AST、shell `sh -n`、`git diff --check` | passed |
| `:app:assembleDebug` | passed |

安装夹具中的外部 APT/下载命令为隔离替身，只证明安装路径/顺序/错误传播，不冒充镜像文件服务或物理硬件测试。镜像测试同样不能代替真实 OTG。

## Android 验证条件

目标模拟器 `120.24.191.156:8057`。测试前 force-stop `com.termux`，确认无 App PID，再启动默认 Activity，未修改默认 UI 模式。测试组件通过 App 自身 TermuxService 下载，不从工作站 ADB 传包；`--user 0` 为正确 Android 当前用户，无权限绕过。未使用 `adb install -t`，未安装新 APK 到用户设备。

复用已发布资源，并在设备核验 SHA256：

- `gnome-usb-20261030-r1/termux-usb-bridge_0.4.1_aarch64.deb`：`d00381ecca8285e502bcd00f3491032d25f14d499454bccbb1cb1dab3964770c`。
- `gnome-50-resolute-20261116-r1/termux-usb-gio-resolute-aarch64-0.4.1.tar.gz`：`a843a28f1821c1e598d8c653ea0db7e6540d188d42296cc5a305bd4a838982ea`。

解压仅到 App HOME 的独立 `.cache/proot-usb-check-20261116/`；GVfs 前置依赖安装到独立 `software-center-check-20261116` 测试容器。未向现有 `ubuntu-xfce4-1`、`ubuntu-kwin-plasma-1` 写入测试组件或改变其 guest 配置。没有新 DEB 编译或组件发布。

## 工具缺口与验收边界

已直接核对缺少 `.harness/agents/application-owner.md`、`.harness/workflows/HARNESS_WORKFLOW.md`、`.harness/rules/agent-rules.md`、`.codex/skills/codex-owner/SKILL.md`、`.harness/scripts/verify.sh`，环境没有 openspec CLI。`.harness/scripts/verify.sh --change-id add-proot-usb-storage --strict-run` 无法执行；人工和本地测试不等于严格门禁通过。

`openspec/specs/`、`docs/product/` 目前不存在。产品文档目标已登记为 `docs/product/desktop-external-devices.md`，一致性保持 Pending；未最终编写、未同步基线、未归档。

无可操作的物理 USB 设备，因此 Android 授权拒绝/超时、真实磁盘文件系统、Android 占用、热插拔、四类完整 GUI 的实物验收仍待完成。
