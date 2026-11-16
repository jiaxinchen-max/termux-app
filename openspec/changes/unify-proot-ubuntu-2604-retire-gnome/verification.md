# 验证记录

## 当前交付状态

用户已确认实施及原生 Android 后端升级，随后明确要求“先推送代码”。本次仅保存未完成进度，不构建或发布 APK，不更新发布资产。整体仍未通过验收。

## 已完成及验证证据

- 独立 GNOME PRoot 从创建目录和渲染矩阵移除，安装、启动及直接 Python 调用拒绝；Native GNOME 和删除入口保留。未迁移或删除用户容器。
- 四类保留 PRoot 的目录、底包及元数据改为 Ubuntu 26.04 LTS / ubuntu:resolute；全新安装回归尚未完成。
- 原编译机 /workspace/termux-packages/build-package.sh 已成功构建 Mutter 50.1、GNOME Shell 50.1、GJS 1.88.0、SpiderMonkey 140.17.0-1，并通过官方 ELF 符号检查。
- Mtk/Cogl/Clutter/Meta 18、GjsPrivate 1.0、Gvc 1.0、Shew 0、St/Shell 18 的真实 Android GType dump 均已生成，来源及哈希记录在各 gir/*.provenance.json；旧 ABI dump 仅保留于 gir-48。
- Native Shell 50 实机日志显示 System EGL/AHardwareBuffer 初始化、GPU 首帧提交及 GNOME Shell started。模拟器 GPU renderer 为 Android Emulator OpenGL ES Translator (Apple M1 Pro)。这不代表完整桌面、主题、应用和输入回归通过。
- 普通用户 termux 的 Ubuntu Resolute PRoot 视图内，Bionic Shell 50 亦完成 GPU 首帧并记录 GNOME Shell started；此前总线检查失败；继续修复后已通过普通用户总线检查、全部扩展及实际 DING 窗口就绪。仍有下文记录的重启崩溃及完整回归待处理。
- 修正 PRoot 平台目录检测、HOME 下渲染 socket 绑定，并将可读取但不可遍历的 /linkerconfig/ld.config.txt 复制为私有快照后绑定。真实 PRoot EGL probe 在该配置下通过；未使用 PipeWire 显示桥或 CPU 回读。
- Resolute glibc GIO 在原编译机使用实际 Ubuntu arm64 sysroot 构建，归档 SHA256 为 a843a28f1821c1e598d8c653ea0db7e6540d188d42296cc5a305bd4a838982ea；仅在隔离测试容器安装，ldd/gio-querymodules/gio mount -l 可执行，未测试真实 USB，未发布。
- GIO 安装器现校验两份实际 payload 的 SHA256，错误哈希在写入前拒绝；新增相应回归。

## 自动检查

- 此前 JUnit 目录/渲染/启动配置测试 20 项及 DesktopEnvironment 测试 17 项通过。
- Native Ubuntu 初始化测试 8 项、废弃 PRoot GNOME 测试 3 项、GIO 安装测试 11 项、Ubuntu GNOME profile 测试 8 项通过。
- 完整 test-desktop-ubuntu-gnome.py 曾失败；其中安装器测试仍有旧 X11 和组件预期，需随未完成的安装器一起适配。不得把 profile 单独通过视作完整套件通过。
- Native Ubuntu 初始化测试仍覆盖旧 48 主题实现，不证明 50 主题兼容。
- 最终保存前的检查结果追加于下文。仓库缺少规定 Harness 指令、strict gate 脚本与 OpenSpec CLI，未运行严格门禁。

## 实测证据位置

- 设备 $HOME/.cache/gnome50-probe/native-test.log：Native GPU 首帧。
- 设备 $HOME/.cache/gnome50-probe/proot-work/session.log：PRoot GPU 首帧及 Shell started。
- 设备 $HOME/.cache/gnome50-probe/proot-launcher.log：D-Bus 就绪失败。
- 设备 $HOME/.cache/gnome50-probe/proot-egl-config.log：EGL probe 通过。
- 编译机 /workspace/artifacts/gnome50-native/：原生构建日志及产物。
- 编译机 /workspace/artifacts/resolute-gio/：GIO sysroot、软件包来源、构建清单及产物。

上述运行日志属于测试环境证据，未随本次源码提交打包；GIR provenance 已纳入源码。

## 未完成与风险

安装器仍引用 GNOME 48 清单、Xlorie 和 Native Ubuntu 48 外观资产，与新启动器尚不配套。需完成新组件发布、精确哈希和 Native Ubuntu 50 主题/扩展包后统一更新；当前组合不可发布。仍已修复 PRoot Shell D-Bus 就绪，仍需验证 Ubuntu 扩展/DING、普通用户图形应用、输入和会话清理，完成 Mesa/Zink/Hyprland、四类桌面全新安装及 Native 回归。产品文档一致性保持 Pending，未归档。

## 影响、回退及授权

原生组件配方及 App 安装/启动链路改动会影响 GNOME ABI、容器底包与显示输出；本次是明确标注的 WIP 保存。回退可撤销本次提交恢复源码；设备测试使用独立容器和私有 runtime，不迁移用户数据。用户“升级现有组件进行支持，pipewire不用考虑”授权实施，“先推送代码”授权本次暂存、提交及推送；不含本轮新资产发布或 APK 交付。

## 本次进度保存前复核

重新运行 Native Ubuntu 初始化 8 项、废弃 PRoot GNOME 3 项、GIO 安装 11 项、UbuntuProfileTests 8 项，共 30 项通过。改动 Python AST、shell 语法、源码凭据模式检查及 git diff --check 通过。未重新执行完整 Ubuntu GNOME 安装器套件，其已知失败仍保留在 review findings；未执行缺失的 strict gate。

暂存后额外检查：新增的 unified diff 配方文件包含补丁上下文行及上游空白，默认 git diff --cached --check 会报告空白提示；保留原补丁语义，未机械裁剪。所有配方补丁通过 git apply --numstat 语法解析，排除 .patch 后的暂存差异检查通过。

## 继续修复：会话身份、模式与启动竞态

- 设备 App 真实 UID/GID 为 10213，隔离测试容器 termux 为 10214。独立真实 D-Bus 探针使用 EXTERNAL 认证：宿主 ListNames 失败，同一 PRoot 内调用成功；保留认证策略，不修改容器用户。
- 就绪检查已改为通过同一 PRoot/普通用户执行，并读取本会话的 session.env。实测已越过 Shell 总线检查，进入 Ubuntu 扩展检查。
- GNOME 50 上游 extensionSystem 只匹配当前及直接父模式；sessionMode 也仅合并一层父模式。旧 termux-ubuntu -> ubuntu -> user 结构导致 Dock 保持 INITIALIZED/state=6，且未继承全部 user 能力。现从实际 Ubuntu 模式复制外观及资源配置，保留直接 parentMode=user，仅替换必需扩展清单以排除 Snap 服务；不能以放宽状态=1检查掩盖此问题。
- 另一次冷启动暴露 shell.pid 存在但内容尚未写完的竞态，启动器报 invalid process ID。session.env 与 shell.pid 均改为先写临时文件后原子替换。
- D-Bus 激活服务会脱离父进程组；PRoot 支持 --kill-on-exit，现用于每个受控 PRoot 子命令。仍需完整会话退出实测，不能以参数存在宣称已无残留。
- 新增真实 dbus-daemon 回归，验证错误宿主地址不会污染会话探针、会话总线不可用时不会误用健康宿主总线、探针参数不被解释为 shell 命令：3 项通过。Ubuntu profile 含模式外观及直接父模式回归：9 项通过。Native Ubuntu 初始化：8 项通过。
- 完整 Ubuntu GNOME 安装测试仍因旧 X11 参数预期而失败；本轮未声称安装链已配套。
- 设备 13:19:05 记录 system_server 的 AnrAuxiliaryTaskExecutor SIGABRT，随后 Activity 服务消失。系统日志记录 SuspendThreadByThreadId / SuspendAll timeout；尚未确立与本轮功能的因果关系。此期间的截图、冷启动及独立清理探针超时均不计为通过，需要系统恢复后复测。

## 继续验证：真实应用、图像与窗口生命周期（2026-11-16）

- 同一普通用户 PRoot 会话内的总线探针、四个扩展 state=1、DING 实际窗口通知均已通过，生产就绪文件只在窗口出现后发布。DING 仅拥有总线名称不算就绪。新增真实私有 D-Bus 与显式假合成器的生命周期回归共 11 项通过，包含 bus-only 阻塞与窗口通知后通过；假合成器不作为 GPU 实测证据。
- Terminal 实际显示，普通用户为 termux/10214，Android 键盘输入 gnome50 被真实终端读取并写入文件；Nautilus 实际显示并列出 Home。鼠标打开文件管理器及快速设置交互尚待单独验证。
- GNOME Shell 50.1-2 已在原 build-package.sh 构建并通过 9 个 ELF 检查。修复禁用 NetworkManager/Bluetooth 后快速设置初始化同步访问尚未创建的 BrightnessManager，并对 Android backend 使用上游 LoginManagerDummy。设备日志不再出现旧 quick-settings-null 或 login1 错误，不能替代 GUI 交互回归。DEB SHA256：37c447a7e282e42c891d2f343d9d3cc506811e487bd3cca2defae3fc5882eb19。
- Resolute Glycin 2.1.1 / Rust glycin 3.1.0 在 Android 读取 overflowuid/overflowgid 被拒绝的诊断被纳入已有 bubblewrap 不可用检测。原 Linux 编译机、真实 Resolute glibc 交叉工具链按入库配方构建通过；没有 Bionic 依赖。默认 AUTO 成功解码 16×16/256×256 PNG，无效 PNG 返回 Invalid PNG signature。保留 running without sandbox 警告和隔离边界说明。
- Glycin 精确库 SHA256 b2ea4a4a87d0fbc9746fe686e84d5848c682498418ded9585eb12f93f073a1be；固定双文件归档 termux-gnome-glycin-resolute-aarch64-2.1.1-r1.tar.gz SHA256 cb72b99666baacb5cd8b4fd62960f3a54568c49c04a05bd8b5f9280ba8493eb9。生产助手已在隔离容器验证下载安装、来源及字节校验，原发行版库未修改；启动时仅绑定当前桌面。5 项助手测试通过，GIO 通用解析器 11 项通过，Ubuntu profile 9 项通过。外部资产尚未发布，完整安装链仍不配套。
- DING 首次受控重启曾耗时 18.94 秒才创建窗口，原 3 秒看门狗会误杀。基于钉住源码将其改为有界 30 秒，窗口齐全后立即取消，不增加固定启动睡眠；后续压力场景也观察到一次超过 30 秒而被看门狗重新拉起，不能保证慢环境每次单次拉起均成功。
- 已在未修复 Mutter 上多次复现 139 段错误。诊断原链构建通过 12 ELF 检查；在第 6 次窗口出现后立即终止所属 DING 的压力场景，日志明确记录 W8 窗口 unmanage、surface-dispose，随后同一窗口再次 queue-run，unmanaging=1，读取 surface role 时 fault=0xaaaaaaaaaaaaaaaa。全程同一 GLib 主线程，证据不支持跨线程 GPU 故障。
- 根因是显示更新在回调后清除 CALC_SHOWING：meta_window_flush_calc_showing() 和 update_window_visibilities() 两处清理都可能覆盖回调重新入队。初版仅移除前者，函数替身测试通过，但无探针设备第 2 次 DING 重启仍崩溃（50.1-2，设备 14:38:33，139/0xaaaaaaaaaaaaaaaa），因此初版被否决。完整修复将消费标记移到显示回调前，移除两处事后清理，不靠悬空指针检查掩盖问题。扩大后的回归编译真实窗口函数、显示可见性更新和同步刷新，原版 50.1 与 caller-only 初版均失败，完整修复通过普通信号及嵌套 shown 刷新两类回调，并确认销毁清理无残留。
- 诊断源码和 signal probe 仅在 artifacts 与私有测试 runtime 中，不进入配方交付/APK。不含诊断日志的 Mutter 50.1-3 已在原链构建、通过 12 ELF 检查，DEB SHA256 0014ff01303536f0a2ba039a2837b28bf6ee38f952fb6436cbebca9911644af4；设备关闭诊断探针并 force-stop 后，连续 8 次在窗口通知后立即终止所属 DING，Shell 22987 始终存活，窗口恢复分别为 1.942/1.714/1.411/2.334/1.613/1.513/1.417/1.411 秒，压力场景通过。

证据：本地 artifacts/gnome50/crash-ding-shell-r2-20261116.log、lifetime-live.log；设备隔离 proot-work-v2 日志；原编译机 gnome50-native/mutter-lifetime.log、gnome-shell-r2.log、resolute-glycin/build-verified.log。设备冷启动会覆盖会话日志，已保存崩溃现场。完整会话清理已通过下述所属进程检查；Native 回归、四桌面全新安装和完整安装器测试仍未完成；整体 NotReadyForRelease，产品一致性 Pending。

### 无探针压力测试与退出清理

Mutter 50.1-3 + Shell 50.1-2 的实际 PRoot 冷启动已使用生产 Glycin 安装库；session.sh 不含 crash-probe，组件源码亦不含 LIFETIME 诊断。上述 8 次压力测试后，记录该 Shell 的真实 PRoot tracer 及 9 个受其跟踪的会话进程，再仅终止启动器。15 秒内全部 10 个所属 PID 消失，ready 与 shell.pid 撤销，另外启动的无关宿主 sleep 仍存活，随后只清理该探针自己的 sleep。证据 PID：22983/22985/22986/22987/22996/23116/23128/23232/23530/23549。未遍历终止用户其他桌面。

再次运行完整 test-desktop-ubuntu-gnome.py：16 项测试、8 个已知安装器失败；现有 fixture 仍传旧 host/container renderer，入口明确以 64 拒绝。已保留日志 artifacts/gnome50/ubuntu-gnome-full-tests.log，没有降低校验制造全通过。安装器及 Native Ubuntu 外观更新仍为发布阻塞。

## 当前继续结果：50.1-4 与保存／启动分离（2026-11-16）

此节取代前文安装器仍为 48／启动前重新做静态验证的旧状态，不删除历史失败记录。

- Mutter 50.1-4 新增引用持有的 idle/GTask keymap 更新，修复同步信号重入 Shell 输入源初始化；原链构建和 12 项 ELF 检查通过。DEB SHA256 `294a5de617a3da94aa8c81a486bdcd021eb0d0524e278cea8b10fd0ebd69ac37`。实际源代码 keymap 回归通过，同步旧实现负对照失败。完整队列补丁重新应用到原版 50.1 源码后，真实源函数回归通过；未打补丁的源码仍按预期失败。
- r4 恢复原 Ubuntu Tiling Assistant，禁用诊断 tracer 后 force-stop 冷启动，8 次 DING 重启恢复分别为 7.897、7.123、7.499、7.002、13.335、13.261、10.164、10.735 秒；Shell 未崩溃，之后 9 个所属进程清理通过，无关宿主 sleep 保留。快速设置及图标已通过隔离诊断方式截图确认，实际鼠标点击仍未单独通过。
- 安装器配套核心更新为 Shell 50.1-2／Mutter 50.1-4／GJS 1.88／SpiderMonkey 140；补齐真实 forwarder、vndksupport 和默认插件哈希，Native／PRoot host 显式依赖 librsvg。GNOME 不经过 Xlorie。Glycin／Resolute GIO／Mesa 均为真实新构建身份；独立 GTK 应用仍使用单独应用组件，其 Native 50 全套组合待验证。
- Native Ubuntu 外观以真实 Resolute 数据 DEB 重建，四扩展／schema／许可／来源核验及实际包 12 项测试通过，设备直接下载并验 SHA256。产物 26,178,566 字节，SHA256 `5d043a773b503c472743c1902d8347ea8ba6c8f7f88c79868e5f661b26288e0e`；第二次完整构建结果未确认。新组件资产均未发布，不进入 APK。
- 用户明确否决启动前初始化／静态预检。已将 GNOME 默认设置、链接配置快照、私有激活目录与执行描述；Hyprland 主题、配置、包装器和字体缓存；XFCE USB 静态会话；Native Plasma 服务路径与应用缓存；Java 脚本／桥部署和音频默认输出准备移到保存／安装。启动仅执行保存的内容与实际会话管理，失败退出，无安装器、初始化、修复或回退。
- 保存入口 5 项、GNOME 会话 15 项、GNOME／XFCE 进程夹具 31 项、管理生命周期 17 项、Hyprland 会话／安装 13／6 项、Ubuntu GNOME profile／安装 16 项、Plasma 安装 11 项、恢复本地初始化 9 项通过。Hyprland 保存的包装器分别执行两个 live token，内容未重写且不保存会话 token。GIO 11、Glycin 5、renderer 4、外观下载 8 项及 Hyprland GPU helper 14 项，以及 Hyprland shell／theme 和 Plasma system-bus 定向检查通过。
- `./gradlew :termux-ui:compileDebugJavaWithJavac` 成功；`./gradlew :termux-ui:testDebugUnitTest` 最终 801 项、0 failure、0 error。首次运行 37 项使用旧 Debian 备份夹具；已将正例改为 Ubuntu Resolute，保留错误发行版拒绝断言，复跑全部通过。
- 实际设备独立执行 Save（生产 `--prepare`）成功，再 force-stop App、启动 UI／显示视图、仅执行保存的生产 GNOME launcher。启动阶段显式禁止 `--version`／gsettings，桌面、Dock、Home 图标实际显示；四个静态会话文件的 mtime 均早于本次启动，没有启动重写。记录 Shell 28567 与 tracer 所属的 9 个进程后，停止启动器全部退出，另建宿主 sleep 保留。随后 force-stop，临时移走隔离测试会话描述，启动立即以 1 退出，描述未重建、ready 未产生；已恢复同一描述字节。设备使用隔离库覆盖 fixture，不代表新 APK 或四类全新安装已验证。
- 宽泛旧 `test-desktop-scripts.sh` 首项仍缺少保存阶段生成的 `libexec/termux-desktop/start-plasma` 夹具，报等待 PID 超时；其中还保留已废弃的直接 Plasma／Turnip 接口和启动时缓存预期。此脚本未通过，不把新定向用例代称为全仓库脚本回归通过。

保存／启动证据：`evidence/save-launch-separation.txt`；本地 `artifacts/gnome50/*-tests.log`、`session-current-tests.log`、`gnome-fixtures-current.log`、`save-coldstart-device.log` 与 `save-coldstart.png`。严格 Harness gate 和 OpenSpec CLI 仍缺失。新资产发布、Native 全套 GUI、四类全新安装、Discover 认证、实际 USB／高通 GPU 等尚待验证，整体 NotReadyForRelease，产品一致性 Pending，不归档。

启动失败的会话页面现返回列表并保留日志；已记录 exit／stopped 终态时不再等待启动宽限期。此 Android 页面路径通过 Java 编译与现有单元测试，尚未在新 APK 上实测；设备实测范围仍为隔离生产 launcher。


## 用户授权的组件发布与公开下载核验（2026-11-16）

用户明确要求“发布相关组件到组件仓库，总结当前任务完成情况”，本节取代前文“资产未发布”的当前状态；历史记录保留。本次授权发布组件，未构建／发布 APK，不代表最终验收或归档。

- 组件仓库 jiaxinchen-max/termux-app-packages 的清单、来源锁和验证说明提交 3c98bd7。两个版本均为公开 prerelease，draft=false，未改变现有 latest。GNOME 50 版本 15 个资产、Native Ubuntu 50 外观版本 7 个资产；原有组件及 catalog 保留。
- `gnome-50-resolute-20261116-r1` 包含 Mutter 50.1-4、Shell 50.1-2、GJS 1.88.0、SpiderMonkey 140.17.0-1、三个复用依赖及 Resolute GIO／Glycin。所有字节与 App 52ccd543 的固定安装清单一致，不需要改安装器。四个新 DEB 来自原编译机原 build-package.sh，不在本地重编。复用依赖逐项从其既有来源下载核验。
- 源包含真实原版 Mutter／Shell／GJS／Glycin 和 crate、App 修改及实际编译机配方／生成 GIR、GIO 和 Glycin 构建来源；SpiderMonkey 的未修改完整上游源码以固定 URL／SHA256 提供，全部本地修改随源包保留。两个 GIR README 只有记录说明差异，其他配方／补丁／GIR 与 App 源码一致。许可与来源保留，不上传诊断 runtime 或临时设备覆盖包。
- `native-ubuntu-gnome-50-r1` 外观包 26,178,566 字节，SHA256 `5d043a773b503c472743c1902d8347ea8ba6c8f7f88c79868e5f661b26288e0e`。已核实原编译机第二次完整构建与首包大小、SHA256 完全相同。本地对实际发布文件重新执行 12 项资源测试通过。
- 上传前逐文件验证字节，上传后 GitHub 所报 size／digest 全部匹配。再无认证访问两组公开发行版，核验完整资产清单和 draft／prerelease 状态；22 个资产逐个从原始 browser_download_url 下载，实际字节数和 SHA256 全部匹配，无需追加缓存绕过参数。既有 lfdevs Resolute Mesa 的原始公开 URL 也实际下载验证通过，保留第三方专用地址。
- 发布只影响固定新版本组件及文档；旧资产、用户容器不变。回退恢复之前 App 源码／组件清单，不自动降级容器。Native 全套 GUI、四类全新安装、真实 USB／高通 GPU／Discover 等仍待验证；宽泛旧综合脚本未通过，严格 gate 不存在；整体 NotReadyForRelease，产品一致性 Pending。

证据：`evidence/component-publication.json`、`current-status.md`；组件仓库 `repository/gnome/publication-20261116.json`；本地 `artifacts/gnome50/publication/public-readback.log` 和 `native-appearance-tests.log`。本次后续修改仅更新发布记录和任务状态，没有业务代码变更，因此不重复运行已通过的 801 项 Java 测试。

## 适配 APK 构建与下载交付（2026-11-16）

用户要求“将适配的apk给我下载”。以干净 App 提交 4b1ee426 构建 arm64，递归检出所有钉住 submodule；补齐本地 CMake 3.22.1／Bison／Flex 后原构建链通过。初次 ABI 注入构建会生成 testOnly；该候选未交付，最终显式加 `-Pandroid.injected.testOnly=false` 重新构建。最终构建命令及实际验证见 evidence/apk-publication.json。

指定名称 APK 为 45,514,430 字节，SHA256 `434d0dfab7f2e18ad3079348d73e746cf4ddd9b43972ae83863436d527302cd3`。apksigner v1／v2 验证通过；aapt 确认 com.termux、唯一 ABI arm64-v8a、实际 Manifest 不含 testOnly；ZIP CRC 通过。72 个 termux-ui 资源逐字节匹配源码，外部主题、GNOME runtime 和 termux-api companion 未打入 APK。既有 12,294 字节 X11 CLI DEB 和 70,480 字节 statx 小助手继续保留。

App 仓库独立公开 prerelease `desktop-adapted-20261116-4b1ee426` 包含 APK、SHA256SUMS 及逐资源验证清单，不替换 stable/latest。从原始公开 URL 无认证下载完整 APK，大小、SHA256 与本地及 GitHub digest 三方一致。未安装覆盖用户 App 或容器；完整全新安装／设备功能验收仍 Pending。此前 801 项单元测试证据保留，本轮仅构建／文档，无业务修改，不重复测试；规定的严格 Harness gate 仍缺失。不归档。

## 全新安装失败修复：App 部署遗漏 Glycin 助手（2026-11-16）

用户在全新 APK 安装后创建 Ubuntu GNOME PRoot，设备日志最终为 `Desktop install failed: installing desktop_install_glycin.py`。失败发生在宿主运行时下载后、Ubuntu rootfs 创建前。APK 内存在该资源，但 LaunchScriptInstaller 的部署数组遗漏它；本地核对其余 desktop_* 资源均已登记。修复仅补齐安装／保存阶段的部署清单，不增加启动时检查或修复。

新增 Robolectric App 测试实际调用 installDesktopScripts，从空目录部署并验证 Glycin、GIO、Mesa 安装助手存在且与 APK 资源字节一致。修复前测试准确失败于缺少 desktop_install_glycin.py；修复后同一实际部署测试通过（1 项），构建耗时 5 秒；APK 交付证据随后记录。Glycin 5 项、Ubuntu GNOME 16 项定向测试通过。用户明确要求修复后直接交付 APK，完整安装由用户手动测，本轮不覆盖用户 App／容器。原严格 gate 仍不可用。

修复版源码 c6ff13b3 已推送。最终清除增量 ZIP 空洞后重新打包，未增添运行资产；产物 45,395,870 字节，SHA256 `c8ffcd9291cbab243067c37144af53c8f52618c27b8d0193c6f4fd05e42f887d`，apksigner v1／v2 通过、arm64-v8a、Manifest 无 testOnly、ZIP CRC 及 72 个资源字节检查通过。独立公开 prerelease `desktop-glycin-fix-20261116-c6ff13b3` 的原始 APK URL 已无认证下载全包，大小与 SHA256 匹配。证据见 evidence/apk-glycin-fix.json。未执行完整设备安装，交由用户手动验收。
