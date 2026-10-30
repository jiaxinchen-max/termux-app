# Discover arm64 本地修复构建记录

## 修复

Debian forky 的 plasma-discover 6.7.4-3、Qt Network 6.11.2 与 GLib 2.90.1 组合中，GLib 初始报告 Disconnected，异步枚举路由后才报告 Online。Discover 原来只在 Component.onCompleted 检查一次，导致过期离线提示残留。

`patches/discover-initial-network-state.patch` 订阅真实 reachabilityChanged。Online 停止定时器并关闭提示；非 Online 仅在定时器未运行时启动单次 1000ms 定时器，触发时重读真实状态。状态变化不重置原截止时间，关闭动画期间再次离线也会安排检查。未替换 backend、未伪造 Online、未更改渲染模式。

## 构建环境

- 指定编译机：builder@BUILD_HOST:7022。
- 隔离目录：`/home/builder/desktop-fixes-20261030/discover`。
- 编译环境：Debian forky arm64 rootfs，20261029_05:24。
- rootfs URL：`https://images.linuxcontainers.org/images/debian/forky/arm64/default/20261029_05%3A24/rootfs.tar.xz`。
- rootfs SHA256：`0e8dda6fc59603abc56196d2df4f180867d1c939707ae61a5b8346b00ed9f4d6`。
- 工具仅解包到任务目录：proot 5.4.0-3、qemu-user 1:10.2.1+ds-1ubuntu3、libtalloc2。
- 显式 `PROOT_NO_SECCOMP=1`，解决 QEMU 静态 PIE 与 proot seccomp 的工具兼容问题。
- 宿主未安装系统包；隔离 rootfs 的 policy-rc.d 返回 101，阻止构建依赖启动系统服务。
- Qt base 6.11.2+dfsg-5、Qt declarative 6.11.2+dfsg-3、KF coreaddons 6.30.0-1、configcore 6.30.0-2、GLib 2.90.1-2。

源码来自 Debian plasma-discover 6.7.4-3，保留原两个 Debian 补丁，再增加网络状态补丁和本地 changelog 版本 `6.7.4-3+termuxfix1`。

- orig SHA256：`f8b809c9aaa25c1d636b0bdf144d87a3ee659d5d80f26961d3116a627fef859d`。
- 原 Debian tar SHA256：`382aeb77e04cb1d115adda3d8f3b78f0c2f856910b89b82c9abf76d4163a2dfa`。
- 编译命令：`DEB_BUILD_OPTIONS=parallel=12 CMAKE_BUILD_PARALLEL_LEVEL=12 dpkg-buildpackage -nc -b -uc -us -j12`。
- 最终补丁同步后再次增量重编译、重打包，退出码为 0。
- 原 Debian rules 的 override_dh_auto_test 为 `:`；未运行上游测试套件。

## 交付

本地离线包：`/tmp/termux-desktop-fixes-20261030/artifacts/discover-6.7.4-3+termuxfix1-arm64.tar.gz`。

远端同名文件位于 `/home/builder/desktop-fixes-20261030/artifacts/`。

归档 SHA256：`448533ddc67f5270ef3d61c1b81aaf30bb9f2ada328543e5a40933f48b685376`。

归档包含：

- 全套同次构建的 deb、changes、buildinfo。
- `PACKAGES.json`：逐包 Version、Architecture、Depends、SHA256。
- `source/`：可重建的本地版本 dsc、debian.tar.xz 及原始 orig.tar.xz。
- `build-records/`：隔离构建入口、构建日志、真实 QML 测试源码与结果、最终 QML。
- `SHA256SUMS`：所有交付文件的散列。

PackageKit backend 包含在 plasma-discover 主包中；单独的 backend 包为 flatpak、fwupd、snap。主包与 common 必须同版本。目标已安装的 backend 或 dbgsym 如依赖精确 plasma-discover 版本，须同时使用对应的本地版本包；保留原 backend 选择，禁止通过删除依赖、修复依赖模式或换 backend 绕过不匹配。kde-config-updates 同次构建版本也已附带。

## 已验证范围

真实 Qt 6.11.2 arm64 QML 测试通过：短暂 Disconnected→Online 不弹；Disconnected→Local→Site 在原 1 秒截止后提示；Online 关闭提示；300ms 关闭动画中再次 Disconnected 后仍提示。

已从最终 deb 解出 `/usr/bin/plasma-discover`，确认其包含完整生成的 Qt 资源数组；解压其中的 DiscoverWindow.qml，与最终补丁源码逐字节相同。

设备正式 App UI 的搜索、安装、卸载、PackageKit 和 polkit 验证由主线程继续；本记录不将 QML 单测记为正式 UI 通过。
