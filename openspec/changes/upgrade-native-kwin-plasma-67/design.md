## Context

规划时尚未修改合成器、编译机配方或模拟器二进制。现已按用户批准完成源码迁移和候选构建，设备功能验证尚未开始。调查证据位于 `/home/user/workspace/artifacts/kwin-kubuntu-upgrade/`，此前协议实测位于 `../ubuntu-plasma-test/kwin-version-study/verification.md`。

| 对象 | 已核实状态 |
|---|---|
| 当前发布 | 自有 KWin r28，提交 fe13811e72f9b62e5439a4fd607b228b10961fe8，源码 6.6.7 |
| 候选基线 | 自有 Plasma/6.7，提交 a73f115af613b880dc1b11059f62b252cb94b2af，源码 6.7.5，已有 Android 后端及虚拟桌面 v4 请求处理 |
| 客户端 | 原 Debian Plasma 6.7.4；独立 Ubuntu 26.04 Plasma 6.6.6，不能代表完整 Kubuntu 镜像 |
| 编译机 | builder@120.24.191.156:7022，/workspace/termux-packages/build-package.sh 存在；原配方有未提交修改；按用户最新指令备份后在原目录更新 |
| 初始构建依赖抽查 | Qt 6.12.0、KF6/ECM 6.30.0、Wayland 1.26.0、wayland-protocols 1.49、PlasmaWaylandProtocols 1.23.0；所查版本达到 6.7 的对应最低要求，尚未运行完整 CMake 配置，其他依赖仍需实际构建核验 |

r28 的 16 项显式渲染脚本检查通过，未修改的 6.7 候选在缺少指定 ICD 时错误接受 Zink 的检查中失败。候选触摸 down/motion/up 仍用 0 时间戳，输出 mode flags 仍误用标志值作协议版本下限。两分支已分叉，不能依靠分支名或版本号直接替换。

## Goals / Non-Goals

目标是提供能构建、能回滚的原生 6.7.5 候选，保留 r28 的 Android 行为，并以同一宿主验证 Plasma 6.6/6.7 的实际客户端协议和桌面表现。具体候选 SHA、源码补丁、构建依赖、ELF 和包版本必须可追溯。

本轮不引入整条 D-Bus 共享，不把版本升级当作启动慢已修复的证据，不绕过音频健康检查、权限校验或服务就绪条件来宣称正常启动成功。没有实际 GPU 的模式只记录未验证，不能将软件 Vulkan 标成硬件加速。原工作区其他未提交更改、容器和用户 UI 默认模式必须保留。

## Decisions

### 候选基线与补丁迁移

采用现有 Android 6.7.5 分支固定提交，减少从纯上游重新适配的范围。仅给旧 6.6 回移 v4 虽可解决已复现请求错误，但不符合用户升级自有 KWin 的方向；直接使用现成 6.7 分支又会重引入已知回归，因此选择逐项迁移 r28 修复。

迁移清单至少覆盖：显式 renderer 和失败处理、Android 输入报文与原生触摸时间戳/取消、输出事件 since-version、AHB/Qt 客户端集成、共享 EGL 上下文与正常退出。先对完整 Android/包装脚本差异做清单，再按 6.7 API 调整；不能全文件覆盖新版后端，不能假设候选已有相同名称功能便等价。与 Qt 6.12 已发布修复配套的二进制保持版本一致，只有构建证明需要时才扩大依赖修改范围并更新方案。

### 编译与包装

只在指定 SSH 编译机运行已有 `build-package.sh`。按用户最新明确指令“直接复用原来的kwin编译链”，复用原配方目录、默认构建根目录、工具链及依赖缓存；原配方另存备份，原 kwin 缓存重命名保留，候选源码归档和输出仍单独保存。停止此前独立构建索引方案，不并发写入前缀。实际参数按现有脚本帮助/解析规则确认。自动依赖下载继续交由该脚本，不手动用 glibc deb 补齐 Bionic 库，不跳过依赖解析。

配方固定候选源码归档和 SHA256，以真实 6.7.5 和独立候选 revision 表达版本；不得只改包号或沿用旧 libkwin。包内 kwin_wayland、libkwin、插件及运行脚本来自同次构建。记录 sysroot 变更前后清单及实际编译命令、日志、链接库、架构、包依赖和下载大小。产物外置，候选的完整包在设备上下载、校验，再由标准包管理安装，不做零散二进制覆盖。

构建实测补充：原前缀中的 libudev 缺少 udev_device_new_from_devnum，KF6CoreAddons 6.31 的可选 UDev 探测误启用后链接失败。在备份该依赖原配方后，显式关闭其可选 UDev 查找，使用上游无 UDev 分支，保留其他包与前缀库；本项不提供 Linux 块设备服务，也不扩展为系统设备支持。

包审阅实测补充：App 已安装渲染库包名为 termux-render，编译仓库配方名为 termux-display-client；运行依赖使用标准 Debian 候选依赖兼容二者，构建仍经原 buildorder 解析。6.7 自动启用 EIS 和 PipeWire 插件，需显式声明 libei>=1.6.0 与 pipewire>=1.0.9；模拟器缺少 libeis.so.1，交付编译机缓存中同架构 libei 包并记录其依赖，安装范围交由候选审阅批准。

### 架构与 App 边界

Activity 仍走正常 UiLauncher/Desktop UI，保留用户 UI 默认模式；Service、任务状态和音频监督沿用现部署。除非源码/运行证据表明候选必须适配 App 接口，否则本轮不改当前 App 业务代码，不将安装成功或服务响应当成桌面成功。

shell 负责传递已有显式渲染与运行目录。宿主运行 KWin 与 Xwayland，容器运行 Plasma 客户端；Wayland socket、X11 和现有输入报文是需保持的边界。版本升级不改变两条 session bus 的归属。对 org.kde.KWin 的访问另行记录；不能将接口不可达错误与协议版本错误混成同一根因。

存储方面保留两个容器及其配置、原 r28 包和 native kwinrc；日志写入独立实验目录。组件下载保持外置。当前 App 的 r28 下载引用位于 desktop_install.sh；本轮候选不修改正式下载指向。候选验收后再准备组件仓库资产与 App URL/哈希同步，遵循后续用户的发布指令。

### 验证与因果边界

每轮功能测试先停止桌面、force-stop com.termux，按实际 UID/归属/PID starttime 检查残留，再从系统 UiLauncher/Desktop 页面启动。计时使用 native CLOCK_BOOTTIME，不使用 PRoot 的静态 /proc/uptime。

先验证普通及按屏幕虚拟桌面切换：使用已验证可运行的真实 Debian Pager/TaskManager 组件，记录协商版本、目标输出、实际当前桌面变化和客户端退出码；必须消除旧库的 v3/v4 错误，并保留旧客户端安全协商。随后通过正常 UI 分别检查 Ubuntu Plasma 6.6.6 和 Debian 6.7.4 的壁纸、面板、Dolphin 窗口、基本输入、停止和再次启动。Ubuntu 不显示完整面板则兼容验收未通过，即使独立协议客户端成功也不改变这一结果。

已知音频监督超时、host bus 文件路径消失和 PRoot 断言逐项归因。遇到这些错误时保留正常策略结果，不能通过增大超时或关闭检查来把候选判成通过。不具备受控计时对照时不宣称提速。

## Risks / Trade-offs

- 分支差异大、r28 补丁遗漏 → 完整迁移清单、旧库负向对照与实际功能回归，不能只通过源码搜索。
- 旧客户端收到新事件 → 迁移输出事件版本修复，检查 since-version，实际验证旧客户端协商。
- 新版私有 ABI 与 Qt/驱动组合不符 → 完整构建/ELF 校验，二进制按同一包成套安装；不拼接旧插件。
- 编译前缀和原配方已有修改 → 备份原配方和 r28 缓存后复用原编译链，记录原状态与依赖前缀差异，避免并发写构建前缀。
- 升级后仍无面板或启动退出 → 记录为该项验收失败，继续按具体日志定位，不强行发表支持声明。

## Migration Plan

先完成本方案确认，再迁移源码并在编译机生成候选。生成可审阅的包差异、哈希、依赖清单与完整 r28 回滚材料后，依现有设备实验授权边界确认候选安装动作；未获设备替换授权时只交付候选，不能把此前只读实验视为任意安装授权。候选设备限定既有模拟器 120.24.191.156:8057，不涉及其他设备或正式资产。

安装前备份 native kwinrc、桌面入口与原包信息，确认 r28 完整包可读且校验通过。若触发回滚，停止候选进程，恢复匹配的原包及依赖/配置，再核验二进制版本和 App 原入口。不得删除或重建容器。提交、推送、正式 release 和 APK 构建均待具体后续授权。

## 保护路径与流程

拟修改路径为外部 KWin 的 src/backends/android/**、必要的 Wayland/包装代码及原编译链中的候选配方；在取得可写源码后先读取其实际指令和保护规则。当前 App 业务路径不在首轮修改清单。仓库没有可用的 Harness bootstrap、rules 或 verify.sh，因此没有可引用的 protected-path policy id，不能虚构或宣称保护审核通过；遇到实际规则后将具体 policy、影响、验证和回滚补入台账。

OpenSpec 1.4.1 可通过 npm 临时执行，规划用该 CLI 生成/校验；Harness strict gate 仍单独标为 Unavailable。产品文档 docs/product/desktops.md 只登记为待维护目标，在最终用户验收后按真实通过矩阵维护。


## 2026-11-14 发布授权补充

用户在候选交付后明确回复“发布”，授权将当前 6.7.5-1 aarch64 候选发布至 jiaxinchen-max/kwin，并同步 App 的下载配置。此次发布使用独立 tag termux-hwaccel-6.7-r1、真实补丁源码提交及 GitHub prerelease；源码 tag 推送属于该组件发布范围，App 仅同步当前工作树配置及对应验证夹具，不推送其他未提交工作。所有原 r28 共享依赖按原哈希复制到新 release，增加 libei，旧 release 保留。

发布核验包括源码对应、全部 App DEBS 的公开 URL/大小/SHA256、安装脚本回归及元数据检查。公开发布不等于设备验收：不安装模拟器，不宣称解决启动慢或通过 Kubuntu/Ubuntu 桌面验收；产品文档仍 Pending，不归档。若发布失败，保持草稿并修复；若需撤回 App 指向，可恢复旧 tag、旧 KWin 包和原 DEBS 清单。


## 2026-11-14 全新Native安装修复授权

用户在两项故障及修复范围说明后明确回复“修复”，授权修复旧libkwin路径和遗漏的Qt AHB/System EGL/renderer probe交付组件。沿用本变更已批准的指定Linux编译机、原KWin build-package.sh、既定组件发布与APK交付流程；新包使用6.7.5-2与独立termux-hwaccel-6.7-r2 prerelease，保留r1历史资产，不覆盖已发布二进制。修复只补齐本来就要求的Native/System EGL能力，不增加Ubuntu/Kubuntu创建入口，不兼容旧数据。

实现将tools/termux-qt-ahb-plugin作为显式可选CMake子目录纳入原构建，原配方开启该选项并固定完整源码及SHA。同步App HOST_FILES到最终deb实际文件及哈希，保留缺件失败检查，为libei运行库补充必要项。新增包级真实文件校验覆盖错误库版本和缺失Qt辅助文件，避免仅靠模拟夹具放行。若编译发现Qt私有API或依赖差异，仅按实际报错适配且留证，不能把旧版二进制拷入新包。

设备验证范围为现有模拟器上用户已失败的native/system桌面：保留配置和已安装包记录，修复文件通过模拟器下载后标准安装；验证每轮先force-stop、核验归属残留、从正常UI重新进入。先验证安装完成及System EGL初始化，不把这次修复写为容器兼容性或启动速度验收通过。不清数据、不删容器、不卸载App。APK构建保持既定arm64文件名、原签名、testOnly=false和资产外置。

保护路径影响为KWin构建入口、既有配方和App安装清单。无实际保护路径policy可用，Harness脚本仍缺失。回滚保留r1/旧r28包和旧配方/源码归档，用户设备当前包及配置先备份；源与App未提交改动不覆盖。产品文档保持Pending，不归档。


## 2026-11-15 已批准的Native GLES读回兼容修复

用户在已验证BGRA扩展缺失及RGBA读回可行的具体方案后回复“修复”，批准KWin OpenGL核心能力检查、必要读回转换和诊断日志修改，以及原编译链新revision、当前模拟器干净UI验证；沿用既有组件发布/App同步/APK交付授权，不覆盖r2二进制，不推送App其他工作。根因与真实像素证据见 /home/user/workspace/artifacts/native-plasma-desktop-exit/bug-candidate.md。

修复保持真实能力报告：新增原生BGRA读回能力查询，仅Android/System GLES且GLES>=3的既定路径允许RGBA兼容读回，其他后端原有必需能力检查保持。审计现有BGRA调用点：DRM CPU导入属于未放宽的DRM后端；Android可能经过的录屏QImage读回使用RGBA临时图像，按原目标格式转换并逐行拷贝，保留外部目标缓冲和行距、预乘alpha。截图等既有RGBA路径不变。转换时保存并恢复GL pack状态/PBO绑定，临时像素分配失败时明确失败，不读取或覆盖缓冲边界，不伪造扩展。

补充上下文创建、make-current、基本能力及shader校验失败日志。验证使用原编译链编译的独立原生工具调用真实EglContext和实际生产读回函数，覆盖RGBA颜色、BGRA替代转换、外部带padding目标及边界哨兵、alpha、翻转/GL状态恢复；原生工具仅用于取证，不安装进正式deb或APK。还需实际Native/System EGL UI的桌面/面板/Dolphin/截图与重启验证。仅编译成功不记为运行通过。

保护路径影响为OpenGL核心和录屏辅助路径，已由本次明确“修复”确认。回滚保留完整r2包、源码、配方及设备配置/包清单；不清数据、不换用户渲染模式。无HTTP合约，api_docs保持N/A；用户可见兼容性product_docs保持Required/Pending，验收前不归档。Harness与OpenSpec CLI均缺失，记录Unavailable，不宣称strict gate通过。

### 实际可视验证追加定位

解除GLES能力检查后，实际可视验证暴露共享内存窗口的合成画面重复拉伸；此项尚未通过，不能以进程/日志替代验收。先用未公开的诊断构建核对上传、绑定、绘制及纹理内容，不更改渲染模式、不清数据。必要修复仍限于本轮Native/System GLES启动后可用性的图形兼容路径，源码、构建缓存及最终归档必须全量核对；去除临时跟踪后才允许发布。独立AHB发送接收与导入的两缓冲区隔离试验已通过。

实际图形验证进一步得到确定性回归：生产GLTexture上传的红、蓝纹理内容分别正确，但红→蓝→红绘制第三次采样为蓝色；每次提交当前纹理既有过滤参数后同样测试恢复正确。兼容处理限定为Android/System GLES且renderer为Android Emulator，在GLTexture::bind重提既有过滤参数，保留其他渲染器缓存；不改真实扩展报告、不更换渲染模式。补充反复纹理切换回归并复验真实桌面。另修正blur下采样着色器残留texture2D以适配预处理后的GLSL ES300，使用同一真实上下文验证上下采样着色器编译；旧GLSL通过既有预处理兼容函数保留。

## 2026-11-15 r3交付后黑屏的同范围修复

用户报告App存活但桌面黑屏，继续既有Native/System EGL可用性修复授权，不增加产品能力。现场新UID10202，KWin12409存活，plasmashell退出；两份Android崩溃记录显示Qt5Compat GraphicalEffects延迟加载进入AHB插件后SIGABRT。实际r3 shim动态符号表缺少termux_system_egl_dispatch，独立ctypes按正确LD_LIBRARY_PATH加载后可复现undefined symbol。该函数未显式导出，继承顶层隐藏可见性，EGLAPI导出的eglGetDisplay仍存在。此前可见桌面通过不覆盖实际AHB特效延迟加载，完整Native功能继续视为未验收。

仅为身份函数增加default visibility，保留显式renderer和identity检查、不允许失败回退；包门禁读取实际ELF动态符号表，使用旧r3负向证明；验证真实Qt客户端和Qt5Compat特效加载，并做干净UI启动及延迟稳定性检查。沿用原编译机build-package.sh生成6.7.5-4与独立r4 release，同步App固定文件/SHA和同名原签名APK。保留r3旧包/配置回滚，不覆盖历史资产、不推送App脏树。api_docs N/A，product_docs Required/Pending；Harness门禁Unavailable。

### 实际Qt特效验证追加协议修复

身份导出修复后，真实QML GraphicalEffects渲染触发Wayland invalid arguments：手写客户端create_buffer签名nh，而同包服务端XML为nhu，缺少flags。候选未发布。移除重复手写协议描述，利用现有ECM/WaylandScanner从服务端同一份XML生成私有客户端代码，发送明确flags=0（保留alpha、原生通道顺序）。仍按v1绑定，不扩大事件能力；保持既有AHB缓冲生命周期和渲染模式。Qt5Compat真实窗口测试必须覆盖完整缓冲导出和正常退出，定时器打印成功不能覆盖Wayland连接失败。该修复仍属于既定Native/System EGL桌面可用性范围。

## App Ubuntu Plasma 底包修复设计（用户已批准）

新建kwin_plasma容器使用ubuntu:resolute，对应Ubuntu 26.04；实例别名由ubuntu前缀生成。安装器和初始化器严格要求ID=ubuntu、VERSION_CODENAME=resolute，拒绝Debian及其他Ubuntu版本。使用现有Plasma客户端包与Ubuntu可用Kubuntu桌面设置；继续原生宿主KWin，不启动容器合成器。归档元数据接受Plasma/resolute，归档实际os-release按桌面目录要求Ubuntu，XFCE仍要求Debian/trixie。既有System EGL/Zink与LLVMpipe/Zink分层校验、失败传播及外置资产策略保持。检查Zink硬件驱动资产，不将Debian二进制覆盖Ubuntu。

验证包含Java创建/启动规格/归档分发、Python真实发行版初始化正反向、安装器基线与包选择和APK内实际配置。构建Linux arm64 APK，核验签名、testOnly、脚本一致性和完整HTTP下载。完整设备安装由用户执行，不伪报功能已验收。无仓库保护规则可读；安装/初始化与归档校验的影响为发行版识别，当前“修复”已批准；备份工作树受影响文件，回滚恢复本次备份及旧APK即可，不触碰设备数据。
