# XFCE 图形相机复现

实际环境：Android 15 arm64，Termux USB bridge 0.3.0-1、PipeWire 1.4.9、GStreamer 1.28.8、
GTK 3.24.52、XFCE 4.20。使用上游 `gst-camera2` GTK 相机示例，源码不修改。
这不是 Cheese/guvcview 的兼容性测试，也不是物理 UVC 测试。

在 Termux 准备 clang、pkg-config、curl、GTK3、GStreamer base/good/bad 插件、PipeWire、
WirePlumber、XFCE、Termux:X11。本轮依赖已安装；只在独立目录编译以下两个测试组件。

```sh
mkdir -p "$HOME/tusb-camera-gui"
cd "$HOME/tusb-camera-gui"
camera_dir=$PWD
curl -fL https://codeload.github.com/PipeWire/pipewire/tar.gz/refs/tags/1.4.9 -o pipewire.tar.gz
for suffix in c h ui; do
    curl -fL "https://raw.githubusercontent.com/GStreamer/gstreamer/1.28.8/subprojects/gst-plugins-bad/tests/examples/camerabin2/gst-camera2.$suffix" -o "gst-camera2.$suffix"
done
cat > inputs.sha256 <<'EOF'
8066a7b220069e4c6e3b02bd2b6ea303bba66df255023c07c99323449ba8fe3c  pipewire.tar.gz
126695111e016558a1b09b1f6a343a057abea33389b4150bde5bab98c5d0aeea  gst-camera2.c
59339e1e727075607ccc226bdf6907fab594b6c1202124ef9c40ceb8bdbc4a6e  gst-camera2.h
54b8e22f05d0dc1164b7ed661c7aa03d7041ffb7a4315e17481e06adc20b454e  gst-camera2.ui
EOF
sha256sum -c inputs.sha256
tar xzf pipewire.tar.gz
mkdir -p plugin
cat > pipewire-1.4.9/src/config.h <<'EOF'
#define PACKAGE "pipewire"
#define PACKAGE_VERSION "1.4.9"
#define HAVE_GSTREAMER_DEVICE_PROVIDER 1
EOF
clang -O2 -fPIC -shared -Ipipewire-1.4.9/src pipewire-1.4.9/src/gst/*.c \
    $(pkg-config --cflags --libs libpipewire-0.3 gstreamer-video-1.0 gstreamer-audio-1.0 gstreamer-allocators-1.0 gstreamer-net-1.0) \
    -lm -o plugin/libgstpipewire.so
clang -O2 -Wno-deprecated-declarations -DCAMERA_APPS_UIDIR=\""$camera_dir"\" gst-camera2.c \
    $(pkg-config --cflags --libs gtk+-3.0 gdk-x11-3.0 gstreamer-video-1.0 gstreamer-pbutils-1.0 gmodule-export-2.0) \
    -o gst-camera2
```

在已经启动的 XFCE 会话终端中运行下列命令。相机、PipeWire 和 WirePlumber 必须共享
同一 `XDG_RUNTIME_DIR`、`PIPEWIRE_REMOTE` 和会话 D-Bus。测试时采用隔离的目录和 D-Bus，
启动了 `termux-x11 :2 -ac`、`xfce4-session`、`pipewire`、`wireplumber`；没有复用其他桌面服务。

```sh
cd "$HOME/tusb-camera-gui"
export GST_PLUGIN_PATH="$PWD/plugin${GST_PLUGIN_PATH:+:$GST_PLUGIN_PATH}"
gst-inspect-1.0 pipewiresrc
tusb-camera --test-pattern --width 640 --height 480 --fps 30 --name tusb.camera.gui &
camera_pid=$!
gst-device-monitor-1.0 Video/Source
GST_PLUGIN_FEATURE_RANK=ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0 ./gst-camera2
kill "$camera_pid"
wait "$camera_pid"
```

检查相机窗口显示持续变化的渐变图案；点击 Capture 两次，生成 `cap_0`、`cap_1`（内容为 JPEG）。
用 `pw-link -l` 确认连接来自 `tusb.camera.gui`，不能仅凭窗口有画面判断来源。
应用依靠 `autovideosrc` 和 WirePlumber 自动连接，不执行手工 `pw-link`。
两张照片应为 640×480，内容不同；图案的理想 RGB 为 `((x+sequence)%256,y%256,sequence)`，
JPEG 采样需允许压缩误差。关闭窗口后应用应退出且连接消失。

本轮用 `xdotool` 点击窗口 Capture/关闭按钮，并用 `ffmpeg -f x11grab -i :2` 保存实际窗口截图。
所有证据及限制见 `../VALIDATION.md` 的“XFCE 图形相机验证”。正式 deb 未因本轮验证重编。

## Camera2 输入的 XFCE 实测

另一个场景用模拟器内置 Camera2 HAL 取代 `--test-pattern`。复用上面的未修改 GTK 相机、
GStreamer PipeWire **接收**插件和已授权的同 UID Termux:API，不新增 APK。

将当前仓库的 `tests/android_ndk_camera.c`、`tests/camera_rgb_source.c`、
`tests/android_camera2_xfce.py` 复制到 Termux 的同一个源文件目录。在真实 Termux 会话内运行：

```sh
# 以下两个目录按实际文件位置设置；app_dir 内需有 gst-camera2 和 plugin/libgstpipewire.so。
camera_sources="$HOME/camera2-test-sources"
app_dir="$HOME/tusb-camera-gui"
camera_work="$HOME/tusb-camera2-xfce"
mkdir -p "$camera_work"
clang -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror \
    "$camera_sources/android_ndk_camera.c" -lcamera2ndk -lmediandk -ldl -o "$camera_work/camera2-rgb"
clang -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror \
    "$camera_sources/camera_rgb_source.c" $(pkg-config --cflags --libs libpipewire-0.3) -o "$camera_work/rgb-source"
# :2 需空闲。使用新的输出子目录，避免旧 cap_0/cap_1 影响验证。
dbus-run-session -- python "$camera_sources/android_camera2_xfce.py" \
    --work "$camera_work/back" --app-dir "$app_dir" --camera-id 10 --label back
dbus-run-session -- python "$camera_sources/android_camera2_xfce.py" \
    --work "$camera_work/front" --app-dir "$app_dir" --camera-id 1 --label front
```

脚本启动隔离的 X11/XFCE、PipeWire 和 WirePlumber，运行如下实际链路：

```text
Camera2/AImageReader（系统库）→ RGB Unix pipe → 原生 PipeWire Video/Source
                            → GStreamer pipewiresrc → gst-camera2 GTK 窗口
```

相机 ID 需根据目标设备枚举结果调整。脚本让 Camera2 子进程单独使用 `/system/lib64`，
避免与桌面进程的 Termux 媒体包装库混用。Binder 平台接口依赖见 `../VALIDATION.md`。
原生 RGB 发布端采用项目摄像头适配层相同的 PipeWire 协商/发布方式；它是验证程序，尚未进入正式包。
原始管道仅传 RGB 像素，PipeWire 端按名义 30 FPS 重新生成时间戳；未验证原始相机时间戳透传。

脚本验证设备发现、自动连接、点击两次 Capture、关闭窗口、停止相机和发布端、清理节点，
保留 `preview-1.png`、`preview-2.png`、`cap_0`/`cap_1` JPEG、连接图、各进程日志及 `result.json`。
不要把前摄 HAL 的模拟图像或后摄 HAL 的虚拟房间当作物理手机传感器实拍。

## 0.4.0 安装包验证

安装 0.4.0 deb 后不再编译原型采集/发布端；使用 SDK 的同一 GUI 脚本并传入 `--packaged`，
它会调用已安装 `tusb-camera2` 并从包内私有路径加载 GStreamer 插件。
`--app-dir` 只需包含之前编译的未修改上游 `gst-camera2` 和其 UI 文件。

```sh
sdk="$PREFIX/share/termux-usb-bridge/sdk"
dbus-run-session -- python "$sdk/tests/android_camera2_xfce.py" --packaged \
    --work "$HOME/camera2-package-back" --app-dir "$HOME/tusb-camera-gui" --camera-id 10 --label back
dbus-run-session -- python "$sdk/tests/android_camera2_xfce.py" --packaged \
    --work "$HOME/camera2-package-front" --app-dir "$HOME/tusb-camera-gui" --camera-id 1 --label front
```

容器消费验证使用已有 Linux rootfs（需 proot、发行版的 PipeWire/GStreamer 命令和插件），
从正常 Termux App 会话启动：

```sh
python "$sdk/tests/android_camera2_container.py" --rootfs "$ROOTFS" \
    --work "$HOME/camera2-container-package-check" --camera 10
```

脚本分别采集 30 帧 PNG，中间等待 25 秒；容器侧加载自身发行版的库。
Camera2 系统库、Binder 和 Android 相机权限处理均留在宿主。
