# GNOME 与内置 USB 存储

## 显式运行模式

| 桌面 | 运行方式 | 发行版 | 合成器 | 宿主显示 | 刷新率 |
|---|---|---|---|---|---|
| GNOME | Native | Termux/Bionic | Mutter Android / System EGL | native AHB | 固定 60 Hz |
| GNOME | PRoot | Debian trixie | X11 / llvmpipe | Xlorie System EGL / native AHB | 显式所选值 |
| GNOME | PRoot | Debian trixie | X11 / Zink / KGSL Turnip | Xlorie Zink / dma-buf | 显式所选值 |
| XFCE | PRoot | Debian trixie | 原有模式 | 原有模式 | 显式所选值 |
| Plasma | PRoot | Debian forky | 原有模式 | 原有模式 | 显式所选值 |

GNOME 固定 `DESKTOP_GNOME_SESSION_STARTUP=direct`，由 App 监督 Shell、私有 D-Bus 及 USB 子进程；不启动 gnome-session 的 systemd 探测/回退流程。退出由 App 的 Stop desktop 管理，不提供完整系统会话管理、登录、电源管理服务。容器固定 `COGL_DRIVER=gl3`、`CLUTTER_DRIVER=gl3`，初始化失败直接退出，不尝试 GLES 或空渲染器。启动前在相同 guest 环境运行 glxinfo：llvmpipe 模式必须实际返回 llvmpipe；Zink 模式必须同时返回 Zink、Adreno/Turnip、`Accelerated: yes`，并拒绝软件 Vulkan。

## USB 边界

`:termux-usb` 是主 APK 的 Android library，只提供 USB host 枚举、Android 授权、同 UID 文件描述符传递；不依赖独立 Termux:API APK。桌面必须显式指定 `$PREFIX/libexec/termux-usb-app`，缺失或失败即报错，不调用外部 API 后端。请求新授权时 App 必须在前台。

GNOME Files 与 XFCE Thunar 使用只读 GIO/GVfs 卷；不是内核挂载或 /dev/sdX。Plasma Dolphin 的 Solid/UDisks2 设备集成尚未支持。USB OFF 不启动协调器；运行或停止清理期间不能改 USB 模式。停止先卸载卷并清理 USB，再停止 D-Bus；清理错误保持失败。

现有 v1 容器归档只支持 XFCE/trixie、Plasma/forky；GNOME 导入导出明确禁用，USB 已开启的桌面须先关闭才能导出。

## 2026-10-30 本地模拟器验证

正式功能通过 Android 15 arm64 本地模拟器的 App 界面操作：

- Native GNOME 创建/安装/启动、Files 浏览 Termux HOME、目录导航、横竖屏自动重绘、USB 页真实无设备及停止清理。
- PRoot GNOME 安装至 Debian trixie，Files 浏览 /usr，真实 Mesa25.0.7-2+deb13u1/llvmpipe LLVM19.1.7/OpenGL4.5，direct Shell 启动。
- PRoot GNOME 所选30Hz在 Surface 重建后仍全部为30Hz。
- 禁用独立 com.termux.api 后，Native/PRoot GNOME 及 Native XFCE 的 App USB 页仍显示内置模块就绪、只读服务连接及真实空设备列表。
- Native Plasma 与共享 libandroid-stub30-3 共存，正常显示桌面及面板。

本次按用户要求只验证本地模拟器；没有物理 USB 盘，真盘授权、挂载、读盘、卸载尚未验证。协议 FD 测试及空枚举不代替物理设备验证。

固定 GNOME/USB 包尚未公开发布；安装验证使用与候选发布产物相同 SHA 的本地缓存。网络 release 下载链路未验证，不应声称新设备网络安装已经可用。

构建和详细截图、SHA、日志由当前任务的本地验证产物保存。Android 编译在用户指定编译机执行。

最终APK：`aa4a16a251d9bd963670f66486dc1acb94b3ca8a59b3b65754d722b26601561f`。远端624项Java单测通过；App停止显示Stopping…后Ready，终态stopped且本轮桌面/USB进程全部退出。USB协议19项、协调器策略6项、安装合同27项通过；GIO Linux安全10项、launcher20项、生命周期5项已通过。

## 2026-10-31 小米 Zink 验证

小米 11 Ultra / Adreno 660 已通过 App 安装 Debian trixie GNOME、显式选择 Zink 并启动；Files 的 `/usr/bin` 导航、触控滚动及横竖屏重建已实测。该路径要求修复 stencil FBConfig、GLXWindow ID、RandR 时序的 Xlorie r8，r7 不支持。详细版本、失败复现、产物与发布边界见 [小米 Zink 验证记录](gnome-zink-xiaomi-20261031.md)。
