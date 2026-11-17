# Wine Ubuntu 26.04 迁移实施中

此前桌面与桥接器修复已推送并核实远端 `572b115151160c509b6d27a86d9278c2a4ca8d48`。用户已批准迁移实施并明确不考虑历史数据兼容；保持共享根与现有安装事务，APK 原名不变。

官方 Ubuntu 26.04 ARM64 隔离构建容器已经正常 APT 安装完成。Hangover 适配只重建同源码的 winedmo 模块；设备重建包与主机候选的 3933 个 payload 条目完全一致。当前未裁剪、未发布新镜像，也未构建或安装本轮 APK。

真实 Termux 应用进程中，隔离 Hangover PE64/PE32 的窗口、中文、Unicode 文件和 WinMM 测试通道均通过，命令与 wineserver 停止返回零，该通过只适用于 Hangover，不能替代普通 Wine/Box64 或 Proton/Box64 的资格。run-as 的 SELinux 域不同，先前 noexec 失败不能代表实际应用环境；测试补齐 /sys 后 FEX 断言未复现，产品脚本原本已有 /sys。

用户明确可选外设不属必装。未为扫描仪、相机、智能卡、CAPI/ISDN 补包；全模块扫描的失败报告保留，不能声称全模块闭包已通过。视频解码依赖、外置 Wine ABI、模板资格与最终镜像仍待完成。

HTTP 合约 N/A；产品一致性 Pending。Harness bootstrap、strict 脚本、OpenSpec CLI 和产品基线缺失，不声称严格门禁通过。迁移提交/推送、Release、APK 安装、验收和归档尚未授权。

2026-11-17 用户再次明确运行架构边界。重新核对实际组件 ELF 及定制 Wine 源码：Hangover wine/wineserver 为 AArch64，HODLL/HODLL64 指向内部 PE 翻译模块；Wine 10/Proton wine/wineserver 为 x86-64，经外部 Box64 执行。产品启动分支已区分，没有把普通 Wine 直接当 ARM64 运行。隔离 qualification 脚本原来向三类测试统一导出 Hangover 的 HODLL/HODLL64，现仅在 Hangover 分支导出，旧诊断脚本保留原样，不能推定此测试环境问题就是退出 137 根因。实际架构与摘要证据位于 /home/user/workspace/artifacts/ubuntu-wine-rootfs/device-evidence/architecture-boundary.json。

已进一步筛选 diagnose-cleanup.log：主 Wine PID 31973 调用 exit(0)，随后 Box64 执行 fini；wineserver PID 31977 在清理测试之前调用 kill(31973, SIGKILL)，其后才出现 cmd exit=137 和用于 stop -k 的新 PID 32206。因此这次 SIGKILL 来源已确定为 wineserver，而 Box64 动态编译的退出清理未及时完成的具体原因仍待定位；不能声称已修复。筛选原文保存在 artifacts/ubuntu-wine-rootfs/device-evidence/diagnose-cleanup-main-exit.txt。

移除 Hangover 专属环境变量后，实际 Termux 应用上下文中的独立 diagnose-boundary 测试已结束：普通 Wine 10 仍显式通过 Box64 动态编译启动，BOX64_LOG=0、WINEDEBUG=-all，cmd.exe /c exit 0 仍返回 137，wrapper 如实返回失败 1。不能把之前 HODLL/HODLL64 的测试边界问题当作该故障根因，也不能把 wrapper 失败改记为通过。qualify-guest.sh 的分支隔离修改已通过 sh -n，设备文件 SHA 与主机一致；旧 qualification/diagnose 证据未改写。最新结果：artifacts/ubuntu-wine-rootfs/device-evidence/diagnose-boundary-result.txt。

后续缓存实验尚未确定根因。单次 BOX64_DYNACACHE=0 成功，但同一前缀和 HOME 的关闭／开启／再次关闭三组对照均正确返回 0 和 37，每组都有 GUEST_COMPLETE，wrapper 均为 0。随后原始未设置 DYNACACHE 的参数也返回 0。因此不能声称关闭缓存修复了 137，生产启动配置未据此改动。原始证据分别保存在 device-evidence/diagnose-nocache-result.txt、diagnose-cache-aba-result.txt、diagnose-default-repeat-result.txt；默认复跑的 guest 原样保留 exit，末尾追加的 marker 不可达，不能将其作为完整 smoke 验收。全新前缀与 HOME 的默认参数命令及 PE32/PE64 测试已另行准备。

diagnose-fini 的高日志量探针人为停止，状态为 Incomplete/ManualStopped，不采信其 wrapper 零值；diagnose-cache 的默认组首个 cmd 返回 137，随后超时 124，没有执行关闭缓存组，不能用于成对结论。原始大日志保留在设备，不整份下载。

同版本未修改上游 Box64 v0.4.4 诊断构建已完成，源码提交为 2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a，未 strip 二进制 SHA256 为 24de48dfd833b5a16ded8e124b3b903b9a02cbc35b3fc076f4670cf4aa92ba9c；仅为 BuiltNotRuntimeQualified，未替换所选组件，构建证据见 artifacts/ubuntu-wine-rootfs/cross-build/box64-unmodified-build.json。

FFmpeg 4 兼容库已完成设备传输与整包核验，5716632 字节，SHA256 c48cefe8912df623b4f597ec5fc5b989dcd5cce19a544150db1098389a8f8842。尚未集成根镜像，codec probe 和 Wine 媒体集成均未据此记为通过。

Proton 两项来源变化已审查：drive_c/windows/system32/joy.cpl 与 syswow64/joy.cpl。重资格工具逐成员核对锁定 Proton 组件原包后复制实际文件；仓库 distribution/termux-app-packages/proton-joy-cpl/README.md 记录了既有 AUTOCENTER 修复。不是字体变化，不忽略哈希差异。完整来源证据另存 device-evidence/proton-source-verification.json，真实运行资格仍 Pending。

用户提供了往期 Wine 编译机供只读排查。OpenSSH 和 Paramiko 多次在 SSH banner/认证前被关闭；另经既有 Android 设备的 TCP 转接测试仍超时。未进入密码认证、未登录、未读取任何远端归档或补丁；不能声称已看过历史构建来源。口令不进入文件、证据或提交。网络不稳定，继续间隔重试，同时开展独立隔离验证。

全新 Wine10 前缀与空 HOME、原组件 JIT=1、未设置 DYNACACHE 的实际 App 对照已完整结束：首次 cmd /c exit 0 返回 137，随后的 exit 37 正确返回 37；PE64/PE32 的 Unicode 文件、窗口、中文 glyph 与 WinMM null sink 检查均为 failures=0，但两个进程仍各自返回 137。GUEST_COMPLETE status=1、wrapper=1，整体失败，不能用 JSON 功能通过覆盖退出失败。日志及结果见 device-evidence/diagnose-fresh-default-default-fresh.log、fresh-wine10-{64,32}.json。此前失败和后续暖状态成功同时保留；尚未完成 Debian 的同条件对照，不能断言迁移才引入该现象。

已只读核对锁定 Wine10 实际 ntdll.so 的 process_exit_wrapper：先 call close(fd_socket)，再 call exit(status)；同一组件的 wineserver 存在 process_sigkill 定时强杀分支。保存实际二进制反汇编与摘要于 device-evidence/wine10-exit-binary-proof.txt，不再只依赖上游参考源码。具体 Box64 退出耗时位置及最终修复仍 Pending，未修改原始 Wine 二进制或扩大超时掩盖失败。

FFmpeg 4 独立 stage 的原生 WMV2 编码、原生解码及 x86_64 probe 经所选 Box64 动态编译和 native(wrapped) libavutil.so.56/libavcodec.so.58/libavformat.so.58 解码全部完成，guest marker 存在、wrapper=0，两路解码 checksum 均为 4180745012、failures=0。证据为 device-evidence/media-probe.log、media-probe.exit 与 media-{encode-native,decode-native,decode-box64}.json。没有写入候选根，资格仅为 codec smoke 通过；Wine 媒体集成仍 Pending。

同版本 Box64 对照因传输超时未能运行。大 stdin 传输、adb push 和分块尝试均未形成完整设备诊断二进制；未解包不完整 gzip、未替换正式 Box64、未将其记为运行通过。超时文件写入进程按私有诊断路径匹配并停止，ADB 连接重建；存在的 .part 仅作未完成传输保留。沙箱 HTTP 制品代理返回 401，未绕过认证。

另外启动因果定位用 original-before / defer-close / original-after 三组时序探针，正在执行。仅复制锁定 Wine10 的 ntdll.so 到私有 diagnostic 目录，以原 SHA256 和 ELF64 program headers 定位，再核对 process_exit_wrapper 的完整 21 字节，复制品中唯一 5 字节 close(fd_socket) call 改为 NOP，保留原 exit(status)。仅中间组通过 PRoot 文件 bind 使用副本，正式组件及前缀模板不变。目标是区分提前关闭 socket 触发的 wineserver 计时与最终 Box64 清理；这是 DiagnosticOnlyNotShippable 的二进制探针，不是可发布修复，也不取代源码来源核实。源摘要 4f9a80ebdff95c5497b5ddc600ac42ec6aa10a4dfc5448d3a94989c38267ab17，副本摘要 da9689eef9da5a855c1b76d7898046e1f681e01d98259d7127e5c51b33b5e8a9，证明及准备脚本位于 artifacts/ubuntu-wine-rootfs/device-evidence/ntdll-defer-close.json 与 diagnose-defer-close-prepare.py。三组均保持 JIT=1、未设置 DYNACACHE、同一测试前缀与 HOME；每组验证退出 0、37 和 PE64，先停止并等待 wineserver 再进入下一组。结果未读回前均为 Pending，不能宣称修好。

退出时序三组现已完整结束：original-before 的 cmd exit 0 和 PE64 返回 137，exit 37 正确；defer-close 三项均正确；original-after 三项也均正确，三组均有 GUEST_COMPLETE。因此延迟关闭 socket 未形成可复现的因果修复证据，后续暖状态影响仍未排除；不得发布此二进制探针。中间组成功不能单独支持修复结论。original-after 的 wineserver 清理还输出了对象引用信息，原文完整保留，不推定其无害。所有组和 wrapper 结果、功能 JSON、原组件 SHA 再核验及证据摘要见 device-evidence/exit-timing-comparison.json。最终确认隔离 guest/proot/wineserver 无残留进程，原 ntdll SHA 不变；Proton 和同版本参考 Box64 的脚本仅准备，未运行。

用户提醒退出策略后核对正式界面：GameSessionActivity.stopGameAfterDrawerHidden() 明确调用 orchestrator.cancel(taskId, true)，LocalGameOrchestratorService.cancel(force=true) 进入 host.forceStopRuntimeProcesses() 并记录 force_cancelled；TermuxLocalGamesHostFactory 使用 RuntimeProcessKiller 先 SIGSTOP 后 SIGKILL 清理所属进程树。因此点击界面退出 Wine 的原始进程状态可以是 137，是主动强制停止路径，不能仅凭 137 判定退出缺陷。此前关于 137 的影响解释未先区分主动退出和自然退出，结论过于绝对。自然退出的 start_rootfs_game.sh/GAME_SESSION_SCRIPT 先 wait 主命令取得 rc，再 wineserver -k/-w，原样回传 rc；另有 TERM 后 KILL 和 PRoot --kill-on-exit 的兜底。隔离失败样本中未使用界面退出或产品 stop_owned_session，之前 syscall 证据显示 wineserver 在测试后续 stop -k 之前发 SIGKILL，故该样本仍需单独排查，不能归因于点击界面退出，也不能将主动取消误记为产品失败。
