# GNOME rootless X11 移植

Android GPU backend 的实现、启动方式、Pixel Fold 实测及限制见
[ANDROID_RENDERING_DESIGN.md](ANDROID_RENDERING_DESIGN.md)。下文 X11 验证与该 GPU 验证分别记录。


目标：Termux 原生 Bionic 和 PRoot glibc 运行 GNOME Shell/Mutter、Session、Files，并复用
USB bridge 的 GIO/GVfs 存储和 PipeWire 相机接口。不修改 termux-app。

GPU 后端尚未实现；对照最新 KWin 6.6 r27 的改造边界和实施顺序见
[ANDROID_RENDERING_DESIGN.md](ANDROID_RENDERING_DESIGN.md)。

配方入口是 `build.sh`。编译机基线为 termux-packages
`6c7f8ef0524bf3e0882301f397bbb6929ea99b5f`。本目录只包含本轮新增或调整的配方；
`spidermonkey/` 应覆盖原有同名配方的对应文件，保留仓库的 0001–0026 补丁。
`mutter/` 从该基线的 disabled-packages/mutter 移入 x11-packages；避免启用全部 disabled 包。
其余目录复制到 `x11-packages/<包名>/`；`common/` 和 `launch.py` 不是包。

## 构建

在指定 Linux 编译机 `/workspace/termux-packages` 使用官方工具：

```sh
./build-package.sh -a aarch64 -f -s -j 8 mutter
./build-package.sh -a aarch64 -f -s -j 8 spidermonkey
./build-package.sh -a aarch64 -f -s -j 8 polkit
./build-package.sh -a aarch64 -f -s -j 8 gjs
./build-package.sh -a aarch64 -f -s -j 8 libgdm
./build-package.sh -a aarch64 -f -s -j 8 accountsservice
./build-package.sh -a aarch64 -f -s -j 8 gnome-session
./build-package.sh -a aarch64 -f -s -j 8 gnome-autoar
./build-package.sh -a aarch64 -f -s -j 8 gnome-settings-daemon-schemas
./build-package.sh -a aarch64 -f -s -j 8 localsearch-schemas
./build-package.sh -a aarch64 -f -s -j 8 pipewire-dbus
./build-package.sh -a aarch64 -f -s -j 8 gexiv2
./build-package.sh -a aarch64 -f -s -j 8 nautilus
./build-package.sh -a aarch64 -f -s -j 8 gnome-shell
```

SpiderMonkey 128 的 Mach 不支持主机 Python 3.14；在 PATH 前放 Python 3.11。
本轮使用官方 Rust 1.99，修正旧 Mozilla 对 sanitizer/OpenEmbedded 目标三元组的识别，
并为 Rust 的 C++ 子构建显式保留 NDK API 24 目标。GJS 跨编译只执行链接探测，
运行时检查另在 Android 实施；生产 GjsPrivate introspection 保留。

`gir/*.xml` 是真实 Android/Bionic 程序运行生成的 GType dump。新增目标架构或变更
类型布局时需重新生成，不得用空 XML 跳过。保留 `GI_SCANNER_DEBUG=save-temps` 后，将
`tmp-introspect*/<Namespace>`、functions.txt 和本包真实共享库传至 Android，运行：

```sh
LD_LIBRARY_PATH="$probe_dir:$PREFIX/lib" "$probe_dir/Namespace-1.0" \
  --introspect-dump="$probe_dir/functions.txt,$probe_dir/Namespace-1.0.xml"
```

取回 XML 放入配方 gir 目录后重建。正式 deb 仍须通过官方 ELF 符号检查。续编译 `-c` 不执行 pre_configure，需保留
`gir-cross/bin`、Ninja 的 PATH，以及 GI_CROSS_LAUNCHER 和
TERMUX_PKG_GIR_PRE_GENERATED_DUMP_DIR 环境；完整构建不需要此续编译处理。

`libgdm` 包含 Shell 必需的上游 login-screen schema。两个 `*-schemas` 包只安装上游
schema/enum，不提供设置或索引服务。`nautilus` 显式依赖 tinysparql 动态解析模块需要的
libstemmer，并约束 gexiv2 0.14 ABI；0.16 的库名/API 已改变，不能混装替代。
`pipewire-dbus` 补充 Termux PipeWire 原配方关闭的真实 D-Bus/portal 模块，不替换 daemon。

最终包在 `../../dist/gnome-20261030/`，旧修订移入 `superseded/`。在已配置 Termux X11
源的专用环境内，先安装 USB bridge 0.4.1 和 termux-x11-nightly，再安装该目录顶层
全部 deb；现有 gexiv2 0.16 环境需显式允许降级到同目录 0.14.7。不要递归安装旧包。

## 会话

桌面为 X11/llvmpipe。`launch.py` 创建仅本 App UID 可访问的真实 system/session D-Bus，
并使用上游非 systemd GNOME Session 后端。system bus 不提供伪造的 login1、Polkit 或 GDM。

```sh
# Native：需要已安装本目录构建的 GNOME 包和 termux-x11-nightly。
python launch.py --work "$HOME/gnome-native" --display :6 --minimal-services \
  --bridge --pipewire

# PRoot：rootfs 内安装发行版 GNOME Shell/Session/Nautilus、Mesa、GVfs、dbus-x11。
# 运行阶段不使用 proot -0；安装软件阶段才可使用发行版安装器的 fake root。
python launch.py --rootfs "$ROOTFS" --work "$HOME/gnome-proot" \
  --display :6 --minimal-services --bridge --pipewire \
  --gio-modules "$HOST_GLIBC_MODULE_DIR" --gio-helper "$HOST_GLIBC_GIO_HELPER"
```

Ctrl+C/SIGTERM 结束本次 launcher 的子进程。日志在 work 目录；任一必需服务退出会报错并清理。
USB 默认只读，`--read-write` 显式启用写入。`--bridge` 不自动弹 USB 授权对话框，
设备出现后用 `tusb-desktop authorize <device>` 授权。`--pipewire` 不自动打开手机相机；
在另一个正常 Termux App shell 中 source `work/host-session.env` 后显式运行
`tusb-camera2 serve --camera ID --name NAME`。需已安装并授权标准 Termux:API。
`--minimal-services` 只允许 dconf、GVfs 核心和 GNOME 通知/屏保接口在私有 D-Bus 自动启动，
减少 Android phantom-process 上限的影响；未启用在线账户、Tracker 搜索和 desktop portals。
不加此选项使用发行版通常的 D-Bus 激活目录，需要设备允许足够的后台子进程。

PRoot 必须使用 glibc 版本的 GIO 模块和 GStreamer/PipeWire 插件，不能加载 Bionic `.so`。
存储服务和 Camera2 采集留在宿主，容器使用真实 GVfs DAV 和 PipeWire 协议。
`--gio-modules` 和 `--gio-helper` 接受该 rootfs ABI 的 glibc 产物；PRoot 的
`--bridge` 强制检查两者，自动生成进入同一容器 D-Bus 会话的 helper wrapper。
本轮产物由 `../../tests/android_gnome_devices.py` 从 volume_monitor.c 和 gio_mount.c
在所选 rootfs 中编译；完整实现也包含可复用的 host-gio wrapper。Native 启动器会
导出 USB bridge 的 GStreamer 插件路径；PRoot 使用发行版 glibc GStreamer/PipeWire 插件。

## 验证

专用目标为 Pixel_Fold_API_35 / emulator-5556，Android 15 arm64，普通 App UID 10200。
不要把本轮命令用于其他正在使用的模拟器。

- Native 与 PRoot GNOME Shell 48.7 / Session 48.0 / Files 48.3：实际 Shell 顶栏、
  overview、GTK4 Files 和 GTK3 相机窗口显示并操作通过。Native 包全部使用编译机
  termux-packages 工具链打包，PRoot 使用发行版 glibc 包。
- FAT32、exFAT：GIO 自动发现唯一 USB 卷；Files GUI 建目录；131123 字节中文文件
  复制、改名、读回、删除；卸载后重新打开镜像确认持久化。
- Camera2 前后摄：WirePlumber 自动连接；未修改的上游 gst-camera2 GTK 应用预览，
  每摄两次 GUI Capture；节点停止后消失。
- 每种环境各四张 JPEG，均为 640×480。PRoot 截图/照片相关系数 0.949–0.978，
  RGB MAE 5.20–11.26；Native 为 0.968–0.983 / 4.59–9.60。连续两次照片内容变化。
- 独立 launcher 的 native/PRoot `--bridge --pipewire` 启动、真实服务接口、退出清理通过。
  PRoot helper wrapper 已实际执行 glibc ELF；Native 已核验桥接版 pipewiresrc 加载路径。
- Native GJS：实际运行 JS，GLib/GIO/GDM/Polkit GI 调用通过。

组合测试证据位于 `../../dist/gnome-20261030/n-devices3/` 和 `p-devices5/`；
最终启动器证据为 `launcher-native-final/`、`launcher-proot-final/`。
Native 组合测试时 Files 为 48.3-2，最终 48.3-3 仅补 gexiv2 ABI 依赖约束，已重新安装
并运行启动器；图片属性扩展 dlopen 也已验证。像素校验器沿用
`../../tests/verify_plasma_images.py`，现同时支持 GNOME/Plasma 证据格式。
准备测试源码目录需放入 volume_monitor.c、gio_mount.c、desktop_probe.c 及上游
`gst-camera2.{c,h,ui}`（来源与哈希见 `../../tests/xfce_camera_gui.md`）。

## 范围

- 不提供 GDM 登录、Android 锁屏、系统账户管理、提权认证、systemd 或 logind。
- Polkit、GDM、AccountsService 仅构建真实上游客户端库，缺失平台操作明确返回不支持。
- 本轮存储使用可丢弃 FAT32/exFAT 镜像，摄像头为模拟器 Camera2 HAL。
  未验证物理 USB BOT/UVC 传输、物理手机传感器或 Android/Wayland 后端。
- 相机 GUI 为未修改的上游 GTK/GStreamer 示例。Snapshot 48.0.1 在真实 portal
  授权后、OpenPipeWireRemote 阶段 SIGBUS，尚未解决；证据在 `snapshot-check4/`。
  Cheese 未验证，不能把示例相机通过等同于所有相机应用兼容。
- 本目录为实验 GNOME 配方，正式 USB bridge 仍为独立的 0.4.1 包。

测试使用专用 Pixel Fold API 35（emulator-5556）；PRoot 组合测试在默认进程上限通过。
Native 完整验证期间临时将 max_phantom_processes 提到 256，现已恢复为未设置；
allow-external-apps 配置原文和 0600 权限已恢复并 force-stop 清除缓存。
`restoration.json` 和 `cleanup-final.log` 记录恢复与零遗留测试进程。
