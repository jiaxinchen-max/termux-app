# XSERVER 修复构建记录

源码：<https://github.com/jiaxinchen-max/xserver>，`termuxdc`，基线 `b08f1752300f3d594694e09f5d8f980978becc4a`（`termux-xlorie-21.1.16-r6`）。

补丁按顺序应用：

1. `patches/xserver-r6-gles-readback.patch`：GLES 不接受 BGRA 读取时明确采用 RGBA 读取并转换字节顺序，保留矩形外像素和行填充；不改变已选择的渲染器。
2. `patches/xserver-r6-explicit-render-mode.patch`：只接受明确 `system`、`zink`、`llvmpipe`；未设、未知或两个模式环境变量冲突时报错；EGL、glamor、运行中绑定 GL 上下文失败时报错终止；移除失败后转软件 framebuffer、改 EGL display、GLES 3 改 GLES 2、AHB 导入改 dma-buf 的路径。Mesa renderer 必须匹配所选 `zink`/`llvmpipe`，Zink 不能使用软件 Vulkan 设备。System 固定 `eglGetDisplay(EGL_DEFAULT_DISPLAY)`，Mesa 固定 surfaceless display；无失败后换入口。
3. `patches/xserver-r6-disable-glx.patch`：`XLORIE_GLX=0` 设置 `noGlxExtension=TRUE`，不注册 provider/vendor，真正不公布 GLX；`=1` 正常启用，其他非空/空值均报错。未设时保留 Xserver 原有扩展开关。删除先前仅返回空 fbconfig 表的分支。
4. `patches/xserver-r6-present-wakeup.patch`：将未来 Present MSC 队列的最早截止时间写入服务器 poll 超时，无待处理事件时不增加轮询；帧请求在与消费者相同的共享锁内发布，避免消费者完成旧帧时覆盖新帧请求。
5. `patches/xserver-r6-fail-fast-resources.patch`：初始 root damage/GPU 绑定失败返回失败；resize 后必要资源或 root buffer 选择失败立即终止；请求的 render socket 不同于真实固定路径时拒绝启动，禁止忽略参数。
6. `patches/xserver-r6-exact-depth-transfer.patch`：depth8/15/16 在已选 GLES renderer 内完成无损像素表示转换，替代格式读回不匹配时的 CPU 绘制；depth30 无精确读回能力则实际 pixmap 请求返回 BadAlloc，保留已有 depth/visual 声明；GPU FBO 分配失败也返回 BadAlloc，不改用 CPU pixmap。

Xlorie 在首次 libepoxy 调用前独立按模式配置 provider/driver：System 清理继承 Mesa/Vulkan 环境；Zink 固定 zink 驱动及一个可读 ICD（显式 `VK_DRIVER_FILES` / `VK_ICD_FILENAMES` 必须相同，均未设时固定系统 freedreno ICD）；LLVMpipe 固定 llvmpipe 及软件标记，不依赖调用者执行 helper。

`TERMUX_RENDER_AHB_RECEIVE` 必须明确为 `native` 或 `dmabuf`，导入失败不会改换接收 ABI。App 选择 `system → native`，`zink/llvmpipe → dmabuf`。r6 的输入 fd 重注册、启用 GLX 时的 visual 配置、render adapter 修复保留；A8 不再走原先非 renderable GL_ALPHA，改为可渲染 GL_R8 加精确读回转换。

## 构建

编译机：用户指定 SSH `builder@BUILD_HOST:7022`。构建配方来源 `/workspace/termux-packages/x11-packages/xorg-server/build.sh`；现有缓存为 r5，因此不直接使用缓存源码作为基线。

任务目录 `/home/builder/desktop-fixes-20261030/`：

- 原缓存备份：`backup/xserver/cache-src-build.tar.gz`。
- 完整差异审计：`xserver-cache-vs-r6.diff`；纳入的非生成文件：`xserver-overlaid-r6-files.txt`。
- 隔离源码/构建：`xserver-src` / `xserver-build`；原 `.termux-build/xorg-server` 未修改。
- 构建：源目录 `autoreconf -fi`，配置继承指定配方生成的 `config.status`，NDK `android-r30-api-24-v0`，`make clean && make -j4`。必须完整重编译：`glamor_format` 布局有变化，缓存关闭了依赖跟踪。
- 日志：`xserver-autoreconf.log`、`xserver-build.log`。

产物 `artifacts/Xlorie`：Android 24 / arm64 PIE，SHA256 `4bd2431c92957b8c3c6cc79434f52ecb4962192b4474fc44f739fd8aff8cbccb`。

`glamor` 静态链接进 Xlorie；不依赖外置 `libglamor.so` 或 `libglamoregl.so`。离线验证包只替换 `$PREFIX/bin/Xlorie`，保留原系统依赖，不更改已发布包版本或 URL。

## 验证

编译通过；在编译机运行以下测试通过 ASan/UBSan：

```
python3 test-xserver-readback.py /home/builder/desktop-fixes-20261030/xserver-src
```

覆盖真实 `glamor_read_pixels` 函数的 RGBA/BGRA 转换、带填充的两行、不相邻矩形、逐行路径和无需转换的读取。此项仅验证像素转换内存行为，正式功能验收由主线程通过 App 界面完成，v4 结果见下方记录。

真实模式解析及环境配置函数的主机测试 `native-tests/test-xserver-modes.py` 已在编译机通过：未设/未知/冲突模式拒绝，provider/driver 固定映射，ICD 不可读或冲突报错。此测试不验证 EGL 或显示服务。

## GLX 注册诊断

`native-tests/query-x-extension.c` 在编译机交叉编译为 `artifacts/query-x-extension`，SHA256 `fd6f82bffd0e4690f0d918f0f117d8d43b6a623a17b783405ca8eecdf109fb67`。仅连接已由 App 启动的桌面执行只读查询：

```
DISPLAY=:0 query-x-extension GLX absent   # 容器的 XLORIE_GLX=0
DISPLAY=:0 query-x-extension GLX present  # 原生启用 GLX 的会话
```

返回 0 表示与期望一致；1 表示不一致；2 表示参数错误；3 表示不能连接 DISPLAY。该探针不是桌面启动入口，不替代 App 界面验收。

`native-tests/test-xserver-glx-mode.py` 在远端测试实际 InitOutput 参数解析与 vendor 注册分支，通过 `=0` 禁用、`=1` 启用、未设保留开关、非法值拒绝。容器 XFCE 固定使用 trixie；forky XFCE 已确认不兼容，不列为待修复或待验组合。GLX 修复不表示扩大发行版支持范围。

## 延迟刷新问题

v3 在 App 启动容器 XFCE System EGL 后，Thunar 进程/任务已出现但窗口像素直到打开关闭抽屉后才刷新。主线程采集 `present check_flip enter` 258 次，`present flip enter` / unflip 为 0，排除本次是显示指向客户端 flip buffer 引起。

源码存在两处帧推进缺陷：未来 MSC 只进入链表，服务器阻塞前未设置唤醒时间；帧请求无锁写 `waitForNextFrame/drawRequested`，可被 renderer 持共享锁完成旧帧时覆盖。上述第四补丁修复这两处，不关闭合成或切换渲染模式。

`native-tests/test-xserver-present-wakeup.py` 在远端 ASan/UBSan 下通过：实际 deadline 函数覆盖已到期/未来帧、向上取整、最大值、队列顺序、保留其他更早超时、空队列不轮询；实际发布函数的确定性并发测试证明新请求不会被旧帧完成覆盖。正式无抽屉触发的 App UI 复验已通过，见下方记录。

## v4 正式 App UI 验证（主线程实测，2026-10-30）

验证对象为模拟器 `emulator-5554`，Xlorie v4 SHA256 `522af06b2b47fead876a4273237d7e6ca12b40eccc892fa69f9c16f483ebab62`。所有桌面均由正式 App 界面启动，未关闭合成或手改运行配置。

- 原生 XFCE / System EGL：打开 Thunar 及 `/usr/bin` 后自行刷新，不需要打开关闭抽屉；右键正常。只读 `XQueryExtension` 查询 GLX `present=1`。
- 容器 XFCE / System EGL，`XLORIE_GLX=0`：打开窗口自行刷新；右键 → Esc → 双指滚动后无条纹、缺块。只读 GLX 查询 `present=0`。
- 容器 Directtouch：侧栏 File System 单击导航成功；单指上滑后目录范围从 `apex/bin` 移到 `media/mnt`，明确观察到滚动，修正旧记录“未生效”。

持久截图证据：

- [native-xfce-v4-window-no-drawer.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/native-xfce-v4-window-no-drawer.png)
- [native-xfce-v4-bin-no-drawer.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/native-xfce-v4-bin-no-drawer.png)
- [container-xfce-v4-window-no-drawer.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/container-xfce-v4-window-no-drawer.png)
- [container-xfce-v4-scroll-after.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/container-xfce-v4-scroll-after.png)
- [container-xfce-v4-direct-navigation.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/container-xfce-v4-direct-navigation.png)
- [container-xfce-v4-direct-scroll.png](/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-validation-20261030/container-xfce-v4-direct-scroll.png)

forky XFCE 不属于支持范围；上述结果也不等同于 Xlorie Zink/LLVMpipe 模式已完成模拟器或小米正式 UI 验收。

v5 仅补充失败边界，成功路径不变；`native-tests/test-xserver-resources.py` 在编译机执行真实初始化/连接函数的故障注入通过：原回调失败、damage 初始化失败、root GPU 绑定失败均返回失败，错误 socket 不会连接，正确配置仍可连接。v5 完整 UI 复验待主线程记录。

## 显式模式审计

`system/zink/llvmpipe`、provider、driver、单一 ICD、native/dmabuf 导入、GLX 开关均有确定映射，错误模式/冲突 ICD/EGL 或 GPU 资源失败不会切到另一模式。API 内 RGBA/BGRA 字节转换和 A8 mask 的采样布局属于已选 renderer 内的像素处理，不改变运行模式。App 必须明确设置原生 `XLORIE_GLX=1`、容器 `=0`，避免依赖扩展默认值。成功截图仍对应 v4 构建，v5 的代码边界验证与其分开记录。

## v6 精确像素格式与请求拒绝

- depth8：GL_R8 上传，RGBA8 读取 R 通道恢复 A8；继续 GPU 绘制。
- depth15：X1555 与 GL 5551 位布局不同，显式将 X1555 转成 RGBA8 上传至 RGB5_A1；读回恢复原 15 位有效色值。
- depth16：继续 RGB565 上传，RGBA8 读回按 5/6/5 精确量化恢复。
- depth24/32：保持已验证 BGRA/RGBA 转换。
- depth30：RGBA8 会损失 10 位色精度；仅在实现提供确切原始 read format/type 时支持，否则非零 pixmap 请求返回 NULL，XCreatePixmap 通过 `dix/dispatch.c:1508` 最终返回 `BadAlloc`，不终止普通 depth24/32 会话。

r6 默认 App 声明的 pixmap depths 为 1/4/8/16/24/32；15/30 原本属于 glamor 能力探测，需要显式 `-pixdepths` 才会加入声明。本补丁未删除声明。原有 DRI3 导入仅接受 depth24/32，不能绕过 depth30 拒绝策略。

远端 `native-tests/test-xserver-depth-transfer.py` 直接执行真实上传/下载函数，在 ASan/UBSan 下穷举全部 256 个 A8、32768 个有效 X1555、65536 个 RGB565 像素，全部无损往返；同时覆盖 padding、stride、子矩形、明确 little-endian 字节及 GL pack/unpack 状态恢复。24/32 原读回测试继续通过。

`native-tests/test-xserver-gpu-allocation.py` 执行实际 pixmap 分配函数，证明 unsupported depth30 与 FBO 失败不创建 CPU pixmap，支持的 depth16 GPU 路径正常。上述为代码/内存行为测试；v6 正式 App UI 日志和画面复验由主线程记录。v6 完成后冻结 XSERVER，仅处理新的实测证据。
