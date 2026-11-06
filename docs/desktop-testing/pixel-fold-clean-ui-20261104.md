# Pixel Fold UI 模式全新安装验证（进行中）

设备：Pixel_Fold_API_35 / emulator-5556。主验证使用 Fold；11 月 5 日按用户要求临时启动 Pixel 7 完成同机对照，已恢复原文件、数据和桌面条目并关闭。

卸载 com.termux 后本地构建、安装 APK，在 App 真实终端执行 pkg update / upgrade。首轮升级70包、新装16依赖，退出0；dpkg --audit为空，无待升级包。后续桌面安装继续使用最新Termux索引。正式入口、保存、启动、触控、方向、备份恢复均通过App UI；ADB仅部署、准备测试夹具和读诊断。

公开测试程序：微软WinFile x64；来源及SHA见证据目录exe-source.json。独立com.termux.api、com.termux.x11暂禁用，Native/Proot WinFile均能显示；结束恢复原enabled=0。

## 已修复

- UiNavigation恢复时启动Intent已被Termux消费：空值处理，恢复不再崩溃。
- 横屏Files缺少ScrollView ID：补齐，授权返回、刷新与目录监听已实测。
- 组件服务连续任务前台化竞态：每次startForeground，stopSelfResult成功后才移除前台。
- 组件重试/恢复读旧状态：等待服务持久化确认，拒绝、超时或中断明确失败。
- 跨实例任务文件替换ENOENT：同目录独立临时文件、fsync与原子替换。
- Auto深色系统栏图标错误：恢复/挂载后重应用颜色，显式Dark及Auto浅深实测通过。
- Desktop每2秒重建卡片打断点击：按内容/布局快照保留View，后台暂停轮询；GNOME创建等UI实测通过。
- 前缀维护继承Zink：维护会话明确LLVMpipe/swrast并隔离驱动路径，游戏仍按独立选择运行；Proot准备实测成功。
- RootFS前缀准备重复复制1.7GB安装源：仅实际重建时暂存，预热前释放；复用/重建两条执行型shell回归通过，完整设备重建峰值待复测。
- 系统关闭会话后启动页仍显示运行中95%：移除sessionOpened对终态刷新的跳过。任务此前已正确记录host_process_lost；2项回归通过，最新APK正常Native显示和退出通过，未声称崩溃专项UI已复测。

共18项Java回归分批通过，维护环境隔离及staging生命周期3条shell场景通过。构建日志和APK SHA均保存在证据目录；最新APK为plasma-shell-apk-build.log对应产物（ARM64 SHA见baseline.json）；误加的暂停/恢复/重试会话框按钮及新增暂停API、测试已撤销，原有3项命令握手测试通过。

## 运行与恢复

Native：Wine9.3 vanilla / VirGL / WineD3D / PulseAudio / 1280×720。Proot：Hangover11.16 / LLVMpipe / WineD3D / PulseAudio / 1280×720。同一EXE生成两个独立配置，主编辑Save and prepare即开始准备。两者均已实际显示及操作C盘/目录，触控与方向独立，抽屉退出通过。

Proot C盘与三个注册表通过UI备份。App终端ZIP完整性通过；删除本轮测试Windows目录和标记后UI恢复成功。设备内逐字节比对标记、kernel32.dll和三个注册表与归档一致，且归档不含共享环境；恢复后WinFile重新显示。归档未导出设备。证据：cdrive-restored-compare-in-app.png。

空间不足时清理了已安装压缩缓存及失败临时产物。上述验证完成后删除本轮Proot配置前缀；经用户明确授权删除本轮4.8GB Wine共享RootFS，保留组件源和C盘归档。因此再次运行Proot Wine需重建基础环境。

## Native Plasma

Native / System EGL / 1280×720 / 30 Hz，通过正式UI网络安装完成（exit0）。模拟器renderer为Android Emulator OpenGL ES Translator (Apple M1 Pro)，isOpenGLES=1、matches=1；没有运行模式回退。应用菜单与System Settings可操作，音量从100%调整至23%，无额外输入也能刷新。

System Settings的扬声器测试触发libcanberra播放流，Corked=no、Mute=no，OpenSL_ES_sink为RUNNING；Android AudioFlinger对应PulseAudio PID1618的44100 Hz音轨active，underrun=0、InternalMute=false。证据native-plasma-ui-speaker-pulse.txt及native-plasma-ui-speaker-flinger.txt。

点击白色面板图标出现“Unknown application folder”；起初误认是文件管理器，读取面板配置后确认实际指向未安装的Discover。另确认Dolphin/Konsole也未安装。已在Native/Proot Plasma安装包清单显式加入这两项，38项隔离安装合同测试通过；本地APK构建成功，UI重装exit0后，Dolphin从应用菜单实际打开，双击进入tmp；F4打开Konsole终端面板并显示shell提示符。Discover失效pin尚待处理，未声称补装Dolphin已修复它。

## 环境问题与发布记录

- 16:57 Android Watchdog记录AMS/PowerManager阻塞89/91秒并重启system_server，下载随后DNS失败；重试后准备成功。
- Fold折叠后内外物理显示均OFF，Home/唤醒也黑屏；仅重启Fold后展开恢复。普通系统横竖旋转通过，折叠外屏视觉未通过。
- 19:08软键盘操作出现ANR。Gboard61%、system_server40% CPU，Termux主线程采样等待事件；尚不能将根因归为应用死锁。系统关闭App后发现上述95%旧状态问题。强制崩溃注入被自动审批拒绝，未执行。
- NativeXFCE全新网络安装失败：App固定Xserver r8，而GitHub只公开r7，xorg-server21.1.16-8下载404。未回退到r7。此前r8为本地已验候选，不能以缓存安装代表网络可用。
- r8的12项公开发布资产（约17MB）已核对，deb SHA与App pin一致；来源commit84dad5a1d4952eead5378023f02edd2d079bf1f0。材料位于证据目录xserver-r8-publication。用户授权后已推送修复分支并公开r8；GitHub API验证12项资产名称/大小/SHA全部一致。保留r7，未推送App工作区。
- KWin/GNOME/USB其余发布资产名称与GitHub digest均已核对；Native Plasma r27已安装成功，后台安装持续且返回UI为Ready。

## PulseAudio 包替换识别修复

GNOME安装重装pulseaudio后，旧15786的exe为pulseaudio.dpkg-tmp(deleted)但TCP健康，旧--check路径误判并启动19221，出现两个实例。已改为优先检测规定TCP endpoint；6项执行型回归与APK构建通过。正式UI重装包退出0，旧--check返回1而TCP健康；再次Open GNOME后仍只有原PID20537，指定端点正常、无新启动，回归通过。原首次TCP丢失原因仍不能直接断言。

## Native XFCE 与内置 USB

通过App以Native/System EGL/1280×720/30Hz安装XFCE，exit0；Xserver r8网络下载404已解除，并安装USB bridge0.4.1。通过正式面板Open Folder打开Thunar并列出Home；Applications菜单Terminal Emulator打开实际xfce4-terminal。终端继承PULSE_SERVER=tcp:127.0.0.1:4713，未额外覆盖参数，执行paplay测试音。Pulse未静音且未cork，OpenSL_ES_sink RUNNING；Android44100Hz音轨active、underrun0。

首次启动后曾观察到TCP模块消失（本地Pulse进程仍活跃，App终端也确认TCP拒绝）。UI原参数重开后正常；随后停止桌面与Pulse、确认无进程，再经App冷启动并跨空闲播放成功。两次日志均未再次观察到模块自动卸载，未确定首发原因，不声明已修复。临时daemon.conf为本轮新建且原不存在，取证后已按内容校验删除；Pulse日志级别和目标已通过App恢复。

旧独立com.termux.api/com.termux.x11禁用时，External storage正式页面显示内置模块与支撑包就绪、只读服务连接；Refresh devices后明确无已授权或待授权设备。无物理USB，真实授权和挂载未验收。首启曾有两次10秒枚举超时，后续原参数重开未再出现。

## 验证矩阵

| 项目 | 状态 | 记录 |
| --- | --- | --- |
| 全新安装与 bootstrap | passed |  |
| pkg update / upgrade 与包审计 | passed |  |
| 首次进入 UI 模式与空状态 | passed |  |
| 五标签导航与转场 | passed | Wine/Desktop/Terminal/Files/Settings均经正式UI切换。 |
| Auto/Light/Dark 与终端/会话框主题 | passed | Light/Dark与Auto跟随系统浅深色、终端/会话框及系统栏通过；系统Dark下显式Light保持浅色。 |
| 展开/折叠/旋转布局 | partial | 展开和App Auto下系统横竖旋转正式通过；修复版折叠复测内外物理display均OFF。唤醒并返回Android Home仍黑屏，外屏视觉受模拟器显示状态限制，未判通过。；更新APK后出现一次冷启动焦点ANR并复发提示，系统Close app后重新启动正常；Java栈未采集成功，不判断根因。 |
| 共享存储权限、目录浏览、文件详情与 EXE 导入 | passed | 首次共享存储授权返回、横屏、嵌套目录、网格/列表、EXE详情与新增目录自动刷新通过。 |
| 嵌入终端、会话和扩展按键 | passed | UI真实终端执行pkg及sleep，保留扩展按键、浅深配色、会话切换，恢复无崩溃；退出会清理会话及子进程。 |
| 组件目录、安装日志、版本选择 | partial | 全量Native/Proot网络安装、版本共用页与手动导入文件选择器通过；真实终端脚本日志自动滚动。服务前台竞态、重试恢复旧状态、任务文件替换ENOENT已修复；不增加暂停交互，仅验证成功或明确失败。RootFS维护环境隔离通过设备准备验证，staging两条shell场景通过，完整新建峰值待复测。。本轮额外Wine8.18下载明确FAILED unexpected end of stream；误加的暂停/恢复/重试会话框按钮、API与测试已撤销，构建及原有3项握手测试通过。 |
| Native EXE 导入、准备、运行与独立配置 | passed | 全量网络组件安装、保存即准备、WinFile实际启动显示成功；独立触控/方向选项无全局继承。；最新APK、独立API/X11禁用后再次启动成功并正常抽屉退出。 |
| PRoot EXE 导入、准备、运行与独立配置 | passed | 同路径Native/Proot独立配置。Save and prepare完成Hangover11.16/LLVMpipe/WineD3D/Pulse/1280×720准备；WinFile显示、Direct touch切C盘、独立方向及退出通过。首次系统Watchdog/DNS失败与空间不足经原参数UI重试成功。旧独立API/X11禁用下运行。 |
| 运行抽屉、输入、方向、退出顺序 | passed | Native/Proot独立触控和方向选择通过；Direct touch目录操作及先收回抽屉再结束会话通过。 |
| PRoot C盘/注册表备份恢复与 Native 无入口 | passed | Proot UI备份成功，App终端ZIP完整性通过；删除本轮测试Windows目录和标记后UI恢复成功。App终端逐项比对标记、kernel32.dll和三个注册表与归档完全相同；归档只含C盘/注册表/格式元数据，Native无入口。；恢复后WinFile重新显示。 |
| Native 桌面安装、启动、操作与音频 | partial | Native Plasma、XFCE、GNOME均通过正式UI网络安装exit0、System EGL启动、文件管理器/终端实际操作及Pulse/Android音频。Plasma显式补齐Dolphin/Konsole，38项合同回归通过；Discover失效默认pin尚未处理。Pulse包替换后旧exe(deleted)导致重复实例已修复；App终端pkg重装Pulse并重开GNOME，复用同一PID且实际播放通过。首次TCP模块丢失未再次复现，不能断言根因。 |
| PRoot XFCE trixie 安装、启动、操作与音频 | partial | 正式UI全新安装exit0，trixie。显式LLVMpipe启动失败：Xserver缺EGL AHB/dmabuf导入扩展，无自动回退；UI单独改为System EGL/1280×720/30Hz后启动成功，Thunar Home和容器Terminal实际操作通过。继承PULSE_SERVER播放音频，Pulse RUNNING未静音，Android44100Hz活跃音轨且underrun0。LLVMpipe支持缺陷仍未修复。 |
| PRoot Plasma forky 安装、启动、操作与音频 | partial | forky正式UI安装exit0，dpkg --audit为空，原失败10包installed且postinst未改。UCF_TERMUX_PROOT_STATX_MODE=proc-fdinfo最终库在6022构建，真实ucf/ucfr与statx回归通过。显式LLVMpipe/1280×720/30Hz启动，Dolphin、Konsole、Direct touch操作及继承Pulse音频通过。SHELL明确为/bin/bash；移除定时模拟鼠标补救后，首屏自行显示，关闭视图再Open仍重绘。首屏存在明显初始化延迟；Stop时KWin析构SIGSEGV仍待候选验证。 11月5日7022按原recipe远端构建析构候选，仅替换精确r27的libkwin，ABI/payload通过；Fold Native System EGL启动后正式Stop成功，PID6548退出，status stopped，App日志无segfault且Android crash buffer为空。PRoot LLVMpipe Stop未重建容器复测，候选未发布。 |
| PRoot GNOME trixie 安装、启动与应用列表 | partial | Native GNOME System EGL界面/Files/音频此前通过。Proot trixie正式UI安装537包exit0。11月5日7022构建撤回GLX_INTEL_swap_event虚报的Xserver候选；Fold固定gl3/LLVMpipe概览、应用网格、Files /usr、Direct touch及UI启动音频通过，Pulse RUNNING且Android44100音轨active、underrun0。新版APK已安装到小米，实际gl3/Zink Turnip Adreno660、Accelerated yes；概览及Files /usr/bin、持续时钟刷新通过。无drirc或renderer回退。候选尚未公开发布，生产App下载pin仍对应原r8，因此不能视作全新网络安装已含修复。 |
| 桌面导出/导入与依赖检查 | passed | 正式UI导出XFCE容器成功，再从导入卡片选择归档恢复成功；先校验并检查5项固定版本宿主组件，全部已满足，无网络/更新。产生独立alias/id，保留System EGL/1280×720/30Hz与Direct touch，启动并操作Thunar成功。未测试缺失依赖时的实际安装分支。 11月5日当前APK从Import卡片再次导入，5项预检日志在正式会话框及任务日志各一次，累计日志去重修复通过；本轮解包16:15完成SUCCEEDED，恢复桌面显示；已通过正式UI删除本轮导入测试副本和容器，原归档保留。 |
| 设置入口、组件与帮助 | passed | 默认UI持久化和冷启动、设置帮助、Native/Proot核心组件菜单、Native核心无备份恢复、X server两项显式选择检查通过；保持内置Termux-X11。 |
| 双返回退出、服务/进程结束与重启持久化 | passed | 默认UI冷启动、配置与主题持久化通过；第一次返回留在UI，双返回清理所有同UID进程和services。另经UI运行sleep600后再次退出，Bash/sleep/TermuxService全部消失，清理日志成功。 |
| 外接存储模块与无设备提示 | partial | 旧独立API/X11 APK禁用时，内置USB模块与0.4.1支撑包通过UI安装；NativeXFCE只读服务连接成功，正式页面无已授权或待授权设备。刷新后再次断言无设备提示通过。无物理USB，真实授权/挂载不能验收。初次启动有两次10秒枚举超时，后续原参数UI重开无错误。 |

证据：`/tmp/fold-ui-clean-20261104-142515/`；详细矩阵`matrix.json`。验证仍在进行。

待恢复临时状态：独立API/X11 App enabled=0；stay_on_while_plugged_in=1；AC off。Night no、UI Auto、rotation free已恢复。

## 安装交互纠正

用户要求安装只验证成功或明确失败。停止并撤销额外暂停/恢复测试；模拟器网络已恢复full。额外Wine8.18下载在3767618/70962032字节处因unexpected end of stream明确失败，未执行暂停。撤销版APK本地构建成功并部署，保留原日志和自动滚动。

## PRoot XFCE

trixie全新网络安装exit0。显式LLVMpipe/1280×720/30Hz启动失败：Xserver报告缺少EGL AHB/dmabuf导入扩展，无自动回退；该模式未通过。通过设置单独选择System EGL后启动成功，日志renderer与参数匹配。Thunar Home、容器终端实际操作及继承Pulse播放tone通过；Android44100Hz音轨active、underrun0。随后正常停止桌面，正在验证归档。

## 桌面归档正式验收

XFCE通过桌面菜单导出到Download/FoldUIValidation，再通过导入卡片恢复成功。预检验证5项固定版本宿主组件且无需网络；本轮未测试缺失组件时的实际安装分支。新容器ID/alias独立，System EGL/1280×720/30Hz、Direct touch均保留；恢复后实际打开Thunar成功。验证后通过UI删除原容器与恢复测试副本，保留归档。预检会话框发现累计日志重复追加，已改为替换最新尾部。11月5日当前APK经正式Import卡片重新导入；进入解包后从卡片菜单打开会话框，5项宿主组件日志各显示一次，任务日志逐项计数也均为1。完整本轮解包仍在运行，未提前记作导入完成。

## PRoot Plasma 安装修复

forky正式UI重试安装exit0，dpkg --audit为空，原失败10包全部installed，原始postinst SHA未变。最终适配库使用UCF_TERMUX_PROOT_STATX_MODE=proc-fdinfo，在6022交叉编译，SHA cc47aae3d46589bda4138b1fee328476d3beb51b5b56865731826b633ab8b997；真实ucf/ucfr及statx回归通过。显式LLVMpipe/1280×720/30Hz启动，Dolphin、Konsole、Direct touch及Pulse/Android音轨通过；SHELL已明确为/bin/bash。移除定时模拟鼠标后首屏自行显示，关闭视图再打开仍能重绘。首屏初始化延迟明显，Stop的KWin析构SIGSEGV待修复。

## Plasma 会话环境与系统进程限制

正式UI已显示Plasma桌面、面板、Dolphin与Konsole。Konsole收到空SHELL而提示自行使用bash，启动脚本现明确设置并检查/bin/bash；合同回归与APK本地构建通过，已部署。

Android events记录11:22:22以Trimming phantom processes杀掉监督sh6869，11:37:23杀掉Pulse6891和启动bash6916；KWin/Proot成为孤儿进程，UI按监督进程消失报退出。音频尚未通过，不能判为Plasma正常退出。已记录原系统设置为null，临时设置settings_enable_monitor_phantom_procs=false继续复测，结束需delete恢复。此项为系统限制，未声称代码修复。

11月5日11:46更新APK后再次焦点ANR。后续JDWP主线程处于MessageQueue.nativePollOnce，工作池均空闲，未观察到Java锁等待；此为提示出现后的采样，不能还原超时瞬间。已保存plasma-shell-jdwp.txt与lastanr原因，未修改无证据的启动代码。

重启Fold期间12:12:29 system_server在WifiHandlerThread抛出Could not fetch IpMemoryStore / DeadObjectException并重启，随后12:13恢复启动；系统崩溃日志已留存fold-postreboot-system-crash.txt。该系统故障不作为App修复通过。

## 后续显示与输入诊断

候选APK f974bd9d修正新Surface/共享状态绑定时沿用旧waitForNextFrame的问题，四ABI本地构建通过。正式UI启动后40秒及再等35秒为黑屏，继续等待时无额外输入自行显示壁纸，随后出现面板；因此首屏同时有明显容器初始化延迟，尚不把该候选改动记作全部黑屏问题通过。

Direct touch初次点击后窗口未立即出现，早先误判为未触发；输入追踪确认18→19→20及坐标193,689正确，随后Dolphin实际打开，触控通过。Simulated touch screen同一位置亦打开成功。未修改输入协议或自动回退。Konsole通过Dolphin Open Terminal实际打开，空SHELL警告消失（plasma-explicit-shell-konsole.png），显式Shell修复正式UI通过。

正式Stop曾在KWin进程析构__cxa_finalize路径触发SIGSEGV；kwin-ui-stop-crash.txt已保全，当前未修复，不能把停止过程判作无崩溃。

Proot Plasma继承Pulse端点并通过真实Konsole执行测试音，AUDIO_EXIT=0；Pulse RUNNING、Corked=no、Mute=no，Android对应Pulse PID11032的44100Hz音轨active、underrun0。证据proot-plasma-ui-tone-*、plasma-audio-exit0.png。

已删除DesktopSessionActivity中启动/焦点/抽屉/旋转的定时模拟鼠标nudge补救，只由renderer新Surface/共享状态绑定路径请求缓存重绘；最新5b1c9d8d APK构建并安装。首屏无额外输入自行出现壁纸及面板；正式UI Direct touch打开Dolphin通过。

无nudge版5b1c9d8d正式UI重绑复测通过：关闭视图保留桌面进程，再Open，Dolphin和已展开菜单正常显示；缓存重绘不依赖模拟鼠标。证据no-nudge-plasma-rebound.png。

## 本轮续测状态

7022本次收到OpenSSH banner后在密钥交换阶段断开；显式curve25519诊断再次于认证前返回Not allowed at this time。KWin Stop候选deb仍未构建，未改为在6022编译Bionic包。

已通过UI删除本轮Proot Plasma条目及debian-kwin-plasma-1容器，日志已保存；可用空间5.2GB。Proot GNOME trixie / LLVMpipe / 1280×720 / 30Hz已从正式UI开始安装。

本轮Plasma后半段在临时关闭模拟器phantom进程裁剪后验证，不能据此宣称Android默认进程限制已解决；最终必须恢复该设置。KWin输入追踪临时标记已删除。

## GNOME 安装结果与 Pixel 7 对照

Proot GNOME trixie正式UI全新安装537包，exit0，dpkg --audit为空；实际Mesa25.0.7/LLVM19.1.7 llvmpipe。固定GL3会话显示顶部条和灰背景后首帧停滞，不能记作交互通过。用户要求复查Pixel7后，在原APK/同容器/同参数下，以原r7、临时r8、临时仅屏蔽swap-event能力完成对照：r7固定GL3无可用driver，r8同样首帧停；仅撤去虚报能力后概览与Files /usr恢复。非Fold独有，正式候选deb仍待离线编译机恢复。详见gnome-usb.md复查记录。

## 11 月 5 日复查收尾状态

Pixel7同机对照已结束，原r7二进制SHA/inode、旧GNOME工作区和原容器恢复，临时条目/`.drirc`已删除；本轮启动的Pixel7已关闭。Fold GNOME已通过Exit → Stop desktop停止；未执行的两个音频夹具按本机源逐字节核对后删除，不能算音频测试通过。

当前APK的桌面导入日志去重回归已从正式Import卡片启动，任务`4cb10d74-1b93-421b-8768-9f4063267bb5`已完成校验及5项组件预检，正式会话框显示无重复，正解包到新容器。日志去重已验收，本轮完整导入仍待完成。证据`desktop-import-dedupe-ui.png`、`archive-dedupe-log-counts.json`。任务结束前不恢复phantom限制或退出App；临时系统设置仍按前述清单待恢复。

## 11 月 5 日晚：编译机恢复后的候选验证

以下记录覆盖前文“编译机离线”“导入仍在运行”和“临时设置待恢复”的历史状态。

7022主机密钥验证后恢复连接；按原脚本恢复API24 NDK fuse-overlayfs挂载。Xserver在r8配置目录的隔离副本编译；KWin按原recipe编译并以DESTDIR隔离收集。两个候选均以生产底包仅替换对应二进制，同版本只用于测试，未推送或发布；现有公开下载pin未更新。

Xserver候选Xlorie SHA75f2d7be9414acbfed8a19855b556f115a0f230d1ea86617e0c9cba3eb9717df。Fold的GL3/LLVMpipe概览、应用网格、Files /usr、Direct touch与音频通过；音频在GNOME应用网格启动测试项，Pulse RUNNING/Corked no/Mute no，Android44100Hz音轨active、underrun0。触控恢复为原Simulated touch screen，测试音频文件按内容核对后删除。

KWin候选deb SHA2557993693235684c332b61b4c7426ac2ea028a363dbe10b19410d120e879511。Native System EGL启动并映射候选libkwin后，从App Exit → Stop desktop停止，PID6548消失、状态stopped；无原析构SIGSEGV，Android crash buffer为空。此轮未重建已删除的PRoot Plasma容器，不替代其LLVMpipe Stop回归。

用户要求先更新小米App后验证：已手动将旧files/games改名为files/front，并以adb install -r安装本地最新ARM64 APK；小米/Fold/本机APK SHA均为5b1c9d8d9140f0fea6372026a61ad9b15f39035a9088869a9454370e68a85e32。小米新版App再安装同一Xserver候选，保持原GNOME #1 / Zink/gl3 / 1280×720@30 / zink-dmabuf / Direct touch / 竖屏参数。实际Turnip Adreno660、Accelerated yes，Files /usr/bin与时钟持续更新通过；无guest .drirc。

归档任务4cb10d74-1b93-421b-8768-9f4063267bb5于16:15完成SUCCEEDED；5项预检各一次。恢复的XFCE桌面显示，验证后从UI删除本轮容器副本；保留归档。Fold已通过UI退出，同UID无进程；独立API/X11恢复default、stay_on_while_plugged_in=1、phantom开关恢复null、AC off。后半段图形测试仍是在临时关闭phantom限制期间完成，恢复设置不能替代默认限制下的验收。

完整候选证据：xserver-swap-fix/COMPILED-CANDIDATE-RESULT.json、kwin-stop-fix/native-stop-cycle1.json、temporary-settings-restored-20261105.json；根目录仍为/tmp/fold-ui-clean-20261104-142515。

小米测试结束后已从新版App正式停止GNOME，状态stopped且无活动GNOME/Xlorie；原Direct touch/竖屏设置未改。保留新版APK及已验Xserver候选。临时原Xlorie副本和UI dump按设备SHA核对后清理；公开原r8恢复包仍保存在本机。


## 11 月 6 日：正式资料包发布

Xserver r9 与 KWin r28 已公开并设为 latest，App 本地 pin 已同步。全部 39 项发布资产服务端 SHA 与 14 项 App 公开下载均校验通过；正式包的程序二进制与本轮已验候选一致。旧 release 保留，先前候选验证边界不变；详见 [发布记录](release-xserver-r9-kwin-r28-20261106.md)。
