# 2026-10-30 桌面修复与验证

本文记录发布前的 v6 验收：分支 `master-x11-submodule-tmp`，基线 `280cbe79`。所有编译在指定 `builder@BUILD_HOST:7022` 执行，本机未编译。验收时尚未提交、推送或发布；后续 r27/r7 发布及 App pins 检查见 [发布记录](release-20261030.md)。原有 dirty cpp 子模块保留。

## 仓库与模式契约

- Xserver：`https://github.com/jiaxinchen-max/xserver`，`termuxdc`，r6 基线 `b08f1752300f3d594694e09f5d8f980978becc4a`。
- KWin：`https://github.com/jiaxinchen-max/kwin`，`Plasma/6.6`，r26 基线 `ff4c62953913ad11f0dedf9658bc8bde52ac3fa5`。
- 显式选择 System / Zink / Software，缺失、旧 Auto、未知参数和实际 renderer 不匹配均明确失败；不按失败更换 provider、驱动或软件模式。已有进程冲突返回73，保留既有会话。
- 原生 XFCE 明确启用 GLX；容器 XFCE 明确禁用 GLX。显示 socket、进程及后端就绪后才启动客户端。

## 交付产物

| 组件 | 离线产物 | SHA256 |
| --- | --- | --- |
| App v6（两设备正式主路径已验） | `termux-desktop-standard-statx-final-v6.apk` | `3953f740baba81303c97bc2dba2a57267d8febed480e9a9c5fd61574e26c8adc` |
| KWin / Qt v4 | `kwin-qt612-native-fixes-20261030-aarch64-v4.tar.gz` | `c358738df64b81e8abe8d4aa57b74978f2959e23f9061389f6d63f1371b2daf8` |
| Xserver v8 | `xserver-r6-local-fixes-arm64-v8.tar.gz` | `6c2382c0be545aeb16d6283ebd49ef67d7abc4660a1df99d482492caf8b9a7a8` |
| Discover fix4 | `discover-6.7.4-3+termuxfix4-arm64.tar.gz` | `6b2bf0284adf46f911eb250be232468ccfa7210e623ae07a2dfd17f711472a32` |
| MultiTouch | `mt.jar` | `77334e9f5ae344df36f9131b0416151f8e832e4ec0fb99dada116ef753ec1751` |

持久目录：`/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/`。组件包包含可审查补丁、源码或来源记录、构建及验证证据。旧候选包用于保留诊断过程，不等于最终验收版本。

## 修复内容

- Xserver：readback/Present/资源错误与精度处理；Adreno 花白根因为 A8 picture 临时纹理使用 unsized GL_RED，固定 GL_R8 并保留 GL 错误退出。真实 GTK 梯形请求三组各8次，与 pixman 四通道逐像素一致。v7 highp 修复独立渐变精度，但未解决花白，不能当作花白修复结果。见 [v8 记录](xserver-v8-a8-picture-20261030.md)。
- KWin / Qt：修复跨插件 RTTI 导致 popup fatal、旧 output-mode 协议声明及触摸时间戳；原生 System 客户端通过 Android AHB 与私有 EGL namespace 使用真实 GPU；D-Bus 激活环境与显式模式一致。v4仅修已有进程未经核验就返回成功的入口，二进制与v3逐字节一致。见 [组件记录](patches/kwin-qt612-native-fixes.md)。
- App：去掉 Auto、严格驱动和实际 renderer、保留失败码及主动 stopped/0；修复多触点取消/模式切换清理和 v120 滚轮。运行中拒绝保存 renderer/分辨率/刷新率变化，触控与方向即时设置保留。删除容器仅执行显式 `proot-distro remove`，不回退 rm，并重新生成被记录删除流程移除的 spec。APT 保留真实依赖诊断。v5将设置页改为可滚动、初始展开，横屏可到达Save/Cancel。见 [脚本复审](app-script-review-20261030.md)。
- 容器标准工具：保留原 systemd 工具，通过显式 proc-fdinfo 获取同一 FD 的真实 mount ID；只在 APT/dpkg 配置子进程加载适配库，不向 Qt/renderer 注入。对确切历史 no-op postinst，用签名 APT 同版本重装并逐字恢复；不升级、删除或改成新 no-op。私有 system/session bus 与真实 PolicyKit/PackageKit 就绪检查保留。小米另发现 session socket 的 PRoot 临时路径丢失；v6 将 session bus 显式固定到现有 runtime bind，启动后只对返回地址执行一次有超时的 Peer.Ping，失败保留原状态；远端构建及契约通过，模拟器 Software 与小米 Zink 的 forky Plasma 正式 App 启动及 Discover 主路径均通过。见 [标准工具记录](polkit-proot-sysusers-20261030.md)及 [session bus 生命周期](dbus-session-lifecycle-20261030.md)。
- Discover：保留真实 reachability 异步状态修复；fix2修复 QtWebView 入口无条件设置 OpenGL、覆盖 Vulkan 参数的实际问题。严格解析显式后端，未知/冲突退出64；不通过独立 Software 覆盖或外部浏览器替代。fix3修复fwupd GetDevices错误未结束初始化，保留原始错误并收尾fetching/initialized；不以空设备结果冒充成功。fix4 在成功 RefreshCache 后重载同一 AppStream 池，串行化加载与查询，合并重叠刷新，按代次阻止旧流投递并更新资源索引；本地 deb 独立于目录加载，失败保留原始错误且不重试或切换后端。见 [显式后端记录](discover-explicit-renderer-20261030.md)、[fwupd与AppStream记录](discover-fwupd-state-appstream-20261030.md)及 [fix4 机制与最终 ELF 验证](discover-packagekit-refresh-20261030.md)。

## 正式 App UI 已验证

设备：本地 `emulator-5554`，Android15 arm64，先清理游戏，约17GB空闲；小米 `XIAOMI_SERIAL`，Mi11Ultra / Android14 / Adreno660，通过 `BUILD_HOST:8037`，约165GB空闲。业务均从 App 选择参数、启动及操作；ADB仅做UI输入、截图、安装产物与只读诊断。截图经过人工查看，不以动作exit0或像素差异替代语义验收。

| 功能 | 模拟器 | 小米 |
| --- | --- | --- |
| Xserver v8，XFCE System | 容器 `/usr/bin` 菜单通过；前序同修复原生实际 Termux bin 菜单/滚动通过 | 原生实际 Termux bin 和容器 `/usr/bin` 菜单、双指滚动通过；容器 Directtouch 导航/单指滚动通过，地址栏与任务按钮花白消失 |
| 原生 Plasma System | App v5 + KWin v4 连续双指右键通过；原生 System 实际 Qt Android Emulator GPU；Directtouch Dolphin 导航及方向前序通过 | 实际 compositor 和 Qt client 均为 Adreno660；Directtouch 系统设置滚动/点击、About GPU、竖横切换通过；最终App v5 + KWin v4重复右键再次通过 |
| 运行中设置保护 | v5横屏滚动到Save/Cancel，尝试System→Software明确提示先停止，卡片保持System/Running | v5横屏滚动按钮、保存拒绝及卡片保持System/Running通过；提示另由logcat核对 |
| 容器删除 | App v3 创建 `debian-xfce4-2` 自有记录，CLI准备真实Debian trixie基础容器后，从App勾选删除；记录和rootfs均移除，原有容器保留 | 未删除用户容器 |
| Plasma 容器与 Discover | App v6 + fix4，forky / Software：固定 session bus 启动、Home 有目录、KCalc 搜索 1 条、安装至 Launch、Launch 真实窗口、2+3=5、卸载至 Install、App Stop 后 Ready 均人工看图通过。启动自动刷新 503；未记录手工 Refresh 成功。v5/fix4 干净会话前序通过 | App v6 + fix4，forky / Zink：固定 session bus 启动、Home 有目录、Updates 手工 Refresh 返回原有 3 条待更新（未点 Update All）、同 Pool 成功重载 2471 / fetching 0；KCalc 搜索 1 条、安装 Launch、真实窗口 2+3=5、卸载 Install 通过，App Stop 后两卡片 Ready。侧栏手势/旋转为前序 v5 结果 |

小米历史15包已经由生产App启动路径完成真实同版本恢复，`legacy-postinst-verification.txt`含15条原脚本SHA和original_verified。polkitd实际降权，PackageKit就绪，Zink桌面成功显示。模拟器没有这15个历史no-op，完成的是自有旧diversion退役和标准工具启动，不混淆两种覆盖。

## 独立检查及已拦下问题

- App：前序远端467项UI模块单测与105项X11单测，572项零失败；显式模式、错误码、设置保护、删除、服务策略等脚本契约通过。v6 远端构建和 session bus 契约通过；与 v5 的 APK 比较仅 session 脚本变化，其余 63 个 assets、5 个 native 文件及 17 个 DEX 完全一致。
- 标准工具：独立真实rootfs 15包恢复、audit为空、幂等及无父环境preload泄漏通过。v2增加真实APT Pre-Install-Pkgs子进程检查，确认wrapper之前已为noninteractive，父dialog不变；旧候选出现debconf回退的日志保留，不声称其没有回退。
- 测试机stock proot5.4.0的readlinkat空路径断言独立修复，仅用于隔离runner；生产两设备proot未替换，设备最小语义probe通过且探针已清理。见 [runner记录](proot-runner-readlinkat-20261030.md)。
- Xserver：故障注入、ASan/UBSan、资源语义及r6八补丁重放通过；KWin/Qt：renderer guard、跨插件RTTI、协议及真实socket冲突契约通过。
- Discover fix4：最终 deb 九组参数矩阵、三组真实 QML 认证门控、ELF 内嵌 QML 与导出源码逐字核对通过；真实 PackageKitBackend + AppStream + 私有 D-Bus 对照复现旧池缺结果，最终 ELF 覆盖成功刷新、变更/删除、同包多应用去重、泛型包、本地 deb、重叠加载与错误收尾。真实fwupd plugin对照稳定复现旧版42%挂起，新版两组各五轮查询、错误、无设备均正确结束，保留原错误。首个因debhelper缓存未重编的包被最终binary检查拒绝，未用于设备验收。
- App v3把设计上Wayland直接return0的standalone kglobalacceld误当必需常驻服务，正式模拟器UI启动失败；v4根据Debian源码选择明确kwin-wayland服务策略，在启动前排除该程序，保留kactivitymanagerd/kded6/cache失败门禁，已在模拟器Software及小米Zink正式UI确认。
- MultiTouch runner远端编译、5项脚本测试通过；报告保留 `semantic_verdict=not_assessed`，业务判定另据截图与界面结果。

## 实际限制与未验项目

- Vulkan/Software不支持Flatpak嵌入网页认证，Discover明确提示并取消；不影响PackageKit主路径，视频预览不在该门控内。未验真实OpenGL网页认证。
- 当前原生compositor带`--no-global-shortcuts`，与容器分属私有session bus，全局快捷键不可用；不把ServiceUnknown当成已支持。
- fwupd的root warning、无设备和LVFS网络错误、Debian AppStream图标HTTP503保持真实展示；未以删除backend、假online或隐藏错误处理。
- 真实断网UI未覆盖：模拟器关Wi-Fi/data后仍存在default network，不能记为断网通过；两项已恢复1/1。
- 容器支持范围固定为 XFCE4 / Debian trixie、Plasma / Debian forky。forky XFCE4 与 trixie Plasma 已确认不兼容，不属于修复或验收目标。
- 旧远端tag r19–r23未清理；未发布新版本，线上下载URL未改。

## PackageKit 缺结果机制及正式 UI 已验证，历史实例证据边界保留

小米旧 fix2 实例 Home 无应用、Installed 仅两个 system 资源。固定当前输入、默认路径、精确 fix2/fix3 Common 的 ABBA 对照均返回 2471 组件、distro1、KCalc1，生产输入 SHA/mtime 前后不变；旧实例完整初始 raw metadata 与精确首次加载时刻缺失，不能精确重放原实例或把重启恢复当成修复。

独立真实 Backend 对照已复现“初始空池 → metadata 发布 → RefreshCache 成功 → 同一实例仍查旧池”的机制，fix4 同一实例可见新组件，最终 ELF 回归通过。模拟器及小米 v6/fix4 正式主路径 UI 均通过；模拟器启动自动刷新返回 503，手工 Refresh 未记通过，小米 Updates 手工 Refresh 则有成功事务、同 Pool 重载 2471 组件及 fetching 归零的完整链。旧 fix2 原实例仍因 raw 输入缺失无法精确重放，不把新机制复现及新版本 UI 通过混作历史原实例完整根因证明。见 [历史对照](discover-fix2-fix3-controlled-baseline.md)及 [fix4 机制验证](discover-packagekit-refresh-20261030.md)。

## session bus ENOENT 与 v6 验证

小米 v5/fix4 单次从任务栏启动 Discover 后报 `DBus session bus not found`，尚未进入 PackageKit 初始化。该次会话失败态随后自行收尾，`phone-v6-before-install.png` 已显示两个容器卡片 Ready；该安装前截图时仍为 App v5，卡片恢复不等于 fix4 UI 验收。原 session daemon 存活且保有 socket FD；直接连接原明确地址返回 ENOENT。只读原 PRoot 内存获得唯一真实 binding：`$PREFIX/tmp/proot-13875-YZfkpG -> /tmp/dbus-JO1IPDE87a`，host 短路径已不存在。旧 rootfs 物理 socket 路径长 111 字节，超过 Unix socket `sun_path` 限制，触发 PRoot 临时重定向；未捕获生产删除调用，不能指定是哪次生命周期清理导致。

最小修改只将 session daemon 地址固定为现有 `/run/wl/container-dbus/session.sock` runtime bind（设备物理路径 95 字节），对实际返回地址做一次 3 秒 Peer.Ping；失败原码退出，不建替代 bus、不重试、不改模式或全局 TMP 清理。独立真实 PRoot/D-Bus 对照已证明：清理自有临时目录后，长路径连接 ENOENT 而 daemon 存活，runtime 短路径仍可连接；脚本契约覆盖 readiness 原码及单次调用。App v6 已完成远端构建与契约；模拟器正式 App 使用 forky / Software 启动成功，实际 `session.address=/run/wl/container-dbus/session.sock`。小米此前流式安装中断后的分块传输已完成，同一 v6 已安装；Zink / forky / fix4 正式主路径也通过，容器实际使用同一稳定 session.sock 地址。见 [原始 binding 与机制记录](dbus-session-lifecycle-20261030.md)。

## 模拟器 v6/fix4 正式主路径

从正式 App 选择 Software 与 forky Plasma 启动，Discover Home 有应用目录；搜索 KCalc 只返回 1 条，安装后显示 Launch，点击 Launch 得到真实窗口并通过界面完成 2+3=5，卸载后恢复 Install。最后通过 App Stop，卡片 Ready。上述均由主线程人工看图确认。

初始四项错误（fwupd root、无设备、LVFS 不可达、AppStream 图标 HTTP503）保留，加载已结束；启动自动刷新返回 503，不能记为手工 Refresh 成功。安装和卸载后迟到的错误均打开详情，确认仍为 LVFS。

证据为 `/tmp/termux-desktop-validation-20261030/emulator-v6-fix4-*.png`，含 discover-ready、search、kcalc-installed、kcalc-launched/window/calculation、kcalc-removed、initial-error-*、postinstall-details、postremove-details、final-ready；会话日志 `final-v6-fix4-{session,host}.log`。

## 小米 v6/fix4 正式主路径

同一 App v6 安装后，从正式 App 启动 Zink / forky Plasma 成功，容器服务与 Discover 使用 `unix:path=/run/wl/container-dbus/session.sock`。Home 有应用目录；Updates 点击 Refresh 后仍列出原有 3 条待更新，未点 Update All。`final-v6-fix4-session.log` 约 466–482 行记录 PackageKit `exit 1`（PackageKit 枚举 ExitSuccess）、AppStream load success、同 Pool 2471 组件及 fetching 0，真实 LVFS 错误独立保留。

KCalc 搜索恰好 1 条，安装后 Launch，点击 Launch 打开真实窗口，由界面点击完成 2+3=5；卸载后恢复 Install。初始 root、无设备、LVFS 三项错误保留并结束加载，没有 HTTP503；刷新、安装、卸载后迟到错误均打开详情确认为 LVFS。计算和最终卸载后错误详情 settled 截图均已人工查看。

App Stop 后最新 inspect 两个容器卡片 Ready，stayon 恢复 0；正式进程 env/maps 无 statx 适配库 preload 泄漏。证据在 `/tmp/termux-desktop-xiaomi-20261030/`：`final-v6-fix4-session.log`、`v6-fix4-runtime-env.txt`、`phone-v6-fix4-*.png`（含 manual-refresh、search、kcalc-installed/window/calculation/removed、postremove-details-settled、final-ready）。最终两设备 hash / dpkg audit 检查已通过，结果见下文。

## 并发显示 owner 冲突：已取证，未修复

模拟器首轮 v5/fix4 黑屏期间，另一个 `tusb-gnome-smoke.py` 启动的 `termux-x11 :6` 通过 XCB 向同一 App 投递 type2 缓冲区，覆盖原 KWin native type3 缓冲区与 shared state；KWin 及其 native 连接仍存活。10:51:24 首次 XCB 接入早于 10:51:53 与 10:53:11 两张黑屏截图，后续还有重复接入。`activity.c` 的 XCB 接入和 shared-state 发布尚未对 native owner 冲突设拒绝门禁，此项仅只读记录，未修改代码或构建修复包。

完整 logcat、带原行号的 owner 时间线和进程关系已保全在本地 `/tmp/termux-desktop-fixes-20261030/` 下的 `kwin-black-screen-{logcat,owner-timeline}.txt`、`gnome-smoke-*`；用户授权暂停烟测后，两次全 UID 快照及观察窗口无该脚本、`:6` 或新 producer。主线程随后使用原 v5/fix4 干净正式会话完成 UI 验证，不能据此宣称 owner 冲突已修复。

## 最终证据与收尾

- 两设备均已安装同一 App v6 与 Discover 四包 6.7.4-3+termuxfix4，KWin v4 / Xserver v8 保留；模拟器 Software、小米 Zink 的 forky Plasma / Discover 正式主路径通过。最终设备检查通过：两设备已安装 APK SHA256 与产物表一致，Xlorie / libkwin / start-plasma / Discover 逐项 SHA 一致；四个 Discover 包均 installed，dpkg audit 空，KCalc absent，无 REMAINING 桌面服务。
- 前序小米15条 original_verified 与 postinst 逐条 SHA 比较全匹配；v6 正式进程 env/maps 再次确认无 statx preload 泄漏。最终检查证据为两设备目录中的 `final-v6-fix4-device-check.txt`，小米 stayon 为 0。
- 两设备 v6/fix4 本轮 KCalc 计算后均已卸载恢复 Install，经正式 App Stop 收尾；小米最新 inspect 两卡片 Ready，stayon 已恢复 0。
- 持久证据位于上述目录的 emulator-latest/、xiaomi/；最终包清单位于 artifacts/SHA256SUMS.FINAL。最终设备检查原件分别为 `/tmp/termux-desktop-validation-20261030/final-v6-fix4-device-check.txt`、`/tmp/termux-desktop-xiaomi-20261030/final-v6-fix4-device-check.txt`；运行文件 SHA 前缀分别为 Xlorie `741e9b87`、libkwin `eee1bfc5`、start-plasma `366c1300`、Discover `f39b862d`，完整值保留在原件。
- 模拟器最终截图：emulator-v6-fix4-discover-ready.png、search.png、kcalc-installed.png、kcalc-launched.png、kcalc-calculation.png、kcalc-removed.png、postremove-details.png、final-ready.png（后七项同 `emulator-v6-fix4-` 前缀）；设置保护仍见历史 emulator-v5-landscape-settings-{scroll,rejected}.png。
- 小米最终截图：phone-v6-fix4-discover-ready.png、manual-refresh.png、refresh-error-details.png、search.png、kcalc-installed.png、kcalc-window.png、kcalc-calculation.png、kcalc-removed.png、postremove-details-settled.png、final-ready.png（后九项同 `phone-v6-fix4-` 前缀）。安装/卸载后迟到的 Update Issue 均读取详情，确认为 LVFS 不可达。
- 自动审批拒绝将小米整批诊断tar（日志/cache/database）上传编译机，理由为可能包含敏感设备数据且整批外发未获授权。未重试或绕过；设备证据仅本地保存，远端仅源码、构建产物与隔离测试。
