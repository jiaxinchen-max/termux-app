# 容器负责桌面会话的设计（用户已确认实施）

## 架构决定
上一版宿主目录服务、JSON 应用索引、Classic 镜像 .desktop 和逐个应用转发的设计被本方案取代，不实施两套并行架构。当前宿主 Hyprland 对接 Android，PRoot 容器运行所有客户端桌面组件。发现、展示、搜索和启动都在同一容器环境内完成。

### 宿主职责
保留已校验的 Hyprland runtime、Android 显示/输入、Wayland/IPC socket、音频和 USB 设备桥接、进程归属及 App 就绪管理。合成器日志、Android 电量等只能由宿主获取的状态通过窄数据接口提供，不能把容器无权读取的路径伪装为可用。为 App 终端和文件快捷操作及合成器按键绑定提供一个固定容器命令入口，避免每个应用另造宿主菜单或包装器。

### 容器职责
Ubuntu 以普通 termux 用户运行面板、启动器、壁纸客户端、通知服务、桌面 session bus、终端、文件管理和应用。PackageKit/PolicyKit 继续由已有 guest root 服务监督器管理，授权 UI 保持普通用户，沿用真实认证。GTK 设置、图标、字体、EWW 和终端配置位于容器用户 XDG 路径；不把宿主 PREFIX 或宿主 XDG_DATA_DIRS 注入容器。

## 组件准备与兼容验证
首先核实 Ubuntu 26.04 arm64 对两种外观所需组件的实际供应和运行能力，包括 EWW、Python GI/GtkLayerShell、通知服务、swaybg 及 Classic 面板。Termux 现有 Bionic 可执行文件不得当作 Ubuntu glibc 组件使用。能从已配置 Ubuntu 源获取的包在安装阶段准备；不在普通启动编译或下载。

如 EWW 等无适用包，先记录依赖、构建来源、许可证、可复现步骤和成本，再准备本地候选；外部资产发布仍需单独授权。不得将缺少组件掩盖为主题已安装，也不得静默把 m3ww 换成 Classic。移植前需要通过目标容器的 Wayland/layer-shell、通知和 GTK 启动探针；没有设备只能记录协议/组件层证据，不能声称 Android GUI 通过。

PRoot 的组件身份分为宿主合成器和容器外观预设，移除 PRoot 对当前指定 runtime_id/theme_id 精确组合的强制耦合。普通图片、颜色、图标资源不依赖合成器版本；含 Lua 配置、窗口查询或操作的预设按实际接口能力检查。Native 继续原有部署和兼容策略。旧主题归档与公开校验材料保留原义，不篡改历史资源哈希或伪称旧预设已完成迁移。

## 安装、保存、恢复与普通运行
修改 desktop_install.sh 的 PRoot 分支，使宿主仅检查合成器与桥接依赖；容器阶段检查并安装桌面会话依赖、主题资源及必要脚本。保存和恢复时生成固定 session、映射与就绪配置，验证当前设置。普通启动仅创建会话 IPC、启动已准备服务及动态桌面状态，不安装包、不修复或迁移持久配置。

用户原有 .config、主题和菜单不直接覆盖。对 App 管理内容放入明确的容器私有预设目录，以 XDG 配置目录或显式 include 引用；已有自定义配置发现冲突时明确报告并由用户选择，不用默认文件替换。宿主旧配置保留为回滚来源，不为瘦化宿主而自动删除仍可能被其他桌面使用的软件包。

## 一次容器会话与总线分工
在宿主合成器就绪后启动当前容器会话监督器，绑定本桌面短 runtime。容器内启动一个统一 session bus，再在该会话运行桌面组件及所有应用；不再为每次菜单点击新建 dbus-run-session。USB off/on 均使用该容器 session bus，guest system bus 继续独立于宿主。

保留 container_command 的普通用户、渲染、音频和 USB 环境清理与映射。只有包管理后台需要 guest root，桌面/UI/认证窗口不提权。会话 token 贯穿监督器及子进程；关键组件退出时按既有必要服务失败规则撤销就绪并清理本桌面，不能以宿主面板兜底。USB 先卸载和释放，再停会话总线，最后停止其余客户端与合成器。

## 容器内应用发现与启动
两种外观通过容器本身的标准应用目录处理程序：支持 XDG_DATA_HOME、XDG_DATA_DIRS、用户同桌面 ID 覆盖、Hidden、NoDisplay、OnlyShowIn、NotShowIn、TryExec、嵌套目录 ID 及本地化。m3ww 启动器改为容器内运行，调用容器 GIO；Classic 使用容器面板的标准菜单。保留同名不同 ID 程序，软件中心由真实安装条目提供，不另写固定名单。

应用安装、卸载后正常负载十秒内反映到菜单；通过标准目录变更通知或容器菜单自身刷新机制完成，不导出宿主 JSON 应用索引。仅带有效桌面入口的程序进入图形菜单，不把可发现等同于已验证其全部图形能力。菜单扫描不执行应用。启动用容器 GIO 处理 Exec、字段码、Path、Terminal 和 D-Bus，避免手工解析桌面 Exec 或在宿主 shell 拼接元数据。

单个坏入口跳过并记录原因，目录系统故障显示明确状态；过期条目在点击时重新检查，不启动已移除程序。第三方图标直接由容器主题解析，不再把容器绝对图标路径交给宿主读取。

## 合成器配置与 IPC
合成器仍读取宿主可见配置。保存时将受管容器预设中需要合成器执行的最小片段以明确路径提供给宿主；不得把整个 rootfs 当作宿主 HOME。配置中的相对路径、include/dofile 和环境引用按实际执行方处理，不能假定容器路径在宿主有同一含义。

重点处理 Hyprland exec：命令实际在合成器所在环境执行，预设中的应用启动和按键绑定必须统一走容器会话入口，不能只把面板移入容器而保留指向宿主的启动命令。不尝试以文本替换改写用户任意 Lua 配置。

容器面板窗口/工作区操作优先经共享 Unix socket 使用 Hyprland IPC；验证对应 socket、会话身份和接口能力。不得复制 Termux 版 hyprctl 到 Ubuntu。若需要适配客户端，使用能在容器运行的 IPC 实现或窄范围桥接，明确只连接当前桌面。必须与数据目录可见性、事件流及进程退出一起测试。

## Android 边界
Activity、Android Service、任务状态、X11 和输入框架不重构。Java 组件选择与展示仅在 PRoot 路径解开合成器/外观精确组合检查，并登记新增会话资产。组件下载仍走现有受校验流程；不引入未批准外部发布。存储变更限于当前容器、当前桌面 IPC 及受管配置，不扩大 Android 权限。

## 风险、验证与回滚
未发现保护路径策略文件；可能修改桌面脚本、Java 组件校验/资产登记、组件生成工具与测试，不改签名、凭据、CI 或发布配置。实施前再次检查保护规则；若发现实际策略，补录 policy id、影响、验证、回滚和所需授权。

验收覆盖：真实 glibc 组件和 layer-shell、通知、壁纸、统一会话总线；受控 .desktop 的名称、隐藏、覆盖、参数、Path、Terminal 和 D-Bus；安装/卸载动态刷新；面板快捷键、窗口/工作区和多会话隔离；USB off/on、渲染模式和停止清理；原生及其他桌面回归。代码审查与本地 APK 构建、签名/资产/原始文件名核验必须有证据。缺 Harness 和设备时单列限制，不用自动测试替代实机。

回滚恢复旧代码并显式保存，停机后重新启用保留的宿主受管配置。清理仅限登记的新会话派生数据，不删除用户 .desktop、主题、容器软件包或自定义菜单；不得自动改回别的桌面。

## 实施补充与验证边界
Ubuntu Resolute 官方 arm64 索引提供 Classic、GTK/GI、GtkLayerShell、通知、Foot/Fish/Starship，未提供 EWW。采用安装阶段原生源码构建：上游 MIT 许可 v0.6.0，来源 https://codeload.github.com/elkowar/eww/tar.gz/refs/tags/v0.6.0 ，SHA256 `cef361946946c566b79f8ddc6208d1a3f16b4ff9961439a3f86935e1cfa174a1`。为适配 Rust >=1.80，将 time 锁定为 0.3.36，完整 Cargo.lock 随 APK 提供，SHA256 `cc8b1d7f7b2033921d39d28613cac5fd27f1ec86445a81da09ee133e5d144f57`。使用 `cargo build --locked --release --no-default-features --features wayland`、两个编译任务；首次安装需要网络、磁盘、内存及明显更多时间，正常启动不编译。该上游 tag 的二进制自报版本仍为 0.5.0，收据按来源和锁文件哈希识别。

程序和收据位于容器 `/usr/local/lib/termux-hyprland/`。同源码和锁文件在 Debian amd64 构建成功，使用生成的配置在 headless Sway 打开真实 EWW layer-shell 面板；Ubuntu 26.04 amd64 完成组件安装、两套普通用户预设准备、真实 GI/GtkLayerShell surface 配置。Linux Classic 面板亦通过协议探针。这些证据不等同于 Android arm64 完整桌面通过。

Resolute GdkPixbuf 通过 Glycin 解码，PRoot namespace 限制造成壁纸/图标失败。复用项目已有的固定哈希 glibc Glycin 适配器及安装/保存校验助手；仅当前 PRoot 视图绑定到发行版库路径，保留软件包文件，不设置全局禁用 sandbox 的环境变量。Linux 沙箱内完整 Ubuntu 会话仍因 bubblewrap 兼容边界超时，保留失败日志；协议探针与单元测试不掩盖此限制。

Ubuntu Classic 使用 `gtk-layer-shell` 默认名称；就绪另须 `org.xfce.Panel` 总线所有者，避免误认其他 GTK surface。m3ww 使用 `m3ww-bar`。应用由 GIO 发现，监听 AppInfoMonitor 并每三秒刷新；纯 D-Bus 激活、Terminal、字面参数、Path、目录变化和不可读目录均有真实 GIO 测试。Android 图形/输入、窗口操作、arm64 首次编译、USB off/on 及实际软件中心装卸刷新仍待设备验收，不归档或确认产品知识。
