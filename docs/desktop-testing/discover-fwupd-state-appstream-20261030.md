# Discover fwupd 查询状态与小米 AppStream 只读诊断（termuxfix3）

## 已确认的 fwupd 缺口

`FwupdBackend::checkForUpdates()` 先置 `m_fetching=true`，然后异步查询 devices/remotes。旧 `get_devices_cb` 失败分支仅调用原 `handleError()`，没有清 fetching 或发 initialized；成功分支才收尾。搜索（包括 Installed）在 fetching 时等待 initialized，聚合结果流因此可以永久保留 “Still looking”。fwupd 更新进度也保持 42，导致全局 Updates 保留 Fetching。

`patches/discover-fwupd-query-settled.patch` 添加查询收尾函数：清 fetching，通知进度、contents 和 initialized。成功和设备查询失败均调用；错误分支先保留原 `handleError()`。它表示查询已结束，不表示查询成功。未用空设备数组伪装成功，未删除 backend，未改 remotes 错误处理或切换 renderer。

## PackageKit 与 AppStream：已查范围和未决项

小米正式 App v4、Discover termuxfix2 已按 Vulkan 打开，但 Home 无应用；KCalc、Kate 搜索及 Installed 底部一直 “Still looking”，Installed 只显示两个 system 更新资源。不能将修复 fwupd 后进度结束等同于修复本地应用结果。

对现存实例只读取证：

- PackageKit D-Bus：backend `apt`，Locked=false，NetworkState=2，GetTransactionList=[]。
- 禁用 apt-cache 的磁盘缓存输出后，读取到 KCalc 候选 `4:26.04.0-1`。
- APT Packages.lz4 21.6MB、DEP11 8.49MB；原 DEP11 有 `org.kde.kcalc.desktop` / `Package: kcalc`。
- libxmlb 打开复制的 C-os-catalog.xb 成功，2471 个组件，能查到完整 KCalc、Kate metadata。
- 无 preload 的 stat、gio info/list 正确读取 yaml 目录及 DEP11 symlink，未见 EPERM。
- Discover 各线程处于 poll/futex，无活跃 Qt pool worker；PackageKit daemon 无活动事务。这不能排除客户端残留 ResultsStream 或 transaction 对象。

缺少 “Took packagekit-backend” 日志不能证明插件没加载：`DiscoverBackendsFactory.cpp` 仅在初始化耗时超过 20ms 时打印。`No distro component` 来自已经进入 AppStream loadDone 的回调，仍不足以直接证明池组件总数为零。

`PackageKitBackend::reloadPackageList()` 使用默认 `new AppStream::Pool`，没有额外 flags/cacheFlags/locale/loadStdDataLocations 设置。`AppStreamIntegration::getDistroUpgrade()` 使用传入的同一个池，不另建池。

## 设备专用 probe

仅在指定编译机编译，通过 adb 放入自有容器 `/tmp/discover-pool-probe-20261030`。未刷新生产缓存，未向正式 Qt 会话注入 statx/preload，未重启正式 Discover。

1. 同步 AppStream Qt Pool，cache 输出覆盖到隔离副本：load=true、2471 组件，KCalc 搜索 1 条，Kate 搜索 6 条。
2. 匹配原实例无 LANG、XDG_DATA_DIRS 与 proot 的 link2symlink/-L 参数：同样结果。
3. 使用生产 libDiscoverCommon 的 ConcurrentPool、QGuiApplication、原有 queued loadDone 和 QFutureWatcher 调用顺序：异步 load=true，2471 组件，Debian distro component=1，KCalc 搜索 1 条。
4. 不调用 overrideCacheLocations，在 probe 独立 proot 中把隔离副本 bind 到 `/var/cache/swcatalog/cache`，采用默认 pool 路径；匹配原会话 Wayland、XDG_RUNTIME_DIR 与环境，仅建立无窗口 passive client：load=true、50ms、2471 组件、distro=1，KCalc 搜索 1 条、2940ms。

probe 说明当前数据、库、架构过滤和独立异步搜索可工作；它不能证明原正式实例的搜索结果流已修复。仍需用真实 UI 复现并读取新日志。

生产缓存验证前后 SHA256 均相同：

```
C-os-catalog.xb     d8c58d6d64ecd280f932224ceb36e16601925b8a4d755429e89e61c5e98c0cef
C-local-metainfo.xb 462bf26be8ba6ab7b995dae202143f4a2e33ced02ea42aaa06a014e9cf3a39d0
```

设备自有 probe 目录和两份 `/data/local/tmp/discover-{async-,}pool-probe-20261030` 均已清理。正式进程、桌面、包和用户文件未改动。

原失败实例完整日志另存 `/tmp/termux-desktop-xiaomi-20261030/discover-readonly-copy/failing-instance-plasma-container-complete.log`，133455 字节，SHA256 `8202f9731bfe07a583cb36a061d1b8d523dae5b80636aa6e1eea75e10b879d58`。同目录保留 cache/database 副本和各次 probe、D-Bus、线程、GIO 日志。

## fix3 只读诊断

`patches/discover-appstream-readonly-diagnostics.patch` 记录现有 search/components/componentsById 返回数量、locale、flags，以及 PackageKit load 起止、标准源路径与 cache 文件名/大小/mtime、指定环境变量、fetch 计数、refresher finished、结果流数量和等待 resolve 标记。不额外刷新或重建池，不修改 PackageKit 查询行为，不增失败回退。

此补丁用于区分空池、查询结果未送达和 fwupd 残留聚合流。不能把新进程偶然恢复当成 PackageKit 根因已解决。

专用 probe 源码位于 `discover-appstream-readonly/`，Fwupd 回归测试、构建与最终包证据随 fix3 离线包记录。正式功能仍由主线程通过 App UI 验证。

## 隔离真实插件回归

编译机独立 fake fwupd D-Bus 服务、真实 Discover fwupd plugin、真实 Qt ResultsStream：旧 termuxfix2 插件在实际 GetDevices D-Bus error 后，错误仍报告但 progress=42，等待中的搜索流未销毁，稳定复现。最终 termuxfix3 deb 解包插件完成五轮 error / empty / nonempty / no-devices / error-repeat：每轮等待中及随后创建的 stream 都结束，progress=100，initialized 恰好一次，进度通知存在，真实错误计数从 1 增至 2。该测试没有向生产 fwupd 或设备发送调用。 初始 NoDevices 精确对照也复现旧版同正式日志的 `Fwupd Error 10 No detected devices` 和卡住；新 artifact 保持相同错误可见，并正确完成等待流及进度。

fixture 使用独立实际 builder 身份的 PRoot runner，解决宿主 D-Bus peer credentials 与假 root EXTERNAL 认证的测试边界；未改生产 runner、包或设备。这不是软件渲染或功能失败回退。

最终包的 9 组显式模式矩阵、3 组真实 QML 认证门控、最终 ELF 嵌入 QML 字节比较、源码 dsc 重解包后六个关键文件比较均通过。PackageKit 仍只增加日志，没有推测性行为修补。

本机设备证据另存 `/tmp/termux-desktop-xiaomi-20261030/discover-readonly-copy-evidence.tar.gz`，SHA256 `0c6687dc45d207b199c80ad09d67b980b711187cd4e997c76d573bcab515376a`。自动审批拒绝整批设备日志/cache/database 外发到编译机，因此未上传或重试；远端交付仅包含构建来源、产物及隔离测试记录。

## fix3 交付

本地离线包：`/tmp/termux-desktop-fixes-20261030/artifacts/discover-6.7.4-3+termuxfix3-arm64.tar.gz`；远端同名文件位于 `/home/builder/desktop-fixes-20261030/artifacts/`。归档 SHA256：

```
4098a09b6d735455f199646c26818e4d3bc3fb8fb4a1821525e1ad5d64a693e2
```

所有包版本均为 `6.7.4-3+termuxfix3`，`PACKAGES.json` 记录 11 个包的精确依赖与 SHA256。需同步安装的四包：

| 包 | SHA256 |
| --- | --- |
| plasma-discover | `0e043f943e0e219818d3eb6456fa34925f526a82d3ef4dbdba113d45eb529658` |
| plasma-discover-common | `4dbeb64747059a3efae3fb4d1e66788eb1e02dbc2e07cea7113acba6e751ad35` |
| plasma-discover-backend-fwupd | `41425299b1219725eb131d17e9c1654e12955243a94fcf5e25e6d8f7a5b1ddc2` |
| kde-config-updates | `d42dd459f93429a16a848236c43789b1db614d7a1a05a3b5eef1b0cf463b8985` |

`ELF-SHA256.json` 记录主程序、common、PackageKit plugin 和 fwupd plugin 的最终字节散列。实际回归测试使用的 fwupd plugin 与最终 deb 解包文件均为 `2745d49d8b4b4932fe437c0fee1f4dd782de9517caa1a9a104f7aca90c94f5d6`。

独立回归小包：`/tmp/termux-desktop-fixes-20261030/artifacts/fwupd-state-test-evidence.tar.gz`，SHA256 `6ae0356ea09ef174730d11d2270549fed897d65d37f03e90ad74947942f591db`；主离线包也已包含源码和四组回归日志。

本机再次独立验证离线包 SHA 及内部 64 个文件散列通过；四包和 manifest 已解到 `/tmp/termux-desktop-fixes-20261030/artifacts/discover-fix3-verified/`，供主线程正式 UI 验证准备使用。回归夹具源码同时存于 `discover-fwupd-query-state/`。

## 最终正式UI结果

App v5 + Discover fix3：模拟器Software搜索/安装KCalc显示Launch、卸载恢复Install通过；小米Zink/Vulkan搜索/安装/Launch实际窗口、界面2+3=5、卸载恢复Install通过。两个环境均保留真实LVFS不可达错误。小米fix2/fix3受控ABBA均返回2471组件，历史PK缺结果未复现；仅fwupd永久等待已确认修复。详见主验收报告及受控对照记录。
