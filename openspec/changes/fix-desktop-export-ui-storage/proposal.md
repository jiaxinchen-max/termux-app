# 修复容器导出发行版识别及 UI 外部存储初始化

## 问题与证据
设备 120.24.191.156:8057 的归档任务记录为 FAILED，log=desktop_archive_distribution_unsupported。对应 desktop-cde9465f 为 PRoot KWin Plasma，容器 ubuntu-kwin-plasma-1 的 os-release 为 ID=ubuntu、VERSION_CODENAME=resolute、VERSION_ID=26.04。DesktopArchiveTasks.codename 仍仅允许 ID=debian，而归档元数据和引擎已支持 Ubuntu Plasma。
默认 UI 的 onResume 只刷新运行时状态，设备 ~/storage 不存在；诊断用 run-as 进程访问共享下载目录被拒绝，但此结果不能等同于 App 进程权限状态。后续 dumpsys 确认当前 Android READ/WRITE_EXTERNAL_STORAGE 已授予、LEGACY_STORAGE=allow，因此必须由 App 进程执行实际读写检查。已安装 termux-setup-storage 通过 reload_style/storage 广播请求终端 Activity 授权并创建链接，该接收者仅在终端可见时处理请求，默认 UI 不能靠直接运行脚本完成初始化。

## 修改范围（待确认）
1. 导出入口按桌面配置校验发行版与代号：Ubuntu resolute Plasma、Debian trixie XFCE，与既有归档规则一致。
2. UI 进入时检查 Android 存储授权及实际访问能力；缺失时进入系统授权流程，授权后完成 termux-setup-storage 对应的目录链接初始化并复查读写。默认 UI 保持可用。
3. 已授权但链接缺失时自动修复；用户拒绝时不循环弹窗，后续外部存储操作可显式重试。保留用户自建文件，不清空整个 ~/storage。

## 边界与文档
不扩大 GNOME 归档支持，不改变归档格式、解包安全规则或桌面底包；不迁移旧数据，不改已有 Discover 修复。无 HTTP API 变化，api_docs=N/A。权限流程和导出兼容性可见，product_docs=Required；拟在验收后维护 docs/product/desktop-container-archive.md、docs/product/shared-storage.md，当前一致性 Pending。
不包含提交、推送、部署或新 APK 交付。当前未修改业务代码。

## 用户补充及实施授权
用户已批准四份规划，并补充：所有需要外部存储的客户端入口统一采用系统弹窗等待授权再继续，覆盖组件下载、备份恢复、桌面归档及文件管理。后台服务和 DocumentsProvider 不得强行从后台弹窗；交互入口先授权，服务端保留实际访问检查，后台恢复经用户点击授权入口继续。

## 实机回归补充：备份末尾失败
用户反馈备份末尾 unreadable。设备任务日志确认为 archive_source_unreadable，非外部写入授权问题；以 App UID 扫描源容器，仅 rootfs/var/lib/snapd/void 无法列目录，实际权限为 0111（仅可进入、不可枚举）。该目录是 snapd 的空运行时挂载占位。按现有运行时排除语义保留目录及权限、不枚举内容；不忽略其他不可读用户目录，不修改源目录权限。此项延续已批准的导出修复。

## 恢复后重复安装回归
用户继续报告恢复后重装并失败。任务 97958826 导入本身成功，后续日志为 15 个宿主包被重新安装、容器 apt 因 DNS 失败退出 100。新 alias 缺少容器目录以外的渲染初始化凭据，导致 UI 判为未安装；归档保留 /etc/resolv.conf 指向 /run/systemd/resolve/stub-resolv.conf，但按运行时排除规则 /run 内容不恢复，DNS 链接悬空。延续已批准的备份恢复修复，在恢复发布桌面前完成离线本地初始化并校验就绪，不能把仅成功解包当作可用恢复。

## 全新安装恢复回归补充
用户反馈全新安装 APK 后导入历史备份失败。设备 ADB 当前 unauthorized，现场原因尚待日志确认；代码检查及宿主夹具可复现独立缺口：host-prerequisites 未检查 eglinfo，Zink/llvmpipe 初始化随后必须运行此工具，旧宿主可能因先前完整安装已有 mesa-demos 而掩盖问题。延续已批准的恢复前补齐必要宿主依赖范围，按显式宿主渲染模式补齐 mesa-demos 并验证工具，不能重装容器或更改备份格式。此补丁不等于已确认现场原因。

## 现场确认：API 入口被宿主装包覆盖
任务 cae5c744 的宿主前检成功、解包完成，但派发 initialize 任务时抛 api_component_initialization_failed。设备 termux-api 0.60.0 的 libexec/termux-api 为指向 termux-api-broadcast 的符号链接；APK 清单要求普通 ELF 文件，RuntimeApiComponents.safe 拒绝最终链接，故未执行离线初始化。延续已批准恢复可用性修复，允许安装器安全替换此唯一 API 入口的最终链接，不放宽父目录和归档成员链接限制，也不改容器内容或备份格式。

## 2026-11-15 定制宿主组件登记补全（用户已授权“完善”）
恢复预检与完整安装统一在最后一次宿主 apt 操作后用 dpkg 登记 APK 匹配的 termux-api，已安装 UPower 时同样重装定制包；核对 DEB 与已安装版本并 hold，防止普通升级覆盖。仅改宿主包管理，不重装容器或复制容器。失败阻止成功标记，重试可恢复；回退撤销辅助函数并显式 unhold 对应包。无 HTTP 契约变更，api_docs=N/A；product_docs 沿用 Required/Pending，等待用户验收。

## 导入后宿主 KWin 主题初始化崩溃回归
用户报告导入容器无法启动，沿用恢复后可启动的修复授权。现场导入 SUCCEEDED，宿主 KWin 在 Qt QKdeTheme::createKdeTheme/handleThemeChanged 空指针崩溃；受控 A/B 仅设置 QT_QPA_PLATFORMTHEME=generic 即越过该崩溃。仅为 PRoot Plasma 的宿主合成器构造独立环境，保留传给容器的原主题变量和父环境，不更改容器文件、主题、渲染模式或 Native 桌面流程。回退撤销合成器环境副本。api_docs=N/A（无 HTTP 契约），product_docs 继续 Required/Pending，完整验收前不归档。
