# Plasma private session bus socket 生命周期

## 现有会话取证

小米 App v5 + Discover fix4、forky Plasma、显式 `zink`。任务栏点击 Discover 一次后退出；日志在 renderer 声明之后报告 `DBus session bus not found`，未进入 PackageKit backend 初始化。未重复启动 Discover、创建替代 bus、改渲染模式或重启会话。

- 原 `plasmashell` PID 14591、`kded6`、PackageKit 的环境一致：`DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-JO1IPDE87a,guid=f9070963d2e61a9fc0b359496ae40e58`。
- session `dbus-daemon` PID 14290 存活并保有 socket FD；其记录 PID、地址与进程一致。
- task manager 的 launcher 为 `applications:org.kde.discover.desktop`；已安装 desktop 文件为 `Exec=plasma-discover %F`，无 `DBusActivatable` 或用户覆盖。
- 原 PRoot PID 13875 无 `/tmp` bind。其 shell、bus、plasmashell 的 cwd 均指向 alias1 的同一 rootfs。
- 使用既有明确地址建立新的只读连接失败：`Failed to connect to socket /tmp/dbus-JO1IPDE87a: No such file or directory`。system bus 的现有 socket 位于 runtime bind，单次 `Peer.Ping` 成功。
- 只读原 PRoot 的可写内存，按设备对应 v5.1.107.101 的 `Path` 布局（4096 字节 path、8 字节 length、4 字节 Side、对齐后 4112 字节）匹配唯一目标 binding，得到：

```
LIVE_PROOT_BINDING /data/data/com.termux/files/usr/tmp/proot-13875-YZfkpG -> /tmp/dbus-JO1IPDE87a host_length 54 side 0
HOST_STAT FileNotFoundError(2, 'No such file or directory')
```

这直接证明生产 session 地址实际依赖 `$PREFIX/tmp` 下已丢失的短路径。连接失败发生于 socket 路径查找，未进入 D-Bus 认证；没有证据将该失败归因于 Discover fix4。

## 路径与清理机制

设备 PRoot v5.1.107.101 的 `src/syscall/socket.c` 在物理 socket 路径超过 `sun_path` 108 字节时，以 `create_temp_name(NULL, "proot")` 创建短路径并插入 binding；`src/tracee/tracee.c` 中普通 fork 共享这些 bindings。

旧默认 socket 的 rootfs 物理路径为 111 字节，实际已触发上述重定向。固定到现有 runtime bind 后，设备物理路径为：

```
/data/data/com.termux/files/home/.termux/kwin-runtime/runtime-10289/container-dbus/session.sock
```

共 95 字节，不需要 PRoot 临时重定向。

`TermuxShellUtils.clearTermuxTMPDIR()` 会将正的保留天数临时按 0 处理，再清空 `$PREFIX/tmp`。**没有直接保存生产 socket 被删除的调用和时间，因此不声称已证明该生产清理事件由哪次生命周期操作触发。** `/proc/net/unix` 和 Unix socket netlink 查询均被 Android 权限拒绝；上述原进程 binding 取证提供了实际路径证据。

## 最小修改

`desktop_container_session.sh` 的 session daemon 显式传入 `--address="unix:path=$BUS_DIR/session.sock"`，与 system bus 一样使用现有稳定 runtime bind。启动后仅对 daemon 返回的明确地址执行一次 `Peer.Ping`，3 秒超时；失败打印该地址和原 `dbus-send` 状态并以原状态退出。未修改全局 TMP 清理策略、Discover 包、renderer 或服务模式；没有重试、替代 bus 或失败切换。

冻结文件 SHA256：

```
31a6a39e420884dee6e9a0ee12493b2bec84eeb1b2770691d9ae4db1cb104aee  termux-ui/src/main/assets/local-games/desktop_container_session.sh
eee18db9e148aaa26b6033868346125138f7369a4033d0e5c2938c572d33d644  termux-ui/scripts/test-desktop-scripts.sh
```

## 验证

`sh -n` 通过；`TMPDIR=/tmp sh termux-ui/scripts/test-desktop-scripts.sh` 全套通过。新增契约验证 readiness 失败 37 原样退出、只启动一次 daemon、只连接一次明确地址且未继续启动。macOS 默认临时目录过长会使既有 socket fixture 报 `AF_UNIX path too long`，因此测试显式使用短目录；沙箱内禁止 socket bind，完整测试在授权的沙箱外运行。

真实对照在编译机独立目录 `/tmp/dbus-proot-lifecycle-20261030`，使用设备对应源码 v5.1.107.101 原生构建的 PRoot 和宿主真实 `dbus-daemon`、`dbus-send`。源脚本：[native-tests/dbus-session-lifecycle.py](native-tests/dbus-session-lifecycle.py)。未替换设备 PRoot 或操作正式会话。

| 试验 | 清理前 Ping | PRoot 临时 socket | 清空本试验 PROOT_TMP_DIR 后 Ping | 原 daemon |
| --- | --- | --- | --- | --- |
| 149 字节物理长路径 `/tmp/dbus-default.sock` | 0 | 存在 | 1，原始 ENOENT | 存活 |
| 76 字节物理 runtime 路径 `/run/wl/container-dbus/session.sock` | 0 | 无 | 0 | 存活 |

对照中的长路径使用显式 `--address` 固定在旧 `/tmp` 位置，验证 PRoot 路径重定向与清理的因果关系；不声称重放了生产删除时刻。为隔离清理影响，runner loader 位于临时目录之外。清理整个自有目录后 PRoot 退出时两组均保留了 `can't chmod ... No such file or directory` 清理诊断，未隐藏这些日志；两组主验证状态均为 0。

本地证据根 `/tmp/termux-desktop-xiaomi-20261030`：`final-v5-fix4-bus-readonly.txt`、`final-v5-fix4-bus-guest-readonly.txt`、`final-v5-fix4-bus-mapping-readonly.txt`、`final-v5-fix4-proot-binding-readonly.txt`、`final-v5-fix4-bus-listener-readonly.txt`、`final-v5-fix4-bus-lifecycle-logcat.txt`。仅保存在本地，未上传设备日志或数据。

独立对照日志及 runner/source SHA 位于 `/tmp/termux-desktop-fixes-20261030/dbus-session-lifecycle/`。上述验证只覆盖机制和脚本契约；App v6 的正式功能 UI 验证由主线程执行。

## App v6 正式验收

同一 App v6（SHA256 `3953f740baba81303c97bc2dba2a57267d8febed480e9a9c5fd61574e26c8adc`）已安装两设备。与 v5 相比仅 session 脚本变化，其余 63 个 assets、5 个 native 文件和 17 个 DEX 完全一致；远端构建与契约通过。

从正式 App 启动 forky Plasma，模拟器显式 Software、小米显式 Zink 均成功；容器 session 地址固定在 `/run/wl/container-dbus/session.sock`。两设备 Discover fix4 均完成 Home 目录、搜索 KCalc 1 条、安装 Launch、Launch 真实窗口、界面 2+3=5、卸载 Install，由主线程人工看图验收。模拟器启动自动刷新 503，未记手工 Refresh 成功；小米 Updates 点击 Refresh 后 PackageKit ExitSuccess（日志 exit 1）、同 Pool 重载 2471 组件、fetching 0，保留原有 3 条待更新，未点 Update All。

小米初始 root、无设备、LVFS 三项错误保留，无 HTTP503；安装/卸载迟到错误详情均 LVFS。App Stop 后最新 inspect 两容器卡片 Ready，stayon 恢复 0，正式进程 env/maps 无 statx preload 泄漏。日志与图像位于 `/tmp/termux-desktop-xiaomi-20261030/` 的 `final-v6-fix4-session.log`、`v6-fix4-runtime-env.txt`、`phone-v6-fix4-*.png`；模拟器证据位于 `/tmp/termux-desktop-validation-20261030/emulator-v6-fix4-*.png`。

最终设备检查已通过：两设备 APK 与组件 SHA 逐项一致，四个 Discover 包 6.7.4-3+termuxfix4 均 installed，dpkg audit 空，KCalc absent，无剩余桌面服务，stayon 为 0。原件为两设备证据目录中的 `final-v6-fix4-device-check.txt`。上述正式 UI 通过不等于证明生产旧 socket 的删除时刻，也不关闭另有证据的并发 display owner 冲突。
