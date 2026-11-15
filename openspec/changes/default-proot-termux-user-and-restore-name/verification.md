# 实现与验证记录

## 实现范围
全部桌面 PRoot 新容器创建 termux 普通账户，初始密码 termux；保留用户已修改的密码。GNOME/Ubuntu GNOME/XFCE/Hyprland 客户端使用普通账户；Plasma root 管理系统 D-Bus、PolicyKit、PackageKit，termux 管理会话 D-Bus、KDE 与应用。桌面信息显示账户提示。备份名称元数据沿用原格式，恢复发布锁内读取最新清单并选择最小数字后缀，卡片显示保存名称。无旧数据迁移。

## 通过的自动检查
- 账户准备 3 项：账户/HOME、密码保护的 sudo、重试不重置密码、拒绝异常身份。
- Plasma 管理/用户真实 shell 子进程 fixture 4 项：权限分离、故障阻止 ready、清理以及保留外来 system bus。
- PolicyKit GI transport fixture 8 项：UID 过滤、每进程注册生命周期、密码取消、worker 普通身份及 cookie 只走 stdin。此为桩测试，不表示 PAM 授权成功。
- Ubuntu/Plasma 安装 11 项，Hyprland 安装 6 项，系统总线 1 项，Plasma 启动 10 项。
- GNOME/Ubuntu 普通身份聚焦 fixture 4 种，XFCE/native 聚焦 fixture 3 种。完整历史 GNOME 脚本仍包含与当前实现不符的旧 renderer 检查预期，未声称全套通过。
- Gradle DesktopRestoreNamesTest、DesktopArchiveEngineTest、FileDesktopDistroRepositoryTest 通过；名称覆盖 Unicode/空格、连续及空洞后缀、多次恢复，并确认归档往返名称。
- arm64 APK clean/assembleDebug 成功，签名校验成功，aapt 未见 testOnly，唯一 ABI arm64-v8a；嵌入脚本逐字比对一致，无 pycache/pyc，无新增 DEB（原 termux-x11 资产哈希与上次 APK 一致）。HTTP 完整 GET 哈希核对成功。

## 上轮实机结果与当时未通过项目
已有样本容器创建 termux UID10208 后，普通用户 vlc --version 成功；sudo -S -k id 用初始密码成功。只能证明命令与密码提权，不代表 VLC 窗口和媒体通过。
私有系统 D-Bus 返回与 guest EUID 一致的 UID；root 管理服务启动与用户会话总线 Ping 通过。普通代理注册受 Android /proc 物理 UID 影响失败；root UnixProcess 注册成功。PolicyKit 127 使用 subject UID 绑定 cookie，已改为普通用户独立密码工作进程并修复标准 bus 路径和 helper setuid 模式。
实机诊断只将密码窗口临时替换为 echo 输入，仍使用原 PolicyKit/PAM/cookie；错误密码被 PAM 拒绝，正确密码最后仍返回 AUTHORIZED False，端到端授权尚未通过，不能宣称 Discover 安装修复。诊断改动只在设备临时文件，不打包到 APK。随后模拟器 run-as 返回 unknown package: com.termux，原容器已无法继续读取，未做卸载/清除数据操作。
设备当时宿主/容器 DNS 均不能解析 Ubuntu 源，未完成 Discover 下载。未完成所有桌面新容器冷启动、VLC 图形/音频与 Discover 图形授权验收。

## APK
文件名：termux-app_apt-android-7-debug_arm64-v8a.apk
大小：45388682 字节（43.29 MiB）
SHA256：df8964955312048d7d9b70b58a3c899d5dc36d78005b62597fce9aef3a47415b
上轮 APK；已由下方崩溃修复 APK 替代。

## 流程限制
仓库缺少 .harness/scripts/verify.sh 与 OpenSpec CLI，未运行 strict gate。产品文档保持 Pending，未归档。测试日志位于本次 workspace artifacts/vlc-start-failure，未将设备敏感内容或密码日志提交仓库。

## 本轮：Discover 输入密码后桌面崩溃修复
用户明确授权修复并推送。新实机容器 ubuntu-kwin-plasma-1 的日志确认：授权管理代理退出 139；manager 随即报 required service manager-pid-13572 exited (status 139)，关闭 PolicyKit/PackageKit/桌面。此前的缺包设备阻碍已消失，本轮重新 force-stop 后验证。

移除 PolkitAgent.Listener 的 Python 虚函数及 Gio.Task 异步跨 C 回调。管理代理改为显式 Gio D-Bus AuthenticationAgent 接口，只接受当前 PolicyKit owner，cookie 经 stdin 交给 termux 工作进程；工作进程直接驱动 PolkitAgent.Session，不再实现虚函数。关闭、重复请求、取消及迟到回调都有单次回应。管理器独立监督代理，最多重启两次，代理失败不清理 KDE。PAM、cookie、PolicyKit 授权规则保持原样。

本轮实机三轮检查全部退出 0：正确密码 AUTHORIZED True；错误密码 AUTHORIZED False；取消 AUTHORIZED False。每轮均 AGENT_ALIVE=0（kill -0 成功），SIGTERM 后 AGENT_EXIT=0，无退出 139。PolicyKit 明确记录 successfully authenticated as unix-user:termux to gain ONE-SHOT authorization。为自动诊断，只有密码窗口在临时副本替换为 echo/false；真实 PolicyKit/PAM/helper/cookie 与 UID 边界均保留。没有将该替换打包进 APK，没有验证实际 Discover 图形窗口操作或完整软件下载。

回归：授权路由/取消/迟到回调/不可信 sender 10 项通过；真实管理 shell fixture 5 项通过，包括注入代理反复退出 139 后 ready 保留、桌面继续运行；Plasma 启动 10 项与 system bus 1 项通过。严格 Harness 脚本仍缺失，不宣称其通过。

本轮 APK 构建成功，签名有效，testOnly 未设置，arm64-v8a，无新增 DEB/pycache；production 两个脚本与打包内容逐字一致。完整 HTTP GET 内容一致。
- 字节数：45506228（43.40 MiB）
- SHA256：46a8fa5f0175a8e75650768de09dc1543d8fb701e00b94132b028468b0d9779b
- 下载子目录：discover-auth-crash-fix
- 诊断证据：artifacts/vlc-start-failure/discover-crash-before.log、auth-final-output.log、user-session-fixed.log、agent-fixed-tests.log、plasma-fixed.log、system-bus-fixed.log、build-discover-fixed.log。

当前结论：此前正确密码后端授权失败已解决；本轮代理崩溃链路及故障隔离验证通过。完整图形交互和全部桌面验收仍 Pending，不归档产品文档。
