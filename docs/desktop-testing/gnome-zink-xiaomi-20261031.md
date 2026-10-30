# 小米 GNOME / Zink 验证（2026-10-31）

设备：小米 11 Ultra，Android 14，Adreno 660；远程 ADB serial `d779997b`。游戏库在 App 确认 0 款。保留既有 XFCE、Plasma 和用户归档，使用新增 `debian-gnome-1` / `desktop-694185b2` 实例。

## 显式运行合同

| 参数 | 值 |
|---|---|
| 运行模式 / 桌面 / 发行版 | PRoot / GNOME / Debian trixie |
| Guest 合成器 | Mutter 48.7，X11 GL3 / GLX |
| Guest Mesa | 27.0.0-devel，`GALLIUM_DRIVER=zink` |
| Guest ICD | `/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json` |
| Host 显示 / 缓冲接收 | Xlorie Zink / `dmabuf` |
| 显示模式 | 1280×720，30 Hz |
| 会话启动 / USB | direct / off |

Guest 固定 `COGL_DRIVER=gl3`、`CLUTTER_DRIVER=gl3`、`MESA_LOADER_DRIVER_OVERRIDE=zink`；Host 固定 Termux freedreno ICD。清理继承的 Mesa、Vulkan、KWin 渲染变量。任何初始化或 renderer 验证失败均退出，不切换 llvmpipe、native AHB、GLES 或空渲染器。GTK 应用的 `GSK_RENDERER=cairo` 是显式选择；本次硬件加速结论针对 GNOME 合成器及 Zink GLX 路径。

## 实际问题与修复

1. r7 仅公布 depth24/stencil0，Cogl GL3 要求 stencil≥2。默认 Clutter 驱动列表会继续尝试其它驱动，出现 Shell 已启动但黑屏。固定唯一 GL3 后，旧 r7 明确失败。新增 RGB/RGBA depth24/stencil8 FBConfig，保留兼容配置。
2. GLXWindow 的 provider 错把底层 X Window ID 存入 GLX drawable，GetDrawableAttributes 返回 `GLXBadDrawable`。改用标准 `__glXDrawableInit(...glxDrawId...)` / release，修复查询和双别名销毁。
3. RandR 模式没有 dotClock / totals，Mutter 报 0 Hz。统一按所选 Hz 生成精确时序，并公布同尺寸刷新率变化；非法值或协议时钟溢出直接失败。
4. GLX provider 分配失败原本返回 NULL，可落入 swrast provider。现在分配失败或必要初始化状态不完整均 FatalError。
5. GNOME/Zink 重装需重新检查依赖；hold 的 Mesa 包仍是完整安装状态。guest 检查接受精确 `install/hold ok installed`，拒绝半安装、卸载状态和命令查询失败。

PRoot GNOME 只安装必要宿主显示组件，不下载 Native GNOME 的 Bionic 桌面包。Guest GNOME 和 Vulkan 依赖使用 Debian 二进制；KGSL Mesa 使用固定上游二进制归档，验证 SHA、大小、aarch64 ELF、ICD、发行版、路径及完整写回内容。

## UI 与驱动证据

全部安装、启动、停止及桌面交互通过 App 界面；CLI 仅部署产物、准备校验缓存和读取诊断。

- `glxinfo -B`：direct rendering Yes，`zink Vulkan 1.3(Turnip Adreno (TM) 660 (MESA_TURNIP))`，Accelerated yes，OpenGL 4.6。
- Shell 的实际进程环境为 GL3 / Zink / 固定 freedreno ICD。
- Xlorie 自身报告 Zink / Turnip Adreno 660，记录 1280×720 DRI3 导入和 Present flip。
- GNOME 搜索界面打开 Files；浏览 `/usr`，进入 `/usr/bin`；单指滚动后目录从 `X11/apt/arch` 移至 `base64/bashbug/cat/chown`。
- App 切横屏再切竖屏，窗口内容和滚动位置保留，无黑屏或缺块；Mutter DisplayConfig 仍报告 `1280x720@30.000`。
- App Stop desktop 后卡片回到就绪，状态 `stopped`；本轮 Shell、Xlorie、Mutter frames、GVfs 和 D-Bus 均已退出。

完整导航、滚动和横竖屏操作先由候选二进制 `84f27c4bf9455eeb8700104bef5c56edcdffce06f9322fc56dae5a105dab9fc6` 通过；之后补 provider 初始化失败边界。最终 APK 再次从 App 执行安装，已有 hold Mesa 依赖验证成功，状态 `exit:0`；正式安装 `xorg-server 21.1.16-8`，`dpkg --verify` 无差异，binary SHA 匹配。随后从 App 启动最终 GNOME，Files 再次打开并浏览 `/usr/bin`，真实 Zink/Turnip renderer、1280×720 DRI3/Present 和 30 Hz 均再次核对。

## 固定产物与验证

- 上游 Mesa：`lfdevs/mesa-for-android-container`，tag `mesa-27.0.0-devel-20261025`，trixie arm64 归档 12,242,251 字节，SHA256 `b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687`。
- 最终 Xlorie：SHA256 `a709d798dd347c72166a0572ece320f5d2c47d2a29dedb2f5f871297208be0a8`。
- 最终 APK：SHA256 `5747ecbbd318dc88d177c336b4f9f96586630e143e41f0843165cd09855df524`，6 个关键运行资产与源码逐字节一致。
- `xorg-server_21.1.16-8_aarch64.deb`：SHA256 `0a243786b8ac14e6557590b5c6c4b14f7d51358084c5075383a06aa57e2128a2`。两次确定性打包字节相同，216 项 payload 中 215 项不变，仅替换 Xlorie 和必要版本/大小元数据。
- 指定 Linux 编译机执行 Android 构建，625 Java tests 通过；最终安装合同 36、launcher 45、managed lifecycle 12、Mesa Linux 安全 18 项通过，包含真实上游 90 项 payload 重复安装。
- Xserver 在指定 NDK 编译机编译。真实 C / ASan / UBSan 覆盖 RandR 初始化、同尺寸改 Hz、GLX stencil 配置、GLXWindow 查询/双顺序销毁、8 个分配失败点和 14 个不完整 provider 状态。

证据目录：`/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/gnome-zink/`。

## 发布边界

Xserver 源码基线为 `jiaxinchen-max/xserver` 的 `termuxdc` / `d9d2bd1d32a4965a07c4b1c5ed27cef84167fcb3`；修复本地提交 `84dad5a1d4952eead5378023f02edd2d079bf1f0`，分支 `codex/gnome-zink-glx-randr`。App 同步固定 r8 release、deb SHA 和 Xlorie binary SHA。新 release 尚未公开，安装验证使用同 SHA 的校验缓存，不声称新设备网络下载链路已经可用。本次未验证物理 USB，也未测量游戏性能。
