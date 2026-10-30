# KWin / Qt 6.12 原生桌面修复

补丁基线：

- KWin 仓库 `https://github.com/jiaxinchen-max/kwin`，分支 `Plasma/6.6`，tag `termux-hwaccel-6.6-r26`，commit `ff4c62953913ad11f0dedf9658bc8bde52ac3fa5`。renderer 补丁的 5 个文件及 output-device 协议文件均已与该 tag 逐字节核对一致。
- Termux 包 `kwin 6.6.0-26`；该包源码/构建产物 SONAME 文件实际为 `libkwin.so.6.6.7`。编译机脚本 `/workspace/termux-packages/x11-packages/kwin/build.sh`，源码 tar SHA256 `da789158db78667b3dabef54a96c781e2505a892f07e81a8f868ee3733a6b6ec`。
- QtBase `6.12.0`，原始 `qtbase-everywhere-src-6.12.0.tar.xz` SHA256 `a951bd163c7b80fc6b8c88d7668fb56abf91c152373e13c10666763238131307`。使用编译机已有 Termux patches 和配置增量编译插件，不改变 Qt ABI。

新增原生 Qt 客户端库链修复、实际 Qt renderer preflight、独立 guard 修复详见 `../native-qt-client/README.md`；这些后续补丁必须在 renderer 补丁之后应用。

`kwin-r26-explicit-renderer.patch` 移除自动选择、默认模式、失败后尝试其他 renderer 和 QPainter 候选。必须显式提供 `KWIN_ANDROID_GL_MODE`：

| 参数 | 实际 compositor | 输出导入默认值 | 原生 Plasma QtQuick |
| --- | --- | --- | --- |
| `system` | Android EGL/GLES，标准 eglGetDisplay(EGL_DEFAULT_DISPLAY) 入口 | `native` | GLES3 / AHB Qt 客户端集成 |
| `zink` | Mesa Zink，唯一指定 Vulkan ICD；未传 ICD 时固定 freedreno aarch64 JSON | `dmabuf` | OpenGL / Mesa Zink |
| `llvmpipe` | Mesa llvmpipe，surfaceless EGL | `off` / CPU readback | software |

额外底层参数 `turnip` / `kgsl` 是 Zink 的显式别名，`wrapper` 只使用指定 Android Vulkan wrapper，均不在失败后切换。`auto`、空值、`hardware`、未知值返回错误。硬件探测失败、实际 GL renderer 与参数冲突、环境 loader/driver 与参数冲突均阻止初始化。显式 linux-dmabuf=on 却无 import formats 时失败；KGSL 的设备标识行为保持原样，不新增 DRM 节点前提。


`kwin-r26-output-mode-version.patch` 是独立的单行协议修复：`bindResource()` 错将 `KDE_OUTPUT_DEVICE_MODE_V2_FLAGS_CUSTOM`（标志位值 1）当作版本下限，导致 v18 及更旧的客户端收到 since=19 的 flags 事件（opcode 4）。条件改为 `KDE_OUTPUT_DEVICE_MODE_V2_FLAGS_SINCE_VERSION`，不改变 renderer 或客户端的绑定版本。

真实发送层回归源码位于 `../kwin-output-mode-version/`：测试直接链接实际 `libkwin.so.6`，通过 socketpair 发送 registry.bind(v18/v19/v20)，创建实际 `OutputDeviceV2Interface` 和虚拟输出，使用 Wayland protocol logger 检查已发送的 mode 事件 opcode 4。v18 必须不发 flags，v19/20 必须发 flags 并携带 Custom=1；旧库作为负向对照在模拟器 v18 失败，v19/20 通过；修复库 v18/v19/v20 全部通过，退出码分别为 old=1、fixed=0。实际记录见 `../kwin-output-mode-version/验证记录.txt`。这属于协议版本合规诊断，不表示支持 trixie Plasma。容器 Plasma 固定使用 forky；trixie Plasma 已确认不兼容，不列为待验项。

`kwin-r26-native-touch-time.patch` 修复 Directtouch 的原生触摸时间戳：down/motion/up 由恒定 0 改为与鼠标一致的 monotonic `currentInputTime()`。KWin seat 和 Qt Wayland 客户端会原样传递该时间，QtQuick TapHandler 以时间差识别多次点击；恒定时间破坏此语义。active touch ID 改为每个 backend 实例所有（类布局变化，必须同时安装本次重新编译的 `kwin_wayland` 和 `libkwin`），输入连接关闭、触摸设备禁用及 backend 析构时发送原生 touch cancel 并清空；EOF 前已经读入的完整消息先处理。坐标保持 App 已映射的服务端坐标，当前 AndroidOutput position=0、scale=1，等于全局逻辑坐标，不加入二次缩放。Directtouch 仍只发送原生 touch，未改成鼠标模拟。修复后的正式 App UI 验收由主线程执行，不能将源码审计视作已经通过。

输出导入仍可通过 `KWIN_ANDROID_AHB_IMPORT=native|dmabuf|off` 显式指定；每种方式失败均报错。CPU readback 是显式模式，不是故障回退。

`qt612-android-any-rtti.patch` 只修复 xdg-shell 插件创建 `std::any` 的 handler：Android 插件局部加载时，隐藏的 `xdg_popup*` RTTI 名称地址可能不同，原 libc++ 唯一地址比较使 layer-shell-qt 的 any_cast 失败。此翻译单元使用 libc++ 按名称比较实现；没有读取 `std::any` 私有内存或修改 popup parenting 规则。模拟器已通过 App 启动原生 Plasma 并显示右键菜单，未再退出。独立跨 DSO 诊断源码在 `../qt-any-rtti/`，其诊断结果需另行记录，不能代替 App 验收。

编译仅在指定 SSH 编译机执行：

```sh
/home/builder/.termux-build/_cache/cmake-4.4.4/bin/cmake --build /home/builder/.termux-build/kwin/build --target kwin_wayland -j4
/home/builder/.termux-build/_cache/cmake-4.4.4/bin/cmake --build /home/builder/.termux-build/qt6-qtbase/build --target QWaylandXdgShellIntegrationPlugin -j4
```

模式脚本验证：`bash docs/desktop-testing/test-kwin-explicit-renderer.sh /path/to/patched/android-rendering-env.sh`。15 项检查通过，包含指定硬件模式失败且其他硬件/系统路径仍存在时必须返回失败。

离线包 `kwin-qt612-native-fixes-20261030-aarch64.tar.gz` 内含精确版本检查、SHA256 清单、源码补丁和 `install.sh`。先通过 App 停止桌面，再在 Termux 中运行 `bash install.sh --prefix /data/data/com.termux/files/usr`。安装前检查全部目标存在，保存全部原文件，然后逐个原子替换；安装程序不启动桌面。必须再通过正式 App 选择明确模式并启动验收。该包是本地验证 overlay，不创建线上 release，也不修改 App 下载 URL；apt 升级会覆盖它。

实际目标路径：

- `$PREFIX/bin/kwin_wayland`（与 libkwin 同次构建，必须一起替换）
- `$PREFIX/bin/start-plasma`
- `$PREFIX/bin/kwin-android-rendering-env`
- `$PREFIX/lib/libkwin.so.6.6.7`（保留现有 SONAME symlink）
- `$PREFIX/lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so`

原生 Qt 客户端新增 payload：`bin/kwin-qt-client-renderer-probe`、`lib/qt6/termux-system-egl/libEGL.so{,.1}`、`lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so`。这些文件只服务明确的 native 客户端模式，不替代全局 Mesa。
