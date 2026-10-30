# Termux USB bridge

无需修改或重编译 **termux-app**。复用 Termux:API APK 与 `termux-api` 包提供的
`termux-usb`，将 Android 授权的 USB FD 传给 native/PRoot 进程。
Termux 和 Termux:API APK 必须来自兼容的签名来源；仅安装命令行包不够。

0.4.1 包含 Python broker、C 客户端、私有 libusb **1.0.30** backend、native 适配和桌面卷集成：

- `tusb-camera`：Android 授权的 UVC 摄像头 → PipeWire `Video/Source`。
- `tusb-camera2`：手机内置 Camera2 相机 → 独立 Android 采集进程 → PipeWire。
- `tusb-camera-run`：为 GStreamer 应用加载包内私有 PipeWire 插件。
- `tusb-gamepadd` + SDL2/SDL3 preload：USB HID → 多进程共享状态 → SDL 虚拟手柄。
- `libtermux-usb-fs` + `tusb-files`：USB BOT/SCSI → FAT/exFAT → CLI / 鉴权 WebDAV。
- `tusb-desktop` + GIO 模块：USB 插拔 → Android 授权 → 自动服务 → Thunar 可移动卷。

不修改 termux-app 或 KWin，不覆盖系统 libusb。

## 构建

```sh
cmake -S . -B build -DCMAKE_INSTALL_PREFIX="$PREFIX" -DTUSB_ADAPTERS=ON -DTUSB_DESKTOP=ON -DBUILD_TESTING=OFF
cmake --build build -j2
cmake --install build
```

完整构建需要 `python cmake clang make termux-api libusb libjpeg-turbo pipewire sdl2 sdl3 glib gvfs dbus`。
Android Camera2 加 `-DTUSB_CAMERA2=ON`；私有 GStreamer 插件加 `-DTUSB_GSTREAMER=ON`，
并安装 `gstreamer gst-plugins-base`。正式配方已启用这两个选项。
Linux 使用对应的开发包。macOS 可不启用 `TUSB_ADAPTERS`，仅用 `-DTUSB_FILESYSTEM=ON`
构建 broker/HID/磁盘镜像测试；USB backend 面向 Linux/Android。
安装后的公共头为 `termux_usb_bridge.h`，pkg-config 名为 `termux-usb-bridge`。

## Termux 使用

正式 aarch64 包：`dist/termux-usb-bridge_0.4.1_aarch64.deb`。

```sh
pkg install x11-repo
apt install ./termux-usb-bridge_0.4.1_aarch64.deb
```

模拟器正式安装验证及当前硬件验证范围见 [VALIDATION.md](VALIDATION.md)。

## XFCE / Thunar 自动设备发现

包会安装 GIO 卷监控模块和 XDG autostart。**首次安装后重新登录 XFCE，或重启 Thunar**，
使应用加载新模块。已有会话中可手动启动：

```sh
tusb-desktop serve --request-permission
```

此命令必须在桌面自己的 D-Bus 会话中运行。它复用已运行的 broker；没有 broker 时自动启动。
Android 设置中需允许 Termux/Termux:API 后台运行，并将 Termux:API 电池使用设为“不受限制”。
Android 15 实测未豁免时会拒绝启动 USB 服务，`termux-usb -l` 返回空输出；正常无设备应返回 `[]`。
枚举失败会保留已有设备状态，不会当作拔出；日志会提示检查后台运行权限。
每秒通过 Termux:API 检查 USB 地址。新设备已有权限时直接识别；`--request-permission`
允许每个新 USB 地址触发一次 Android 授权请求。不能绕过系统授权弹窗。
省略该选项时，未授权设备会显示为待授权卷，点击挂载或执行 `authorize` 再请求权限。
授权后只将 BOT 存储显示为磁盘；摄像头和 HID 不再显示为待授权磁盘。

- 存储默认只读，自动启动独立文件服务并用私有随机口令挂载 GVfs DAV。
- Thunar 的 Devices 侧栏显示可移动卷、名称、卸载/弹出按钮；容量通过 DAV quota 提供。
- GIO `GVolume.mount`、`GMount.unmount`、`GVolume.eject` 接入相同服务生命周期。
- 卸载先退出 GVfs，再结束文件服务、等待请求结束、同步并释放 USB 接口；同步失败会报错。
- 弹出后不自动重挂，直到检测到拔出/重新插入；不会断电或宣称完成硬件层面的 SCSI 弹仓。
- 摄像头自动发布 `tusb.camera.<设备ID>` PipeWire 节点；PipeWire 必须已经运行。
- 标准 HID 手柄自动启动服务，首个可用手柄提供默认 SDL socket；SDL 应用仍需 `tusb-sdl-run`。
  其他手柄的 socket 可通过 `status` 查询，使用 `TERMUX_USB_GAMEPAD_SOCKET` 选择。

```sh
tusb-desktop status
tusb-desktop authorize /dev/bus/usb/001/002
tusb-desktop mount /dev/bus/usb/001/002
tusb-desktop unmount /dev/bus/usb/001/002
tusb-desktop eject /dev/bus/usb/001/002
```

可用 `--no-automount`、`--no-camera`、`--no-gamepad` 关闭对应行为。
写入需要 `serve --read-write`，且必须先让 Android 卸载该卷；autostart 不启用写入。
服务状态和日志位于 `$TMPDIR/termux-usb-bridge/desktop/`，目录为 0700，口令文件为 0600。
USB 地址改变时会作为新的插入事件处理；无法识别两次轮询之间在相同地址完成的拔插。

这里提供 GIO/GVfs 卷，不创建 `/dev/sdX`、UDisks2 服务或内核挂载。
Thunar/GIO 可以直接读写 DAV；仅使用 POSIX 路径的程序仍需独立文件访问适配。
Qt Solid/UDisks2 的设备枚举不由本模块接管。

PRoot 中需要对应 glibc/musl 的 GIO 模块，并将 desktop 状态目录和同一个会话 D-Bus socket
映射进去；Bionic 模块不能直接加载。可在容器内单独编译模块：

```sh
cc -shared -fPIC src/desktop/volume_monitor.c $(pkg-config --cflags --libs gio-2.0 gio-unix-2.0 gmodule-2.0) \
  -o libtusb-volume-monitor.so
# 将模块安装到容器 GIO modules 目录并运行 gio-querymodules；设置映射后的状态目录：
export TERMUX_USB_DESKTOP_DIR=/run/termux-usb-desktop
```

不连接 USB 的真实 GVfs 集成验证（需 clang、pkg-config）：

```sh
clang "$PREFIX/share/termux-usb-bridge/sdk/tests/desktop_probe.c" $(pkg-config --cflags --libs gio-2.0) \
  -o "$TMPDIR/tusb-desktop-probe"
dbus-run-session -- python "$PREFIX/share/termux-usb-bridge/sdk/tests/test_desktop.py" "$TMPDIR/tusb-desktop-probe"
```

此测试使用镜像模拟插拔，实际经过 GVolume/GMount、D-Bus、GVfs 和 DAV，不能替代物理 USB 验证。

## 手动使用 broker

先启动 broker，在另一个终端授权和使用设备：

```sh
export TERMUX_USB_BRIDGE_SOCKET="$TMPDIR/termux-usb-bridge/broker.sock"
termux-usb-bridge serve
```

```sh
export TERMUX_USB_BRIDGE_SOCKET="$TMPDIR/termux-usb-bridge/broker.sock"
termux-usb-bridge list
termux-usb-bridge authorize /dev/bus/usb/001/002
tusb-probe /dev/bus/usb/001/002
termux-usb-bridge exec /dev/bus/usb/001/002 -- your-program
```

`exec` 将已打开 FD 放到 `TERMUX_USB_FD`，程序必须主动消费此 FD；不会自动修改任意程序。
`tusb-probe` 默认读取原始描述符；`-DTUSB_WITH_LIBUSB=ON` 构建使用
`libusb_wrap_sys_device` 的探针，需要 libusb 开发包。

设备打开和 libusb 枚举**不会弹授权框**。应先显式 `authorize`，未授权设备可能出现在
broker 列表中，但 libusb 无法读取其描述符，因此会略过。重插/重枚举后应再次检查授权。
USB video 设备还需要 Termux:API APK 获得 Android 相机权限。

## 让现有 libusb 程序使用 bridge

下载并校验 libusb 官方 1.0.30 发布源码后，在 **Termux 或 Linux** 中运行：

```sh
bash scripts/build-libusb.sh /absolute/path/to/libusb-1.0.30 \
    "$PREFIX/opt/termux-usb-bridge"

export TERMUX_USB_BRIDGE_SOCKET="$TMPDIR/termux-usb-bridge/broker.sock"
LD_LIBRARY_PATH="$PREFIX/opt/termux-usb-bridge/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" your-libusb-program
# 同样可以用已安装的启动器：
termux-usb-run your-libusb-program
```

脚本复制源码后打补丁，失败不会修改输入源码。`scripts/apply-libusb.py SOURCE` 则原地打补丁，
可用于 termux-packages 配方。源码锚点不匹配会拒绝继续；目前只验证 1.0.30。
新增文件通过 Linux usbfs 编译单元包含，不需要修改 libusb 构建系统。
合成后的 libusb 仍遵循上游 LGPL-2.1-or-later，新增 bridge 文件为 MIT。

只有设置 `TERMUX_USB_BRIDGE_SOCKET` 才启用 backend；未设置时保留原来的 Linux backend。
设置后，不应在同一进程的 libusb context 生命周期内修改/移除此环境变量。
backend 替换设备发现、FD 打开和热插拔来源，传输、URB 和接口操作仍使用上游代码。
热插拔每秒轮询；同一轮询间隔内消失又以相同地址出现的设备可能无法被区分。
拓扑/端口信息不完整，设备速度可能为 unknown；不保证所有依赖 sysfs/udev 的应用兼容。
静态链接、内置私有 libusb、直接访问 hidraw/evdev/V4L2 的程序不在透明兼容范围。

## PRoot

broker 在外侧 Termux 运行；容器里构建 **glibc/musl 版本**的客户端或 patched libusb。
不能把 Bionic `.so` 直接复制给 glibc 程序。

将 socket 所在目录映射进去，例如给 `proot` 增加：

```sh
-b "$TMPDIR/termux-usb-bridge:/run/termux-usb-bridge"
```

容器内设置：

```sh
export TERMUX_USB_BRIDGE_SOCKET=/run/termux-usb-bridge/broker.sock
# 假设 patched libusb 私有安装到 /opt/termux-usb-bridge
LD_LIBRARY_PATH="/opt/termux-usb-bridge/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" your-libusb-program
```

使用同架构用户空间。PRoot 的 fake root 不会改变内核中的 UID；服务端按真实 UID 校验。
它不创建真实 `/dev/video*`、`/dev/input/*`、块设备或文件系统挂载。

## UVC 摄像头 → PipeWire

连接已启动的 PipeWire 服务（`XDG_RUNTIME_DIR` / `PIPEWIRE_REMOTE` 与桌面会话一致）：

```sh
termux-usb-bridge authorize /dev/bus/usb/001/002
tusb-camera --device /dev/bus/usb/001/002 --width 640 --height 480 --fps 30
```

依次协商 YUYV、MJPEG，转换为 RGB 发布为 `tusb.camera` 节点；可用 `--name` 指定节点名。
原始 FD 由 broker 交给 libuvc，不需要 Android 系统把摄像头注册到 Camera2。
Termux:API APK 仍需获得 Android 相机权限。PipeWire 客户端或桌面 session manager 可连接此节点，
不产生 V4L2 `/dev/video0`。浏览器能否识别还取决于其 PipeWire/portal 相机支持。

GStreamer 图形相机还需 `pipewiresrc` 插件和用于自动连接的 WirePlumber。
0.4.0 包含私有上游 PipeWire 1.4.9 插件；用 `tusb-camera-run YOUR_GSTREAMER_APP` 加载，
不覆盖系统插件。XFCE 上游 GTK `gst-camera2` 应用的验证见 `tests/xfce_camera_gui.md`；
模拟器需要选择 `ximagesink`。UVC 测试输入仍为合成图案。
仅支持 V4L2 的相机应用仍不能直接访问此源。

手机内置相机使用下面的正式 `tusb-camera2` 命令。之前的独立验证探针仍保留在 SDK tests 中。

无硬件验证：

```sh
tusb-camera --test-pattern --width 64 --height 48 --fps 10 --seconds 20
pw-link -o
```

完整摄像头软件链路检查（需 clang、pkg-config）：

```sh
sh "$PREFIX/share/termux-usb-bridge/sdk/tests/android_camera_check.sh"
```

包含 YUYV/MJPEG 像素解码、坏帧/尺寸/步长检查、五种分辨率/帧率的独立 PipeWire 采集、
RGB 像素验证和服务断连退出，并保留 PPM 截帧。输入为编码样例和合成图案；Android 模拟器的
Camera2 虚拟摄像头不是 USB UVC，不能用它证明 USB 传输、格式协商或摄像头授权可用。

USB 摄像头断连后应退出并重新授权/启动；不承诺在地址变化后自动恢复到同一物理摄像头。

## 手机内置 Camera2 相机

```sh
tusb-camera2 list
# ID、YUV 分辨率和 FPS 范围来自 list，不假定所有手机使用相同 ID。
tusb-camera2 serve --camera 10 --width 640 --height 480 --fps 30
# 可选 --seconds 20 限时运行，--name your.camera 自定义 PipeWire 名称。
tusb-camera-run gst-device-monitor-1.0 Video/Source
tusb-camera-run YOUR_GSTREAMER_CAMERA_APP
```

先在 Android 设置中给现有 Termux:API 授予相机权限；该方案要求其与 Termux 共享 UID，
并且 Android 允许该 UID 当前使用相机。后台不活跃 UID、AppOps、相机隐私开关和其他应用占用
仍可导致失败。不能仅靠安装 deb 声明/授予权限，也不会绕过 Android 前台服务规则。
`list` 输出 ID、朝向、传感器方向、YUV 尺寸和 AE FPS 范围。输出保留传感器原始方向，
不自动旋转或镜像；应用需要根据列出的 orientation 自行处理。

监督进程把 Camera2/AImageReader 放在只加载系统 NDK 库的独立 native 子进程中，
以有界帧协议传输 RGB、原始时间戳和序列号。另一子进程发布 PipeWire 源，
把相机时间戳平移到图时钟域，保留帧间隔；未验证音视频同步和端到端延迟。
采集使用最新帧缓冲，消费者较慢时会丢弃中间帧，避免在相机回调中阻塞管道。
停止、相机错误、管道错误或 PipeWire 断连时会清理两个子进程及节点。

独立进程需通过系统导出的 `ABinderProcess_startThreadPool` 初始化 Binder 线程池。
这是平台接口，并非应用公开稳定 NDK ABI；当前验证 Android 15/API35，其他版本和厂商 ROM
需实测。缺少接口时明确失败。YUV 转 RGB 当前使用 BT.601，未实现 HDR/色彩管理。

## 在容器中消费设备

Android USB/Camera2 的权限与采集进程留在宿主 Termux；容器运行自身发行版的客户端。
相机消费走 PipeWire 协议，glibc/musl 与 Bionic 不需要共享 `.so`。
对于同内核、同真实 UID 的 PRoot，可把宿主运行目录映射进去：

```sh
# 宿主：当前 PipeWire 运行目录，须与 tusb-camera2 的环境一致。
camera_runtime="$XDG_RUNTIME_DIR"
proot -0 -r "$ROOTFS" -b /dev -b /proc \
  -b "$camera_runtime:/run/tusb-pipewire" -w / \
  /usr/bin/env -i PATH=/usr/bin:/bin \
  XDG_RUNTIME_DIR=/run/tusb-pipewire PIPEWIRE_RUNTIME_DIR=/run/tusb-pipewire \
  PIPEWIRE_REMOTE=pipewire-0 pw-dump
```

容器安装自己的 PipeWire、GStreamer 和 PipeWire 插件。例如 Alpine 的插件包是
`gst-plugin-pipewire`；Debian/Ubuntu 通常是 `gstreamer1.0-pipewire`。
容器内 `gst-device-monitor-1.0 Video/Source` 可发现宿主源，GStreamer 相机应用可使用该源。
不要在容器里设置指向 Termux Bionic 插件的 `GST_PLUGIN_PATH`。

其他适配层也可用，但入口不同：USB libusb 客户端、SDL preload、GIO 模块需在容器内编译为
对应 glibc/musl ABI，并映射各自的 broker/gamepad/desktop socket；GIO 桌面还需正确的 D-Bus 会话。
存储也可直接消费鉴权 WebDAV，独立网络命名空间要配置可达的宿主地址。

Docker/LXC 只有在与宿主共享内核、Unix socket 可达、真实 UID/映射及 PipeWire 权限允许，
并放行所需的 Unix socket/FD 传递和共享内存操作时，才能采用同样方式。PRoot 的 fake root
不会增加 Android 权限；另一个 VM/远端机器不能直接绑定本机 socket，需要额外网络转发层。
本桥接不创建 V4L2/evdev/块设备，也不让只认这些内核接口的程序自动兼容。

## HID 手柄 → SDL2 / SDL3

```sh
termux-usb-bridge authorize /dev/bus/usb/001/003
tusb-gamepadd --device /dev/bus/usb/001/003 \
  --socket "$TMPDIR/termux-usb-bridge/gamepad.sock"
```

另一个终端启动 SDL 程序：

```sh
tusb-sdl-run your-sdl2-game
tusb-sdl-run --sdl3 your-sdl3-game
```

服务独占 HID 接口，多个 SDL 进程读取同一状态；支持最多 32 个按钮、8 个轴及一个方向帽。
解析 Generic Desktop Joystick/Gamepad collection、report ID、逻辑范围及 null hat。
默认映射 X/Y 为左摇杆、Rx/Ry 为右摇杆、Z/Rz 为扳机；设备布局不同可通过 SDL 的映射机制调整。
preload 拦截初始化和事件更新，在应用的 SDL 线程中创建、更新、移除虚拟手柄。

仅覆盖动态 SDL2 ≥ 2.24 / SDL3 ≥ 3.2 程序，以及标准 HID interrupt IN 手柄。
厂商私有 Xbox 协议、蓝牙 HID、震动/LED/陀螺仪未实现；不会假装反馈命令成功。
断连会移除 SDL 虚拟手柄；USB 重枚举后需重新授权并用新设备路径重启服务。
PRoot 内需要编译对应 glibc/musl 的 SDL preload，并将 `TERMUX_USB_GAMEPAD_SOCKET`
设置为映射后的 socket 路径，不能直接加载 Bionic preload。

## USB 存储 → FAT/exFAT → 文件服务

USB 传输支持 BOT/SCSI transparent command set、LUN、READ CAPACITY(10/16)、
READ/WRITE(10/16)、512–4096 字节扇区；文件系统支持 FAT12/16/32、exFAT、长文件名和 UTF-8。
自动选择第一个兼容分区；当前每个进程挂载一个卷。不支持 UAS、NTFS、ext4 或系统级挂载。

```sh
termux-usb-bridge authorize /dev/bus/usb/001/004
tusb-files --device /dev/bus/usb/001/004 ls /
tusb-files --device /dev/bus/usb/001/004 cat /document.txt
tusb-files --device /dev/bus/usb/001/004 --read-write put ./local.txt /remote.txt
tusb-files --device /dev/bus/usb/001/004 --read-write serve --port 18080
```

默认只读；写入要求显式 `--read-write`。原始 USB 写入前必须让 Android 系统卸载该卷，
避免系统文件缓存和用户态驱动同时修改磁盘。不要同时启动多个文件服务访问同一可写卷。

WebDAV 仅监听 `127.0.0.1`，强制 Basic 鉴权；启动时输出用户名 `tusb` 和随机口令。
也可用 `serve --token-file FILE` 读取 0600 口令文件。GIO 使用 `dav://tusb@127.0.0.1:18080/`，
KIO 可使用 `webdav://tusb@127.0.0.1:18080/`，按提示输入口令。
0.4.1 兼容 KIO 在 MOVE/COPY 的 Destination URL 中携带认证信息；同源校验仍比较主机和端口。
未认证上传会以固定缓冲、最多 10 秒消费请求体，避免连接重置丢失 401；
`Expect: 100-continue` 则先检查认证，不要求客户端先发送请求体。
支持 GET/HEAD/Range、PROPFIND Depth 0/1、PUT、MKCOL、DELETE、MOVE、COPY。
不提供 LOCK；上传先写临时文件，覆盖时保留备份后切换，不能承诺掉电时的 POSIX 原子性。
WebDAV URI 不等于所有 CLI 都能访问的本地目录；CLI 可使用 `tusb-files` 或 WebDAV 客户端。

不连接外设的磁盘镜像验证：

```sh
tusb-files create-image ./new-fat.img --mib 64
tusb-files create-image ./new-exfat.img --mib 64 --exfat
tusb-files --image ./new-exfat.img --read-write mkdir /demo
tusb-files --image ./new-exfat.img --read-write put ./local.txt /demo/test.txt
tusb-files --image ./new-exfat.img cat /demo/test.txt
```

`create-image` 只允许创建不存在的新文件，不会格式化既有文件或物理 USB 设备。

## 测试与模拟

```sh
cmake -S . -B build -DBUILD_TESTING=ON
cmake --build build -j2
ctest --test-dir build --output-on-failure
```

测试覆盖 C/Python 协议互通、真实 FD 传递、CLOEXEC/exec 继承、并发独立打开、路径限制、
协议分片/错误、设备移除、重复启动，以及模拟 `termux-usb` 的回调、拒绝授权和描述符偏移复位。

无硬件调试：创建 `devices.json`，将 USB 路径映射到本地文件：

```json
{"/dev/bus/usb/001/002": "descriptor.bin"}
```

```sh
python3 broker.py serve --mock-json devices.json
```

这是**普通文件 FD mock**，不支持 USBDEVFS ioctl/URB，不能用于宣称 USB 传输、UVC 或 SCSI 已验证。
修改 manifest 会改变后续枚举；已传出的 FD 按普通打开文件语义继续有效。

Linux/QEMU VM 可运行 `python3 broker.py serve --usbfs`，对 QEMU 的真实虚拟 USB 节点进行测试；
该 backend 依赖当前用户已有的 `/dev/bus/usb` 权限，不会提权。
`tests/libusb_smoke.c` 可链接 patched libusb，验证双 context 枚举和退出；空列表通过不代表传输通过。

Android 交叉构建：

```sh
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_INSTALL_PREFIX=/data/data/com.termux/files/usr -DBUILD_TESTING=OFF
cmake --build build-android -j2
```

## Termux 打包

### Plasma 桌面验证

`tests/android_plasma_devices.py` 在隔离 KWin X11/Plasma 会话中验证 Dolphin/KIO
对 FAT32、exFAT 镜像的读写及 Camera2 → PipeWire → 上游 GTK 相机的预览、拍照。
PRoot 端使用发行版的 glibc、PipeWire/GStreamer 插件，不加载 Termux Bionic 库。
Debian 除桌面外需要 `gstreamer1.0-pipewire`、`gstreamer1.0-x`、GStreamer base/good/bad、
`gstreamer1.0-plugins-base-apps`、`dbus-x11`、`xdotool` 和 `x11-utils`。
缺少 `gstreamer1.0-x` 时自动输出可能回退到 fake sink，拍照成功不代表预览成功。
测试脚本先检查 `ximagesink`、`pipewiresrc` 和 `jpegenc` 是否存在；
图形运行完成后还须用带 Pillow/NumPy 的 Python 运行
`tests/verify_plasma_images.py <结果目录>`，比对实际预览与 JPEG 像素。

这不覆盖物理 USB、Dolphin Solid/UDisks 设备自动发现、Kamoso/QtMultimedia 或
KWin Android/Wayland 后端。验证记录见 `dist/plasma-0.4.1/VALIDATION.md`。

### 构建

```sh
python3 scripts/package-source.py
# 将 dist/termux-usb-bridge 整个目录复制到 termux-packages/packages/
# 在 termux-packages 构建环境中：
./build-package.sh -a aarch64 termux-usb-bridge
```

生成固定时间戳的源码归档及带 SHA256 的本地源配方。配方编译并安装全部 native 适配层和私有 patched libusb；不替换系统 libusb。

已有 Android CMake 构建时，也可直接生成本地 `.deb`，无需宿主安装 dpkg：

```sh
python3 scripts/package-binary.py build-android --architecture aarch64
# 可加 --libusb /path/to/patched/libusb-1.0.so --libusb-source /path/to/libusb-1.0.30
# 将私有 patched libusb 一同装入包，不覆盖系统 libusb。
```

完整验证以 Linux `ctest` 的五组测试为准：broker FD、HID 解析、FAT/exFAT + WebDAV、
SDL2/SDL3 实际状态注入、PipeWire 视频源到独立接收端的连续 RGB 帧传输。
摄像头测试使用合成图像；手柄测试使用 HID 描述符与 report 回放。
测试不等于已验证所有物理外设或 Android 相机授权策略。

Android arm64 已运行相同测试（含 PRoot fake root FD 访问、SDL2/SDL3 和 PipeWire 实际帧传输）。
隔离 QEMU Linux VM 中已验证真实虚拟 USB U 盘的 USBDEVFS → BOT/SCSI → FAT 路径：
写入/读取 131,123 字节、重命名、同步和关机后宿主读回校验。
细节见 [VALIDATION.md](VALIDATION.md)。

## GNOME native / PRoot

GNOME Shell 48.7、Session 48.0、Files 48.3 的 rootless X11 配方与启动器见
[`packaging/gnome/README.md`](packaging/gnome/README.md)。native 使用指定编译机的
termux-packages 工具链打包；PRoot 使用发行版 glibc 包，不修改 termux-app。
Pixel Fold 上两种环境的 Files 自动卷发现、FAT32/exFAT 写入持久化及前后摄 GUI 拍照均通过。
独立 deb、日志、照片与像素校验在 `dist/gnome-20261030/`。
Snapshot portal 相机仍有 SIGBUS；物理 USB/UVC 和物理手机摄像头尚未验证。

## FD 与权限边界

socket 父目录必须属于当前 UID 且权限为 0700；socket 为 0600；双端检查真实 UID。
broker 不删除已有 socket，避免破坏运行中的实例。异常退出的 stale socket 需确认无服务后手动移除。
每次打开分别取得 FD，不缓存并共享同一 open file description；接口独占由内核/libusb 处理。
已经通过 SCM_RIGHTS 交出的 FD 无法由 broker 可靠撤回，停止 broker 不会收回它。
同 UID 的 PRoot 之间也不构成安全隔离。当前只适合用户自己信任的 native/PRoot 程序。
