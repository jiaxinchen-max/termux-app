# App v4 脚本契约复审

本轮只修改已确认的脚本/设置错误，编译均在指定 builder，未在设备手改脚本或执行容器删除。

- 容器删除只接受显式 `DESKTOP_MODE=proot`、`DESKTOP_REMOVE_METHOD=proot-distro` 和合法 alias；仅执行 `proot-distro remove <alias>`，保留 stdout/stderr 与原退出码，不尝试 rm。缺 spec/未知方法退出64，缺工具127。
- 实际小米 proot-distro 5.10.3 的只读 help 证实旧 `uninstall` 是 unknown command，正式命令为 remove，不带 -y。旧代码失败后回退 rm 正是被移除的路径。
- App 原流程先删除桌面记录/spec，再发起容器移除；removeContainer 现在通过 deploy 重新生成包含明确方法的 spec，避免缺 spec 被当成成功。
- 已有桌面 PID 时启动脚本退出73，保持现会话，不复用无法核验的参数、不杀未知进程。正常 native KWin 内部已具备存活/socket/ready marker/实际 Qt renderer 门禁；其旧 existing-process 入口由独立 KWin v4 修复，App不重复实现正常启动门禁。
- 运行/starting 时拒绝保存 renderer、分辨率、刷新率变化并提示先停止；名称及不变参数仍可保存，触控/方向即时设置不受影响。保存 IO 错误记录日志并提示，成功回调仅在实际保存成功后执行。
- 会话必须显式选择 `DESKTOP_KDE_SERVICE_POLICY=kwin-wayland`、`QT_QPA_PLATFORM=wayland`、`XDG_SESSION_TYPE=wayland`；缺失/未知/冲突值退出64。启动集只含当前实际需要的 kactivitymanagerd 和 kded6；其缺失、提前退出、cache后死亡门禁保留。kbuildsycoca6 原 stderr、缺工具127、失败原码门禁不变。
- standalone kglobalacceld 不进入 Wayland 启动集。Debian 6.7.4 源码在任何 Qt 初始化前检查 XDG_SESSION_TYPE=wayland 并直接return0，注释明确由kwin_wayland负责快捷键；这不是失败后的回退。原生KWin与容器客户端使用不同私有bus，且compositor带--no-global-shortcuts，日志明确全局快捷键不可用，保留实际ServiceUnknown诊断。

## 验证

- 远端 unit tests：termux-ui 467、termux-x11 105，零失败；新增5个纯设置契约覆盖 active renderer、starting resolution、active refresh 拒绝，以及重命名/原参数/停止或失败后修改允许。
- desktop scripts 契约覆盖：已有 PID 返回73且保持进程；删除原码42与诊断保留、无重试/无 rm、实际 remove 命令格式、缺工具127、5类无效输入64；必需 KDE 服务非零/零退出、缺失、cache退出41均在 Plasma 前停止。v4新增4类无效服务策略64、完整成功路径不调用standalone kglobalacceld（即使提供了会返回0的程序），并明确打印全局快捷键限制。
- 主线程模拟器v3正式UI已通过真实Debian容器勾选删除、运行时改renderer拒绝且System卡片不变；这两条代码v4未改。v3容器启动在kglobalacceld设计性退出0处被误判失败，不能记为成功；v4容器Software/Zink及Discover正式UI回归由主线程继续，本报告不把脚本/单测当正式验收。
- README 的“APT依赖损坏只提示x11-repo”已修复，可勾选：desktop_install.sh run() 回显失败命令和原退出码，再展示 E:/Depends/broken 等原诊断；契约18用 `E: Unmet dependencies: libtest Depends: libmissing (= 2)` 与退出100核验，安装不误报完成。外层安装脚本统一返回1，但原APT退出100保留在终端和日志中。

Discover 的 Vulkan 被 QtWebView main 强制 OpenGL 问题由 Xserver 代理独立处理；App v3不添加 Discover Software 覆盖或基于失败的渲染切换。

## kglobalacceld 来源核验

Debian官方 `kglobalacceld_6.7.4-1.dsc` 对应 orig.tar.xz SHA256为 `538f883e7b04397d0c5b1756e750117022fe6a03a43ba890f65d62e2cf45e783`，debian.tar.xz为 `e97b58ce7b661c98320b01fac322646e99a9034b7657215ab743e115ab1bb662`；已逐包核验，Debian没有patches。`src/main.cpp` 明确注释“On Wayland the shortcuts are ran as part of kwin_wayland / no-op when started on Wayland”，对应 `if (qEnvironmentVariable("XDG_SESSION_TYPE") == QLatin1String("wayland")) { return 0; }`。源归档保存在编译机 `kglobalacceld-source-audit`，设备未修改。v3把依赖包误认成所有会话都必须常驻，v4改为在启动之前用明确会话策略选程序，未尝试启动后吞掉退出码。

## App v5 设置页横屏滚动

小米v4正式UI发现设置BottomSheet内容高于横屏窗口，LinearLayout无滚动能力，展开后Save/Cancel仍在屏外。v5只将设置内容包入NestedScrollView并初始STATE_EXPANDED/skipCollapsed，Save/Cancel改为wrap_content加50dp最小高度，让大字体可增长。所有脚本、native库、运行保护及删除逻辑与v4逐字保持。远端资源编译与既有单测完成，编译APK的xmltree可核验滚动根和按钮高度；未新增截图测试、未操作设备，正式横屏滚动/保存拒绝验收由主线程执行。
