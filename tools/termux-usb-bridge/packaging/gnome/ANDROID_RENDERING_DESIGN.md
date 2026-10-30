# Mutter Android GPU 后端

2026-10-30：首阶段已实现并在 Pixel Fold / Android 15 模拟器验证。
首阶段实验包为 Mutter 48.1-17；客户端 AHB 扩展包为 Mutter 48.1-23，GNOME Shell 48.7。KWin 参考基线为
`ef19d13142c95581b711313895b0c550f891b89d`（Plasma 6.6.7 / r27）。

## 实现边界

原生 `gnome-shell` 的 Shell JS、GJS、St、Mutter、Cogl、Clutter 在同一 Bionic
进程；新增 Android backend，复用现有 termux-render 显示/输入协议。
无需修改或替换配套 termux-app APK。普通官方 APK 不具备该配套显示视图。

```text
native GNOME Shell / Mutter
  Cogl GLES → GPU 色序/纵轴转换 → Android EGLImage / AHB → 配套 APK 的 LorieView
          ↑ Wayland socket
  native AHB GLES 客户端：共享 AHB / EGLImage，无像素上传
  native GTK 或 PRoot/glibc GTK：现有 wl_shm，仍需上传
```

实现文件在 `mutter/android/`，集成补丁为 `0008-android-backend.patch`。
`0009-gles3-unmap-buffer.patch` 修复 Cogl 在 GLES 3 驱动没有 OES_mapbuffer 时，
解析了 MapBufferRange 却未解析核心 UnmapBuffer 的崩溃。

- 独立 `android_backend`；不启用 Linux native_backend、DRM/KMS、logind 或 udev。
- system 模式接收完整 AHB，以 EGL_NATIVE_BUFFER_ANDROID 导入共享输出。
- 48.1-23 使用 GPU 中间纹理及最终一次色序/纵轴转换：现有 LorieView 的 RGBX 根纹理
  消费端固定应用 `.bgra`，不能直接输出正常 RGB。转换全在 GPU，无 CPU 映射或回读；
  这包含一次 GPU 全屏绘制，不宣称全链路无复制或 direct scanout。
- 私有 EGL forwarder 仅注入 compositor；初始化后从子进程环境移除。
- 私有 libvndksupport 在平台 namespace 的共享子 namespace 加载驱动。
  解决 Android 15 模拟器 sphal 转回 Termux 默认 namespace 后误加载 binder wrapper
  的版本依赖问题；实测 EGL/AHB/驱动仅一份系统 libbinder。
- GL renderer 必须符合显式选择的 system/zink，拒绝 llvmpipe、lavapipe、softpipe、
  SwiftShader；不静默降为软件输出。
- 键鼠、滚轮、触摸进入 Clutter seat；提供逻辑设备与物理来源及 capability。
  Android Unicode 文本在当前 XKB 布局可表达时转为按键，支持 ASCII 输入。
- 输出每帧刷新 Cogl journal 并等待 GPU 完成后唤醒消费者，无正常输出整屏 glReadPixels。
  Clutter 的 offscreen 完成时间是 producer 完成，不是 Android vblank 时间。
- 固定逻辑分辨率；Surface 旋转/缩放后将输入坐标映射回该尺寸。

## 构建与启动

原生生产库通过指定编译机的 Termux 工具链生成：

```sh
cd /workspace/termux-packages
./build-package.sh -a aarch64 -f -s -j 8 mutter
```

将本目录 `mutter/` 配方放入 `x11-packages/mutter/`。
依赖现有 termux-render、libandroid-stub，以及配套 system/zink 分派版 libepoxy。
本轮使用已发布 `libepoxy_1.5.10-6_aarch64.deb`，SHA256：
`c78f1674337e4a90a9c933d6301d058bf2be85bc926baae6202320e803582d83`。

先打开配套 APK 的实际显示视图，再执行：

```sh
python launch.py --backend android --gl-mode system \
  --work "$HOME/gnome-android" --size 1280x800 --minimal-services
```

客户端加载工作目录的 `host-session.env`，使用其 Wayland socket。
`--pipewire`、`--bridge` 保留原有可选服务；USB broker 必须可连接。
本轮后段一次 USB broker Connection refused 导致可选桥进程退出，launcher 按设计
终止会话；最终合成器验证使用 `--pipewire`，未把该错误计为 GPU 故障。

`--backend x11` 保留原 native/PRoot 全会话启动方式。
Android backend 的 `--rootfs` 被明确拒绝：glibc Shell 不能与 Bionic libmutter 混载。
PRoot 应用通过绑定工作目录/runtime、设置 Wayland 环境连接宿主 Shell。

## 实测

仅操作 `emulator-5556`。保留原 Termux APK，单独安装随后卸载测试 instrumentation。

| 项目 | 结果 |
|---|---|
| 官方交叉编译/符号检查 | 11 ELF 校验通过 |
| 实际 GL 上下文 | OpenGL ES 3.0，Google (Apple)，Android Emulator OpenGL ES Translator (Apple M1 Pro) |
| 输出 | native AHB，1280×800，真实桌面截图和消费者帧计数 |
| native Nautilus | Wayland 窗口显示、文件内容可见 |
| Debian PRoot Nautilus | 与 native 窗口同时显示于同一宿主 compositor |
| 输入 | producer 原生绝对鼠标点击切换 overview；Android 文本、退格；Super；触摸事件到达 |
| 旋转 | Surface 横竖切换后 Shell/两个客户端存活并继续显示；输出逻辑尺寸固定 |
| 帧调度 | 修正 PENDING_PRESENTED 后无 next_presentation 断言 |

首阶段客户端使用 Cairo/wl_shm：只验证 GPU 合成。相关截图、日志、deb 见
`../../dist/gnome-android-20261030/`。

## 客户端 AHB 与标准 DMA-BUF（48.1-23）

Pixel Fold / Android 15 系统 EGL 实测缺少 `EGL_EXT_image_dma_buf_import` 和
`EGL_EXT_image_dma_buf_import_modifiers`；分配的 AHB 句柄 fd 指向
`/dev/goldfish_pipe_dprctd`，`DMA_BUF_IOCTL_SYNC` 返回 ENOTTY (25)。
不能将这个 fd 当成 DMA-BUF 通告给客户端。无可访问的 DRM/KGSL 设备；仅改 Mutter
不能补齐模拟器驱动的标准 DMA-BUF 分配/导出/导入能力。

新增 `0010-android-client-buffers.patch` 与 `android/meta-wayland-android-buffer.[ch]`：

- 仅 system Android 后端启用私有 `termux_ahardware_buffer_manager_v1` v2；协议与
  仓库 KWin 相同，传完整 AHB native_handle，不伪装 `linux-dmabuf`。
- 新增独立 MetaWaylandBuffer 类型、native EGLImage/Cogl 导入、opaque/red-blue
  标志及生命周期。无需将 AHB 映射到 CPU，也不调用 texture 像素上传。
- v1/v2 同步使用客户端 glFinish，服务端在 release 前刷新 Cogl journal 并 glFinish。
  不声称实现了 native fence 或 Linux syncobj 显式同步。
- 校验 AF_UNIX/SOCK_STREAM、flags、尺寸、layers、format、usage/protected；接收超时
  100 ms，未提交完整 handle 的客户端不能无限阻塞主循环。
- v2 `probe_buffer` 在真实 Cogl 采样路径读取一个诊断像素协商红蓝顺序；正常路径无回读。
- libexec `mutter-android-ahb-client` 使用与 compositor 相同的私有 EGL namespace，
  GLES 函数由 eglGetProcAddress 解析，避免默认 namespace 混载系统 binder。
  AHB API 在运行时解析，保持包的 API 24 编译基线，运行需要 Android >=26。

实测范围：原生/Bionic 测试客户端的 RGBA8888、BGRA8888 双缓冲，每项 120 帧；
实际 renderer 为 Apple M1 Pro 上的 Android Emulator OpenGL ES Translator。
测试与正常 Nautilus/wl_shm 客户端并存，未把它们视为同一种 GPU 客户端。
测试代码及步骤见 `../../tests/android_mutter_ahb/`，本轮产物/日志/截图见
`../../dist/gnome-ahb-20261030/`。

这打通了支持该私有协议的客户端共享 GPU 缓冲区路径。普通 GTK/Mesa/glibc 应用不会
自动改用 AHB；仍需其 toolkit/EGL WSI 适配。现有 Qt/Xwayland 的 KWin AHB 客户端
适配协议兼容，但本轮未运行这些适配器。物理 Adreno/Zink 的标准 DMA-BUF 仍需真机
分别验证分配、导出、导入、格式/modifier 与同步；不以模拟器 AHB 结果替代。

## 限制

- Zink/dma-buf 分支已实现，尚无物理 Adreno/KGSL/Turnip 实测；不能外推为真机通过。
- 没有运行时 AHB 重新分配；改变桌面逻辑分辨率需重启会话。
- 未实现指针约束、压力屏障、虚拟输入设备、任意 Unicode/IME 提交或剪贴板桥。
  Shell 对不支持的屏障会打印警告；当前能力标志不通告屏障支持。
- 缺少 cursor theme 时软件游标不可见，需安装匹配的光标主题。
- 本轮未验证 Wayland camera GUI 或 USB 热插拔/写入回归；此前 X11/llvmpipe 下的
  FAT32/exFAT、前后 Camera2 GUI 验证不能当作本轮 GPU/Wayland 的验证。
- GDM、logind、锁屏、Polkit/system session 服务仍不提供。私有 session 中的相关
  GNOME 服务缺失警告保留，不伪造这些系统服务。
