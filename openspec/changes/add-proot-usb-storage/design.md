# 设计（待需求确认）

## 现状与证据

主 APK 的 `termux-usb/EmbeddedUsbApi` 已通过 Android UsbManager 枚举、授权并向同 UID 客户端传递 FD，无需另装 Termux:API APK。`DesktopUsbStorageTasks.supported()` 目前仅允许 XFCE 与已废弃的独立 GNOME；Java renderer policy、shell 安装/启动及 Ubuntu GNOME launcher 还有独立限制，不能只放开 UI。

`desktop_usb_storage.py` 已提供只读、按桌面拥有的 broker、文件服务及 GIO 协调器。现有 GIO 模块面向 Thunar/Files；Qt Dolphin 不通过该模块发现设备。现有 `desktop_install_gio.py` 安装经过 SHA 校验的 Resolute AArch64 glibc 产物。GNOME 旧 USB 分支还会在 rootfs 的宿主 PREFIX 占位路径内 mkdir，启用前必须修正。Hyprland 客户端目前各自 dbus-run-session，需为 USB 浏览固定一个 guest 会话总线。

源码依据见 `.harness/runs/add-proot-usb-storage/evidence.md`。旧 `docs/desktop-testing/gnome-usb.md` 含历史发行版与验证记录，只作为历史证据，不当作当前能力基线。

## Activity、任务和保存事务

复用现有外接存储对话框，支持矩阵显式区分运行方式及桌面。保留深浅主题、不透明背景、忙碌/禁用原因及关闭行为。Android USB 系统授权必须从前台 App 发起；轮询不自动触发新的授权弹窗。依赖安装和启用由停止桌面时的任务完成：准备成功后才发布有效 USB 配置，失败则保留此前可用配置，提供明确错误。

在安装/保存阶段安装主机 bridge、guest gvfs-backends/GIO 模块并生成持久会话配置及入口；Plasma 的 USB 启用配置另安装 Thunar。恢复沿用既有 USB off 归档策略。启动不运行 APT、不补文件、不做驱动/版本探测、不创建持久配置。不能把依赖检测页变成每次启动的预检流程。

## 桥接与存储边界

链路为 Android USB FD → Bionic broker/只读文件服务 → 已有鉴权本机 WebDAV → guest glibc GIO/GVfs → guest 文件管理器。不将 Android content URI 当 POSIX 路径，也不要求 FUSE、UDisks2 或 /dev/sdX。只支持现有 BOT/SCSI 与 FAT/exFAT 解析范围；不擅自扩展 NTFS/ext4/UASP。

主机 IPC 继续使用按 desktop ID 派生的短路径；guest 使用 `/tmp/tusb-<id>` 或当前会话 `/run` 子目录的显式 bind。guest 命令只接收 guest 路径，包括 GIO helper 的口令文件参数、控制 socket、volumes.ini 与 D-Bus 地址；主机协调器接收主机地址。禁止在 rootfs 的 `/data/data/com.termux/files/usr` 下创建子项或修改其 000 权限。不得将 Bionic `.so` 加载入 glibc 应用。

复用已有 localhost 鉴权 WebDAV，随机口令仅存在私有运行目录，不出现在启动命令行、普通日志、URI 或归档。任何设备动作都由当前会话重新解析已枚举设备，验证参数与同 UID socket 凭据。应用与所有 PRoot 仍属同一 Android UID，此机制不承诺容器之间的强安全隔离。

## 四类会话接入

| 桌面 | 设计接入 |
| --- | --- |
| XFCE | 复用当前 guest 会话总线与短路径映射，补充口令路径转换/真实挂载验证，防止历史路径错误复发。 |
| Ubuntu GNOME | 仅对 PRoot 放开 read-only；使用现有 guest 总线与 Files，替换旧 host PREFIX USB bind；native 合成器不承担 guest USB 文件操作。 |
| Hyprland | 为 guest Thunar 及 GIO helper 固定同一个受监督的 guest session bus；主机合成器继续使用自己的总线，其他 guest 应用必须收到正确 guest 地址。 |
| Plasma | 使用已有 guest 会话总线为配套 Thunar/GIO 提供 USB 文件入口；不伪造 UDisks2 服务，不将原始含口令 URL 交给 Dolphin。 |

四类均使用普通 termux 用户运行文件管理器。无需改 X11/Wayland 显示、输入、宿主/guest renderer 或音频模式。动态 USB IPC/进程属于每次运行所需服务，可以在启动时创建；服务配置、组件安装属于保存阶段。

## 生命周期与错误

USB off 不启动服务、不枚举设备。read-only 时启动本会话的服务，并通过有界 IPC 就绪信号监督；不把等待实物设备、枚举结果、授权或挂载作为桌面 ready 条件。每个 USB 操作有界超时，拒绝授权或设备错误只标记该设备；必要会话服务无法启动/意外退出则按桌面既有失败流程退出，不自动修复、切换后端或重试启动。

正常停止先断开 guest GIO 卷，再停止本次文件服务、broker，最后停止所属 guest session bus；不全局 pkill。热拔出清除对应卷并释放本会话资源，枚举错误不等于全部拔出。无法清理须保留可诊断失败，不能伪报成功，也不能影响其他桌面资源。

## 组件分发、验证和回退

优先复用资产库现有固定版本与 SHA；如需更新 glibc 或 bridge DEB，复用用户指定编译机的既有构建链并单独发布组件，APK 不内嵌大包。发布与 APK 构建在需求确认后的任务中记录实际结果，不在规划期执行。

测试覆盖四类支持矩阵、保存失败的配置事务、000 占位目录、host/guest 地址与口令映射、实际 GIO/GVfs 镜像卷只读、热拔出、授权拒绝、服务退出与清理顺序。模拟器验证前按用户要求 force-stop App 并确认干净进程；无物理 USB 时只能确认控制/会话/镜像链路，实物 OTG 验收保持待完成。

回退为关闭 USB 后保存，生成已有 off 会话；失败保存不得破坏此前准备结果。没有可读取的 protected-path 清单，不能宣称已通过该项门禁；计划只涉及桌面 UI/脚本/相关测试和普通依赖声明，不修改签名、凭据或 CI。若实施发现受保护路径，先记录影响、回滚、验证与所需授权。
