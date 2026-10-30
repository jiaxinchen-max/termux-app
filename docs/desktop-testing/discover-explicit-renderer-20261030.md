# Discover 显式渲染模式修复（termuxfix2）

## 根因与修复

小米容器会话显式设置 `QSG_RHI_BACKEND=vulkan`，但 Debian Discover 6.7.4 的 `discover/main.cpp` 在 `WITH_QTWEBVIEW=1` 时无条件调用 `QQuickWindow::setGraphicsApi(OpenGLRhi)`。实际构建 flags 与 moc predefs 均确认该宏为 1。Qt 6.11.2 的 `qsgrhisupport.cpp:50` 优先处理 C++ 指定的 API，只有未指定时才在第 77 行读取环境变量，因此环境继承正确仍会走 OpenGL。设备日志对应 `EGL not available`、`QRhiGles2`、backend 2 初始化失败。

补丁 `patches/discover-explicit-renderer.patch` 在创建 QApplication 前严格解析参数：

| 显式参数 | 实际选择 | 嵌入网页认证 |
| --- | --- | --- |
| `QSG_RHI_BACKEND=opengl`，QT_QUICK_BACKEND 未设置 | OpenGLRhi | 初始化 QtWebView |
| `QSG_RHI_BACKEND=vulkan`，QT_QUICK_BACKEND 未设置 | VulkanRhi | 明确不可用 |
| `QT_QUICK_BACKEND=software`，QSG_RHI_BACKEND 未设置 | software scenegraph | 明确不可用 |
| 缺失、未知、冲突、大小写错误 | 创建 QApplication 前退出 64 | 不初始化 |

仅明确 OpenGL 才启用 AA_ShareOpenGLContexts 与 QtWebView。Vulkan/software 收到 Flatpak 嵌入网页认证请求时显示所选渲染器不支持的提示并取消交易；组件加载或创建失败也显示错误并取消。移除该处理器原有的失败后打开外部浏览器路径，不改后端或重试。

限制为 Flatpak Webflow 嵌入网页认证；视频预览使用 QtMultimedia，不受此门控。PackageKit、搜索与主界面没有进入 Webflow 处理器。未声称验证过 OpenGL WebView 的真实网页认证流程。

保留 termuxfix1 的真实异步网络状态补丁。

## 构建与独立验证

指定远端编译机 `builder@BUILD_HOST:7022`，沿用独立 Debian forky arm64 rootfs、Qt 6.11.2，工作目录 `/home/builder/desktop-fixes-20261030/discover/fix2`；未在本地编译，未修改设备、App 源码或系统包。

增量构建须先 `dpkg-source --before-build .` 确保 quilt 补丁已应用，再显式运行 `cmake --build obj-aarch64-linux-gnu -j12`，再 `dpkg-buildpackage -nc -b -uc -us -j12`。仅运行后者会复用 debhelper-build-stamp 并只重打包；首次这种无效包已由最终 deb 参数矩阵检查拦下，不作为交付。最终构建日志必须包含 main.cpp、qrc_resources.cpp 编译与主程序重链接。

源码包从原 orig.tar.xz 与 fix1 Debian tar 干净重建，增加 fix2 changelog、renderer quilt patch、series；构建前已确认 main.cpp 与 DiscoverWindow.qml 与导出源码逐字节相同；dpkg-buildpackage 完成后会自动 unapply 原构建目录的 quilt 补丁，已应用的最终源码保留于 `/home/builder/desktop-fixes-20261030/discover/rootfs/root/build/fix2-source/discover-6.7.4`，并由 dsc 重解包再次核对。构建输出目录和已安装 deb staging 不进入源码归档。

真实 Qt QML 独立 fixture 直接抽取最终 DiscoverWindow.qml 的 onWebflowStarted 处理器，验证三组：可创建组件、缺失组件、必需属性缺失导致创建失败。每组均检查不可用时恰好一个错误、一次取消、零次打开；可用时分别检查一次成功打开或明确错误取消。无外部浏览器或后端切换。

最终 deb 二进制的 `--version` 参数矩阵覆盖三种合法模式及六种非法模式，仅验证参数和初始化选择；不将其记为真实窗口渲染或正式 UI 功能通过。设备正式 UI 验证由主线程执行。

验证脚本位于 `discover-explicit-renderer/`；证据、源码和散列随 fix2 离线包交付。

## 交付与结果

所有最终包版本均为 `6.7.4-3+termuxfix2`，包含主包、common、fwupd、kde-config-updates，以及同次构建的其他后端和调试符号包。目标已安装的精确依赖包必须同时匹配新版本，不能删除 backend 绕过依赖。

远端包目录：`/home/builder/desktop-fixes-20261030/discover/fix2/artifacts/`；其中 `PACKAGES.json` 记录 11 包的 Version、Architecture、Depends、SHA256。

离线包：`/tmp/termux-desktop-fixes-20261030/artifacts/discover-6.7.4-3+termuxfix2-arm64.tar.gz`，远端位于 `/home/builder/desktop-fixes-20261030/artifacts/`。

归档 SHA256：`7ff9df179e07eacc5d405e43cebeec93b0375e55c6b171dd8b3f61e932951176`。

最终主程序 SHA256：`112e945c4883b9693511d961628fcd33a73b333b327ffd3e4fb410352ef73115`。

| 包 | SHA256 |
| --- | --- |
| plasma-discover | `3f7dfe397ad9504d4e159f47a9895a0ac7f1bf80084755e30580c4ac0af63577` |
| plasma-discover-common | `3e3f3dbf3f6f3ae94587f6ade3c5ec3e44b59dc80e5aa0cf4d5bab6a43f778c0` |
| plasma-discover-backend-fwupd | `03889cf9bd2a9599e91e911ee629a8c9b0affa5b5e13ddac6f645d636d2dc94e` |
| kde-config-updates | `8f09c0defbec3fef088c4dec23d55fc83091ec85db9f79018b2a7adc9215c720` |

实际最终 deb 的 9 组参数矩阵全部通过，生成资源与最终 ELF 嵌入的 DiscoverWindow.qml 和导出源码逐字节一致；源码 dsc 再次解包后 main.cpp/QML 与最终源码一致。认证门控三组真实 QML 运行通过，所需属性缺失的负向 case 保留真实 Qt 错误。主包仍包含 PackageKit backend，桌面入口仍为 `Exec=plasma-discover %F`。

本子任务未安装设备包，未代替正式 App UI 验证；该验收由主线程负责。
