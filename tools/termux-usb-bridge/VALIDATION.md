# Termux USB bridge 验证记录

日期：2026-10-28。未修改 termux-app 或 KWin。

## 2026-10-30：GNOME native / PRoot

USB bridge 保持 0.4.1；GNOME 的独立配方、启动器和 deb 见
[packaging/gnome/README.md](packaging/gnome/README.md)。专用 Pixel Fold API 35 上验证：

- native 与 PRoot Shell/Session/Files 实际 GUI 运行；FAT32/exFAT 卷自动出现，GUI 建目录，
  131123 字节中文文件复制、改名、读回、删除，卸载后镜像校验通过。
- 前后摄经 Camera2 → host PipeWire → 对应 ABI 的 GStreamer → 上游 GTK 相机；
  每摄两次 GUI Capture，四张照片/预览经独立像素校验通过。
- native/PRoot 独立启动器的 bridge/PipeWire、真实 D-Bus 会话和退出清理通过。
- native 包由指定编译机官方 termux-packages 构建并通过 ELF 检查；PRoot 使用 glibc 包。
- 证据为 `dist/gnome-20261030/n-devices3`、`p-devices5` 和 `launcher-*-final`。
- Snapshot 48.0.1 在 portal 授权后 OpenPipeWireRemote 阶段 SIGBUS，仍未通过。
  存储为镜像，相机为模拟器 HAL；没有物理 USB/UVC 或物理传感器实测声明。
- 已恢复临时进程上限与 allow-external-apps；原配置内容/权限一致，无遗留测试进程。

## 0.4.1：KIO MOVE/COPY 同源校验

原生 Plasma 的实际 KIO 改名测试发现：Destination 使用 `http://用户:口令@主机:端口/路径`，
旧版按完整 netloc 比较而返回 400。现改为主机名、端口比较，认证仍独立检查；
新增带 userinfo 的 MOVE/COPY、跨主机/端口拒绝和未认证请求拒绝的回归。
Debian 旧版 KIO 的大文件 PUT 还暴露了未认证请求体未消费导致的 TCP 重置；
新增有时间/缓冲上限的丢弃处理，以及认证前的 100-continue 检查，覆盖大上传认证重试和原始 HTTP 握手。
Plasma/KWin 图形验证脚本为 `tests/android_plasma_devices.py`，明确使用隔离 X11 会话。
最终原生/PRoot 图形结果及构建证据随 `dist/` 提供；不把 X11 验证扩展为 Android/Wayland 后端验证。

- 原生 KWin X11/Plasma 6.7.5、Debian PRoot KWin/Plasma 6.3.6：Dolphin/KIO
  完成 FAT32/exFAT 镜像的目录创建、131123 字节文件上传、中文改名、下载、复制、删除；
  服务关闭后直接重开镜像，数据和删除状态均持久化。
- 两套桌面的未修改上游 GTK `gst-camera2` 自动连接 Camera2/PipeWire 前后摄，
  各保存两张 640×480 JPEG；预览截图经独立像素比对和人工查看通过。
  最终目录为 `n041done`、`p041final`，各含 `pixel-verification.json`。
- PRoot 最初缺少 Debian `gstreamer1.0-x`，导致自动输出未显示视频而拍照仍成功；
  安装标准插件后通过，不需要禁用 MIT-SHM或修改客户端。
  桌面脚本增加输出插件预检，像素校验独立于取帧/拍照成功状态。
- 使用模拟器 Camera2 HAL 和存储镜像，没有物理 USB、UVC、Solid/UDisks 自动发现、
  Kamoso/QtMultimedia、GPU 或 KWin Android/Wayland 后端的实测声明。
  模拟器仍有空间，未清理游戏或用户桌面资料。

## 0.4.0：正式 Camera2 命令与容器支持

新增 `tusb-camera2 list/serve`、独立系统库采集进程、原生 PipeWire 发布端，以及通过
`tusb-camera-run` 启用的私有上游 GStreamer PipeWire 1.4.9 插件。Camera2 传输协议检查长度、
尺寸、时间戳和序列；相机回调只更新最新帧，管道写入由独立循环完成，并保留相机帧间隔。

- 使用正式实现的预发布编译结果完成 240 帧限时采集和清理，以及后摄 XFCE 预览、GUI 两次拍照、
  正常退出；新增帧协议的空输入、截断头、非法 magic、超大长度、截断 payload、时间戳/序列回退、
  完整 EOF 共 8 项检查通过。
- Alpine 3.22/aarch64 PRoot（musl）的发行版 PipeWire 1.4.7、GStreamer 1.26.3 和
  `gst-plugin-pipewire`，通过映射宿主运行目录，发现并读取正式实现发布的 Camera2 源。
  相隔约 25 秒各采集 30 帧 PNG；宿主共捕获 885 帧后正常退出。容器未加载 Termux Bionic 库。
  这不构成 Docker/LXC、跨 UID 或其他虚拟机的实测。
- 指定 SSH 编译机密码认证失败，使用官方 `termux-packages` 固定提交
  `654ad00a2a224e57c8d128696f6a02bd108901e3` 的 on-device 模式替代构建：
  `bash build-package.sh -f -s -j 4 termux-usb-bridge`。`-s` 复用已安装依赖，不跳过 ELF 检查。
  配方在 on-device 模式使用 DESTDIR 独立暂存，避免扫描用户现有容器目录。
  15 个 ELF 通过官方清理和未定义符号检查，0.4.0 预验证包安装、相机枚举及私有插件加载通过。

安装后的最终回归、GUI 和容器复测日志随交付包存放在 `dist/`；构建日志为
`dist/tusb-camera2-0.4.0-build.log`。上面的旧原型记录保留用于说明实现演进；0.4.0 已提供正式命令，
不再需要自行编译验证端。Android 相机权限、活跃 UID、Binder 平台 ABI、原始方向和色彩限制仍适用，
未增加物理手机/物理 UVC、录像或 PRoot GUI 的验证声明。

## Camera2 → PipeWire → XFCE 图形相机验证

在普通 Termux App 子进程（UID 10200、`untrusted_app_27`）启动隔离 XFCE/X11 `:2` 会话，
复用已有 Termux:API 相机授权和未修改的上游 GTK `gst-camera2`。没有新增/修改 APK。

实际输入链路为：Camera2/AImageReader → YUV 转 RGB → Unix pipe → 原生 PipeWire
`Video/Source` → GStreamer `pipewiresrc` → GTK 相机窗口。Camera2 进程独立使用系统库，
桌面发布进程使用 Termux 库，避免上一轮发现的同名包装库冲突。

- 后摄 ID `10`：205 帧，204 次亮度校验变化；前摄 ID `1`：189 帧，159 次变化。
  帧输入为 640×480 `YUV_420_888`，实际 Camera2 时间戳递增。
- 标准 GStreamer 设备枚举分别发现 `Android Camera2 back/front`，报告 RGB 640×480、30/1 FPS。
  WirePlumber 自动连接 `tusb.camera2.back/front:capture_1 -> gst-camera2:input_1`，无需手工连线。
- 前后摄 GUI 预览均显示对应 HAL 场景；分别点击 Capture 两次，得到 640×480 JPEG。
  两次预览与两张 JPEG 均有变化。窗口图像与同次 GUI 保存图像的平均 RGB 绝对误差分别为
  后摄 9.631、前摄 5.996（含采集时间差和 JPEG 压缩），并已人工查看截图。
- 点击 XFCE 窗口关闭按钮后，图形应用正常退出。先停止 Camera2，再由 EOF 停止发布端，
  三个进程均退出 0；源节点和连接消失，专用桌面及服务已清理，相机无活动客户端。
- 最初使用上游 `pipewiresink mode=provide` 发布时，设备发现的临时消费者断开会触发
  `all buffers have been removed` 并使源退出；最终改用原生发布端，GStreamer 仅负责接收。
  未修改上游插件。初版退出顺序也会造成 Camera2 写管道 EPIPE，调整关闭顺序后通过。

新增验证程序：`tests/camera_rgb_source.c`、`tests/android_camera2_xfce.py`；
`tests/android_ndk_camera.c --stream CAMERA_ID FRAME_COUNT` 输出连续 RGB，诊断写 stderr。
复现步骤：`tests/xfce_camera_gui.md` 的 Camera2 部分。
证据：`dist/android-camera2-xfce/{back,front}/`，包含两次实际窗口截图、GUI 照片、设备枚举、
连接图、各进程日志及 `result.json`；总检查日志为 `dist/android-camera2-xfce-validation.log`。

该链路是 native 验证原型，未修改正式 `tusb-camera` 或重打 deb。RGB 管道未传递 Camera2 时间戳，
发布端按名义 30 FPS 重新打时间戳，本轮未测量 GUI 帧率或端到端延迟。输入为模拟器 Camera2 HAL，
前摄是 HAL 模拟图像，后摄是 HAL 虚拟房间；未验证物理手机、录像、锁屏、长期后台或 PRoot GUI。
独立 Camera2 进程仍依赖下面记录的 Binder 平台接口初始化。

## 手机内置相机：独立 native Camera2 验证

- 复用已安装且同签名、共享 UID 10200 的 Termux:API。通过系统设置将其 Camera 权限改为
  “Allow only while using the app”；没有 `pm grant`、root、新 APK 或 APK 修改。
  授权前 native 枚举成功，打开返回 `ACAMERA_ERROR_PERMISSION_DENIED`（-10013）。
- 使用 `tests/android_ndk_camera.c` 直接调用 NDK Camera2 / AImageReader；无 Java/JNI 采集或
  Termux:API 相机服务转发。通过既有 Termux RunCommandService 启动普通 App 子进程，
  UID 10200、SELinux `untrusted_app_27`，不是 adb shell/run-as 上下文。
- 前摄 ID `1`、后摄 ID `10` 分别连续获取 30 帧 640×480 `YUV_420_888`，检查三平面
  长度、行/像素步长和递增时间戳，并保存首末帧。前摄时间跨度 1030.227 ms、27 次亮度校验变化；
  后摄 1184.205 ms、29 次变化；两个进程退出码均为 0。
- Termux:X11 切到前台后立即重复采集，以及约 90 秒后再次启动采集，两个相机均通过；
  后一次前摄跨度 1002.047 ms、后摄 966.667 ms。仅验证当前定制 Termux/API 的系统状态，
  不代表任意后台状态、锁屏或长期持续采集均可用。
- 独立可执行文件没有 Java App 运行时已有的 Binder 接收线程池：省略初始化时能创建会话，
  但 15 秒内没有图像。测试通过 `dlsym` 调用系统导出的 `ABinderProcess_startThreadPool` 初始化。
  **它是平台接口，不属于应用公开稳定 NDK ABI**；当前仅验证 API35，跨系统版本需另行兼容验证。
- Termux clang 嵌入的 RUNPATH 会加载 `$PREFIX/lib/libmediandk.so` 等同名包装库；
  直接与系统 Camera2 混用时触发系统 Binder `Parcel::freeDataNoInit` 分配计数溢出中止。
  仅对独立探针设置 `LD_LIBRARY_PATH=/system/lib64` 使用系统库后通过；仅 unset 不足以排除 RUNPATH。
  该变量不能未经验证直接套到整个 XFCE/PipeWire 会话。

复现脚本：`tests/android_ndk_camera.sh`，需在实际 Termux App 子进程/会话内执行并传入相机 ID。
日志：`dist/android-ndk-camera-native.log`、`dist/android-ndk-camera-denied.log`、
`dist/android-ndk-camera-x11-foreground.log`、`dist/android-ndk-camera-x11-delayed.log`。
图像：`dist/android-ndk-camera-{front,back}.png`。
初始失败记录：`dist/android-ndk-camera-wrapper-failure.log`、`dist/android-ndk-camera-initial-crash.log`。

本轮图像来自模拟器 Camera2 HAL（前摄模拟图像、后摄虚拟房间），与之前 `tusb-camera --test-pattern`
是不同输入链路。证明无需新增 APK即可由 native 获取 Android 相机帧；后续桌面原型见上节。
尚未接入正式 `tusb-camera` 的 PipeWire 后端，也没有验证物理手机相机。
本轮授权保留在模拟器现有 Termux:API 上。

## XFCE 图形相机验证（0.3.0-1）

- 在 Android arm64 模拟器的真实 XFCE/X11 `:2` 会话运行 GStreamer 1.28.8 上游
  GTK 相机示例 `gst-camera2`，其 C/H/UI 源码未修改。该程序是上游相机示例，非 Cheese/guvcview。
- 当前 Termux 软件源没有 Cheese/guvcview，已安装的 PipeWire 包也没有 GStreamer 插件。
  在独立测试目录原生编译 PipeWire 1.4.9 上游 `src/gst` 插件，通过 `GST_PLUGIN_PATH` 加载；
  没有覆盖系统插件或修改正式 deb。
- `gst-device-monitor-1.0 Video/Source` 自动发现 `Termux USB camera`，报告 RGB 640×480、30/1 FPS。
  未修改的相机应用经 `autovideosrc` 选择 PipeWire，WirePlumber 自动建立
  `tusb.camera.gui:capture_1 -> gst-camera2:input_1`，没有手工 `pw-link`。
- GUI 动态预览通过；两次点击 Capture 生成 640×480 JPEG。解码后的采样像素与正式包
  `tusb-camera --test-pattern` 的 RGB 图案一致，两张照片及两次窗口预览均有变化。
  30 FPS 为源配置/协商值，本轮未测量 GUI 实际显示帧率。
- 模拟器上自动视频输出选中的 `cacasink` 在 libcaca 崩溃。通过
  `GST_PLUGIN_FEATURE_RANK=ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0`
  使用 X11 输出后预览、拍照通过；共享内存不可用时普通 X11 输出仍正常。
- 点击窗口关闭按钮后应用退出码为 0，PipeWire 连接消失；测试专用桌面和服务已清理。

复现：`tests/xfce_camera_gui.md`。证据：`dist/android-xfce-camera-validation.log`、
`dist/android-xfce-camera-devices.log`、`dist/android-xfce-camera-graph.json`、
`dist/xfce-camera-preview-{1,2}.png`、`dist/xfce-camera-capture-{1,2}.jpg`。

输入为合成图案，未实测物理 USB UVC；此次也未测试仅支持 V4L2 的应用、录像或 PRoot 内的 GUI。
正式 0.3.0-1 deb 尚未包含此次测试额外编译的 GStreamer PipeWire 插件，安装该 deb 本身不足以复现本轮 GUI 链路。

## 写入与摄像头补测（0.3.0-1）

- 再次通过真实 GVfs 对 exFAT 镜像写入二进制文件、建立中文目录、重命名和删除；
  卸载后重挂逐字节读回一致。默认只读模式拒写。日志：`dist/android-write-recheck.log`。
- 新增实际 YUYV/MJPEG 解码测试，覆盖 BT.601 颜色、行 padding、非八像素倍数、短帧/奇数宽度、
  长度溢出、输出边界、JPEG 与 UVC 尺寸不一致、损坏输入、坏帧后恢复，以及缺少 Huffman 表的 MJPEG。
  Android arm64 和本地 ASan/UBSan 均通过。
- 修复原 raw 转换器按八像素分组且不检查长度/步长的问题；MJPEG 改用 TurboJPEG，
  解码前核对 JPEG 头尺寸，失败后重建解码器。正常关闭不再被退出期间的 UNCONNECTED 事件误报为失败。
- 独立 PipeWire 消费者验证 64×48/10 FPS、18×3/10 FPS、640×480/30 FPS（30 帧）、
  1280×720/30 FPS（10 帧）、64×48/1 FPS，逐像素采样、RGB 行对齐与截帧长度检查通过。
  正常退出清理节点、PipeWire 服务断连退出均通过。
- 复现入口：`tests/android_camera_check.sh`；日志：`dist/android-camera-check.log`。
  正式包安装后的验证日志：`dist/android-camera-final-validation.log`。

模拟器的两个摄像头属于 Android Camera2 HAL，没有 USB Host UVC 摄像头。
本轮编码样例和 RGB 图案不构成 USB 传输、UVC PROBE/COMMIT 或 Android USB 相机授权的实测。

## 桌面卷集成（0.3.0）

正式包通过指定编译机的 `termux-packages/build-package.sh -I -w -f -a aarch64 -j 8 termux-usb-bridge`
构建，12 个 ELF 通过清理及符号检查。安装后运行的环境为 Android 15 / API 35 arm64-v8a，
Thunar 4.20.10、GVfs 1.62.0、GLib 2.90.1。

- 原有 broker、HID、FAT/exFAT/WebDAV、SDL2/3、PipeWire 的 Android 安装包测试通过；
  增加容量/同步和 Android 后台拒绝输出的回归检查。
- 真实 GIO/GVfs/D-Bus 会话通过插入事件自动创建 GVolume/GMount，并通过 DAV quota 查询到容量。
- 真正的 `gio` 客户端完成中文目录、二进制复制、重命名、删除和跨卸载/重挂的持久化读回。
- GMount 卸载、GVolume 弹出、直到重插前禁止自动重挂、拔出/重插的 GIO 信号全部通过。
- 默认只读拒绝真实 GVfs 写入；外部 GVfs 卸载会结束文件服务并清理口令；失败挂载无残留口令/端点。
- 在模拟器原生 X11 上启动 XFCE/Thunar，窗口打开后插入镜像：Devices 出现 `USB Test Disk`，
  Network 无重复挂载行。点击设备可浏览 `USB-demo.txt`，显示空闲容量 63.9 MiB。
- 点击 Thunar 侧栏弹出按钮，设备行消失、窗口退回主目录；服务端状态为 `ejected`、URI 为空。
- 真实 Android UsbManager → Termux:API → 自动启动 broker → 桌面协调器的空设备枚举通过，退出清理通过。
  Android 15 需要在系统设置允许 Termux:API 不受电池优化限制；已通过系统授权对话框验证。
- 实窗发现并修复了 GVfs 临时挂载对象、Thunar 不在 changed 时移除 shadow 行、旧卸载事件影响重挂等时序问题。
  并发回归还修复了 Android 空 `sendall` 在对端已回复关闭后偶发 EPIPE 的问题。

复现脚本：`tests/android_installed.sh`、`tests/test_desktop.py`、`tests/desktop_probe.c`。
最终日志：`dist/android-desktop-final-validation.log`、`dist/android-desktop-real-api.log`、
`dist/tusb-desktop-final-build.log`。
GUI 证据：`dist/thunar-usb-mounted.png`、`dist/thunar-usb-ejected.png`、
`dist/android-thunar-eject-status.json`。

本轮存储输入为 exFAT 镜像，未验证物理 USB 存储插拔、Android USB 授权弹窗或实际 UVC/HID 自动启动。
GVfs 在目录操作时有上游 `standard::size` critical 日志（其目录解析主动删除 size 后又读取）；
操作结果和持久化检查均通过。Termux GVfs 的点对点连接不可用时会回退到会话 D-Bus。

## 正式安装包模拟器复测（0.2.0-2）

设备：`emulator-5554`，Android 15 / API 35，arm64-v8a。
在既有 Termux 中通过 apt 安装正式 deb 和 SDL3，再通过 dpkg 升级到 `0.2.0-2`。
测试使用安装到 `$PREFIX` 的共享库、命令和 SDK；只在模拟器本地编译独立测试客户端。

- 发现并修复 `tusb-sdl-run` 和 pkg-config 的重复前缀：Termux 配方传入绝对 LIBDIR/INCLUDEDIR，
  原模板再次添加 PREFIX。改用 CMake FULL 路径，远端正式重编为 revision 2。
- `tests/android_installed.sh` 全部通过：broker 13 项、HID 3 项、FAT/exFAT/WebDAV 6 项，
  native/PRoot 私有 libusb、多 context、SDL2/3 状态注入、PipeWire RGB 帧采集。
- 安装后的 `tusb-sdl-run` 和 `tusb-sdl-run --sdl3` 分别通过两轮初始化/退出，
  查询到 `axes=-32768,32767 buttons=3 hat=2`。
- 安装后的 `tusb-files` CLI 在独立 exFAT 镜像创建中文目录、写入文件，跨进程读取与原文一致。
- 安装官方 Termux:API v0.53.0 GitHub APK，核对签名与现有 Termux 一致。
  真实 `termux-usb -l` 返回 `[]`；真实 API → broker → C 客户端和 native/PRoot libusb 均正常返回空列表。
- 该模拟器没有 USB Host 设备：UVC 输入使用合成帧、HID 使用描述符/report 回放、存储使用镜像；
  本轮没有验证 USB 授权弹窗、物理设备传输或 Android USB BOT 链路。

日志：`dist/android-installed-validation-r2.log`、`dist/android-real-api-validation.log`。
首次发现启动器缺陷的日志保留在 `dist/android-installed-validation.log`。
重编日志：`dist/tusb-native-r2-build.log`。

复现（Termux 内，需 clang、pkg-config）：

```sh
sh "$PREFIX/share/termux-usb-bridge/sdk/tests/android_installed.sh"
```

## 正式 native 构建

编译环境：用户指定的 SSH builder，`/workspace/termux-packages`。

```sh
./build-package.sh -I -w -f -a aarch64 -j 8 termux-usb-bridge
```

`-I` 保留既有 sysroot，`-w` 从仓库下载可用依赖版本。libusb 1.0.30 源码 SHA256：
`fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf`。

产物包含 9 个 ELF：broker 客户端、gamepad 客户端、filesystem、SDL2/SDL3 preload、
私有 libusb，以及 probe、camera、gamepadd；均经过 termux-packages 的 ELF 清理与符号检查。

## 已执行

| 环境 | 验证 |
|---|---|
| Linux x86_64 宿主 | CTest 五组全部通过：broker、HID、filesystem/WebDAV、SDL2/3、PipeWire |
| Android arm64 模拟器 / Termux UID | broker 13 项通过，含 PRoot fake root + socket 映射后的 C API FD 读取 |
| Android arm64 | HID 描述符、report ID、截断保护等 3 项通过 |
| Android arm64 | FAT/exFAT、UTF-8 文件名、二进制读写、持久化、只读保护、WebDAV 共 6 项通过 |
| Android arm64 SDL2 / SDL3 | HID 回放 → 独立 socket 服务 → preload → 真实 SDL 查询；均执行两轮初始化/退出 |
| Linux 和 Android PipeWire | 合成图像源 → 协商 RGB 格式 → 独立 capture client，收到至少 3 帧且内容变化 |
| QEMU x86_64 Linux guest | 虚拟 USB 存储的 usbfs → BOT → SCSI → FAT；131,123 字节读写、重命名、关机后宿主读回一致 |

手柄测试预期：X=-32768、Y=32767、按钮位图=3、方向帽=2；SDL2/3 结果一致。
QEMU guest 实际枚举路径为 `/dev/bus/usb/002/002`；测试使用独立临时磁盘镜像，未接触物理磁盘。

## 实现边界

- 未验证真实 Android USB 授权弹窗、物理 UVC 流、物理 HID report 或不同厂商 USB 控制器。
- 摄像头的 YUYV/MJPEG 协商路径已编译；实测帧传输输入为合成图像。
- USB 存储支持 BOT；未实现 UAS、NTFS、ext4。
- SDL 支持标准 HID 输入；未实现厂商私有协议、震动或传感器。
- 文件服务提供 CLI/WebDAV，不提供内核挂载；摄像头和手柄不创建真实 V4L2/evdev 节点。
- 重枚举后若 USB 路径改变，需重新授权并重启对应设备服务。
