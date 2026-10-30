# 原生 Qt 客户端显式渲染模式

## 发现与范围

小米原生 Plasma 选择 System EGL 时，KWin 日志是 Adreno 660，但系统设置 About 的 Graphics Processor 显示 llvmpipe。只读采样发现：systemsettings 已有 `KWIN_ANDROID_GL_MODE=system`、`QSG_RHI_BACKEND=opengl`，其 maps 只有 Termux `libEGL_mesa`、`libgallium`、`libLLVM`；KWin maps 则使用 Android/vendor EGL/GLES，且没有 Mesa Gallium。

KDE kinfocenter v6.7.5 `kcms/about-distro/src/GPUEntryFactory.cpp` 在 `drmDeviceCount()==0` 时最终通过 `fancyOpenGLRenderer()` 在客户端自身创建 QOpenGLContext/QOffscreenSurface 并读 GL_RENDERER，不来自 KWin D-Bus 或旧缓存。因此这是 Qt Wayland 默认 `wayland-egl` 集成选择 Mesa 的真实软件客户端，不能修改显示名称来掩盖。

本修复仅覆盖 native Bionic Qt 客户端；glibc/proot 容器继续由 App 的容器脚本明确选择客户端渲染方式。

## 实现

- 基线为 jiaxinchen-max/kwin `Plasma/6.6`、r26 commit `ff4c62953913ad11f0dedf9658bc8bde52ac3fa5` 的 `tools/termux-qt-ahb-plugin`。该仓库已有完整 Qt Wayland AHardwareBuffer 客户端集成，但启动链未使用它。
- `qt612-native-ahb-client.patch` 对其使用实际运行版本 QtBase 6.12.0 的私有 headers 构建；System 客户端由 GLES3 渲染至 AHardwareBuffer 三缓冲，通过现有 `termux_ahardware_buffer_manager_v1` 协议提交 KWin。
- 私有 `$PREFIX/lib/qt6/termux-system-egl/libEGL.so.1` 仅转发至 `libtermux-platform-ns` 加载的 Android system EGL。GL 函数仅通过该 EGL 的 proc resolver 获取，不再猜测 vendor 库、尝试 libepoxy 或换 Mesa dispatch。可选 GL 扩展查询可返回 null；必要入口和上下文失败直接失败，不选择其他 renderer。
- 不覆盖全局 Termux Mesa。KWin compositor 先按原明确模式启动；ready 后才设置 native Qt 客户端环境，避免客户端私有库路径改变 compositor 的装载方式。
- `kwin-r26-native-qt-mode.patch` 明确客户端链路：System → AHB/system EGL/GLES；Zink → 指定 Mesa EGL vendor、wayland-egl、OpenGL/Zink；llvmpipe → Mesa llvmpipe，QtQuick software。D-Bus activation 环境同步全部相关变量，未使用值显式清空，避免继承上一种模式的 QtQuick 或库路径。
- native Plasma 启动组件前运行 `kwin-qt-client-renderer-probe`：用实际 Qt QOffscreenSurface/OpenGL context 验证 GL_RENDERER 与参数。该诊断没有可见窗口；失败中止启动，不将另一个 renderer 当成成功。

## guard 修复与检查

`kwin-r26-renderer-guard.patch` 独立修正 llvmpipe 校验：字符串包含 llvmpipe 同时还必须不包含 zink，避免把 `zink (...llvmpipe...)` 误认为直接 llvmpipe。

`../kwin-renderer-guard/check.py` 自动提取并编译生产 `verifyRendererMatchesMode()` 完整函数体，使用真实 Qt QByteArray 操作，分别注入 7 个 renderer 字符串 × 3 种请求模式。旧函数 20 项通过、1 项失败（Zink on llvmpipe 被当成 llvmpipe）；修复函数 21 项全部通过，均在指定远端编译机编译执行。该检查验证 guard，不能代替真实 GPU/客户端运行验收。

Shell 模式检查扩展至 16 项，包含 native System 私有路径、清除旧软件 Quick 环境、Zink/Mesa 与 llvmpipe 明确配置、缺失 AHB 客户端组件报错。已通过。

`test-dbus-env.py` 在指定编译机的独立真实 session bus 中，从软件模式残留环境开始，通过实际 D-Bus activation 捕获客户端环境。旧 v2 负向对照在 Zink 得到 `LIBGL_ALWAYS_SOFTWARE=1`，期望 0，退出 1；v3 的 Zink、System、llvmpipe 全部通过。新脚本统一维护 owned key 列表，每个 key 均显式导出；空值也同步给 activation，避免 unset 被 D-Bus 忽略。Zink 显式 software=0、System 清空 Mesa/Vulkan override、Mesa 客户端 EGL_PLATFORM=wayland。

```sh
python3 test-dbus-env.py /path/to/v2/kwin-android-rendering-env v2
python3 test-dbus-env.py /path/to/v3/kwin-android-rendering-env owned
```

## 构建与复现

将三个独立补丁按要求应用至 r26 后，运行本目录 `build.sh`；构建必须使用同版本私有 Qt headers。明确提供 source、build、cmake、ninja、clang、Termux-prefix 和 Qt-host-prefix，无工具探测或失败回退。

本轮实际路径：

```sh
bash build.sh /home/builder/desktop-fixes-20261030/qt-ahb-plugin/src \
 /home/builder/desktop-fixes-20261030/qt-ahb-plugin/build \
 /home/builder/.termux-build/_cache/cmake-4.4.4/bin/cmake \
 /home/builder/.termux-build/_cache/ninja-1.13.2/ninja \
 /home/builder/.termux-build/_cache/android-r30-api-24-v0/bin/aarch64-linux-android-clang++ \
 /data/data/com.termux/files/usr /data/data/com.termux/files/usr/opt/qt6/cross
```

Qt 插件、私有 EGL wrapper、Qt 实际 renderer probe 均已成功交叉编译。`libtermux-platform-ns.so` 是设备已有必需依赖，安装器明确检查。

新文件随完整 native 离线包安装；安装器检查原组件、校验所有 payload、备份现存文件并记录新增文件，不自动启动桌面。v3 只修正客户端环境与 D-Bus 同步脚本，native binaries 与 v2 相同。

## v4 启动冲突契约

`kwin-r26-existing-session-conflict.patch` 修正 v3 的已有进程入口：发现既有 `kwin_wayland` 时返回 73，不能仅凭 `pgrep` 报成功；在 socket 检查期间才发现进程的分支也返回 73。冲突保留现有 socket，不复用会话、不更改渲染模式、不终止未知进程。没有进程的陈旧 socket 仍按原规则清理。

正常新启动已有门禁保持不变：默认 15 秒内同时满足所启动 KWin 仍存活、请求的 Wayland socket 存在、当前日志包含 `Android EGL backend initialized`；提前退出或超时返回 1。native Plasma 还必须通过实际 Qt renderer 预检。后续 KWin 退出码透传。

`test-start-conflicts.py` 直接执行生产脚本的完整入口片段，在指定编译机通过真实 Unix socket 和受控 `pgrep` 检查五种契约：已有进程无 socket、已有进程有 socket、socket 检查时进程出现、无进程的陈旧 socket、全新会话。旧 v3 前三项误返回 0，负向对照退出 1；v4 五项通过，冲突均返回 73，保留 socket，未发送进程信号，入口锁正常释放。v4 只更新该启动脚本，native binaries 和 renderer helper 与 v3 相同；最终 v4 仍需主线程正式 App UI 复验。

v4 包为 `/tmp/termux-desktop-fixes-20261030/artifacts/kwin-qt612-native-fixes-20261030-aarch64-v4.tar.gz`，SHA256 `c358738df64b81e8abe8d4aa57b74978f2959e23f9061389f6d63f1371b2daf8`。下载包和全部 manifest 已核对；`v4-payload-proof.json` 保存 v3/v4 比较结果，payload 仅 `bin/start-plasma` 不同。实际回归日志为 `start-conflicts-v3-negative.log`、`start-conflicts-v4-final.log`。

## 正式 App UI 验收

以下结果由主线程操作正式 App 界面取得，诊断代理未操作设备。

最终包：`kwin-qt612-native-fixes-20261030-aarch64-v3.tar.gz`，SHA256 `e7024386c8a1db199f00acc9fa55c76ffbd2beb568e0b5c100a443d7c4251a87`。

- 模拟器：native System 启动、重复右键、Directtouch Dolphin Home 点击通过；实际 Qt preflight 为 `GL_RENDERER=Android Emulator`、`matches=1`。
- 小米 Mi 11 Ultra：最终 v3 native System 的 compositor 与 Qt client 日志均为 Adreno 660；正式系统设置 About 页面 Graphics Processor 确认 Adreno 660。重复右键、Directtouch 设置侧栏滚动与 About 单击、切竖屏及恢复横屏均通过。
- 小米截图：`/tmp/termux-desktop-validation-20261030/phone-native-qt-v3-about-ready.png`、`/tmp/termux-desktop-validation-20261030/phone-native-qt-v3-landscape-restored.png`。

## 键盘只读诊断记录

模拟器容器 Software 的 Discover 搜索曾在截图中未显示字符；只读日志已记录 `commitText(k/c/a/l/c)` 及相应 native Unicode 发送，Android 焦点指向 DesktopSessionActivity。该会话使用 `--compositor-only`，不执行 v3 native Qt 客户端环境分支；KWin 日志未记录 Unicode 映射失败。普通 ADB 字母键也经 Gboard 转为 `commitText`，因此不能据此独立判断硬件键与 Unicode 两条路径。

后续主线程通过 App 抽屉 Keyboard → Open keyboard，Discover 搜索栏显示 `kcalc` 并出现 KCalc 结果（`/tmp/termux-desktop-validation-20261030/emulator-discover-real-keyboard.png`）；正式详情页完成安装后显示 Launch，卸载后恢复 Install。先前截图缺字原因未证实，不能记为已确认 Unicode 丢失。生产键盘代码未修改，也未凭既有 `sendKey()` 零时间戳推断根因。
