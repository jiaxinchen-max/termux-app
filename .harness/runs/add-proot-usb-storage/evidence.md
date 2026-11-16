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

## 模拟器真实 GIO/GVfs 镜像结果

结果 **PASS**，完整日志与驱动脚本保存在 `device-image-test.log`、`device-image-test.py`。测试前再次 force-stop 并验证无 App PID，重新启动默认 Activity；测试仅在明确准备的独立容器中运行。

独立最小底包没有完整桌面已有的 host PREFIX 占位目录；第一次驱动因此在读取前退出。仅在该测试 rootfs 创建空的 000 夹具后，从干净进程重测通过。未修改两个用户桌面的原有 000 目录。测试驱动设互斥锁，防止同时运行破坏测试磁盘。

- 已发布的 Bionic `libtermux-usb-fs.so` 创建/填充 64 MiB exFAT 镜像，再以只读打开并提供实际鉴权 localhost WebDAV。
- guest `termux` UID 10216 用发布的 glibc `tusb-gio-mount` 和 Ubuntu GVfs DAV 挂载，实际 `gio list/copy` 完成中文目录与二进制内容逐字节核对。
- 首个无凭据 OPTIONS 为 401，helper 鉴权后 OPTIONS/GET 成功；写入 PUT 为 403，gio 写入命令失败。
- guest glibc GIO 模块在 `gio mount -li` 中暴露可移动卷；helper 卸载成功。WebDAV 线程关闭、文件系统关闭后镜像整体 SHA256 完全不变。
- Android 上运行新的 fd-relative guest mount helper，000 夹具权限不变。
- 日志里的 UDisks 不可用提示没有使读盘失败；测试没有创建 UDisks 硬件服务或内核挂载。
- 完成后移除 64 MiB 测试镜像、读回文件、口令文件和临时 endpoint/volumes 元数据；保留小体积日志和测试组件，测试 rootfs 未删除。

这是共享文件传输基础链路的真实镜像测试，未经过实物 USB FD/BOT 传输，也未代表四个完整 GUI 会话均已测试。

## 工具缺口与验收边界

已直接核对缺少 `.harness/agents/application-owner.md`、`.harness/workflows/HARNESS_WORKFLOW.md`、`.harness/rules/agent-rules.md`、`.codex/skills/codex-owner/SKILL.md`、`.harness/scripts/verify.sh`，环境没有 openspec CLI。`.harness/scripts/verify.sh --change-id add-proot-usb-storage --strict-run` 无法执行；人工和本地测试不等于严格门禁通过。

`openspec/specs/`、`docs/product/` 目前不存在。产品文档目标已登记为 `docs/product/desktop-external-devices.md`，一致性保持 Pending；未最终编写、未同步基线、未归档。

无可操作的物理 USB 设备，因此 Android 授权拒绝/超时、真实磁盘文件系统、Android 占用、热插拔、四类完整 GUI 的实物验收仍待完成。

## 已批准的读写增量（2026-11-16）

详细记录见 `read-write-verification.json`、`read-write-code-review.md`、`read-write-review-findings.yaml`、`read-write-delivery.json`。此前章节是首期只读交付记录。

- 增加显式 off/read-only/read-write，四类 PRoot 全链路传递；Native XFCE 继续只读，Native 读写在 Java/shell/launcher 入口拒绝。模式成功准备后才提交，四类两种开启模式均执行事务成功/失败验证。
- 读写模式不自动挂载，USB 授权后也不自动接管。真实 bridge Manager 夹具验证轮询/授权不启动文件服务；未授权设备从文件管理器点击连接后仍显示待授权，不在后台弹窗。每次手动连接先用内置 IPC write-check 核对 Android 设备/权限和可移动卷；任何非 unmounted/removed 状态、查询异常都拒绝。公开 Android API 无法稳定将存储卷映射至 USB 路径，因此其他已挂载 SD/USB 也会阻止连接，界面已说明。这不是跨进程的原子系统卸载，也未经实体设备验证。
- 未增加桌面启动前预检/初始化；仅在设备连接动作查询 Android。关闭/只读行为保持。读取健康状态精确校验模式。
- GIO 卷忙保留运行服务可重试；文件服务非零退出/刷盘失败锁定 error，不能再标记为安全弹出，必须重插。可写卷意外断开保留警告；正常清理顺序不变。真实父 Manager 的忙碌/非零退出路径已执行。
- 28 个 Java 测试与 92 个 Python 测试通过，其中安装单项含 12 个桌面/模式组合，事务单项含 8 个桌面/开启模式组合。原持久化测试将 read-write 当作非法值，已依新合约改为正确读写回读和 unknown-mode 拒绝。Python AST、shell 语法及 diff 空白检查通过。
- 模拟器先 force-stop，确认无 App PID，再启动默认 Activity（未改默认 UI）；启动因设备负载延迟，确认进程出现后才发起一次测试。仅通过 TermuxService 执行独立镜像测试，不安装新 APK、不修改用户桌面根目录。第一次镜像测试缺少 staging Bionic 依赖库路径而在创建磁盘前退出；修正测试启动环境，并清除 guest 继承的 host LD 路径后通过。
- `device-write-image-test.log` / `.py` 记录实际发布 Bionic FS → localhost 鉴权 WebDAV → 普通 termux UID 10216 的 glibc GIO/GVfs。64 MiB exFAT 真正完成 mkdir、PUT/二进制回读、MOVE、COPY、DELETE 及 helper 卸载；关闭文件系统后只读重新打开，同文件内容一致。只读 PUT=403，镜像整体 SHA256 不变，rootfs 的 host PREFIX 占位权限仍为 000。测试镜像、口令、endpoint 和读回文件已清理。
- 组件复用首期已发布固定版本，无 DEB/资产发布，无新增 APK 大型资产。APK 原签名、arm64、非 testOnly，包含全部 74 个桌面资源和匹配的内置 USB 客户端。新 APK 未装入模拟器，因此新 Android API 的实体授权/卸载/插拔及四个完整 GUI 仍待用户验收。严格 Harness 仍不可执行，产品一致性保持 Pending，不归档。
