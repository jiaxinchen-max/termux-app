# Discover PackageKit AppStream 刷新生命周期（termuxfix4）

适用现有 Debian forky Plasma，App 保持 v5。源码与离线包已冻结；正式 App UI 验收由主线程执行，本报告只记录组件与最终 ELF 验证。

## 已证明的机制缺口

真实 `PackageKitBackend` 在构造时加载 AppStream；`RefreshCache` 成功后仅取 updates，没有重载应用池。默认 flags 不含 `FlagMonitor`。首次 metadata 晚于池初始化时，同一实例持续查询旧池；`m_allPackagesLoaded` 还会缓存空的 AllApplications。

独立目录、真实插件/Common、默认池路径及私有 D-Bus fixture 复现：首次空池及 AllApplications 为 0；自行构造 catalog，由真实 AppStream 编译器发布缓存后，实际 `Finished(ExitSuccess)` 到达，fix3 仍返回 KCalc=0。新池可读取同一已发布组件。最终 fix4 同一实例返回该组件，无需重启或切换后端。

旧手机会话 00:10:54 启动；catalog 缓存 mtime 为 00:11:59.601，PackageKit `refresh-cache` last_action 为 00:11:59.723。没有旧 Discover 精确首次加载时间和完整初始 raw metadata，不能据此宣称旧实例完整根因已重放。此前固定当前输入的 ABBA 两版本均 2471 组件结果仍成立。

## 变更

- 仅成功 RefreshCache 请求重载同一池；完整 load 与组件查询持同一 mutex 串行，连接只建立一次。加载中的成功刷新合并为一个后继 load。
- 新查询等待最终加载代；旧异步查询按代次停止投递，不把旧组件重新加入活动索引。load/刷新失败保留原文错误和真实计数，不触发失败重试、空结果冒充成功或后端切换。
- 按 AppStream 原生 catalog 序列化 SHA256 比较 metadata；未变对象复用，变更/删除退出活动索引；包到 ID 映射按新池去重重建。缺失资源按单个 ID 精确查询，保留无应用映射的通用包查询。
- 本地 deb 使用独立 ResultsStream，不受 catalog 初始化/重载门禁影响。此前候选被实际加载阻塞测试拦截，未用于设备验收。

[补丁](patches/discover-packagekit-appstream-refresh.patch)；[真实 Backend 测试源码](native-tests/discover-appstream-refresh/README.md)。

## 最终产物验证

最终 deb 提取后的 Common/PackageKit ELF 全套通过，`/proc/self/maps` 记录了实际加载的最终提取路径：

- 成功刷新、新组件可见；相同 metadata 连续三次刷新保持对象身份，映射不重复。
- 同包两个应用的 QVector 恰好两个唯一 ID；真实 Resolve 事件创建的无映射通用包仍可查。
- 同 ID 改名、改包、删除均更新查询与 AllApplications；旧详情引用仍有效。
- worker barrier 阻塞真实加载时，两次成功刷新合并后继 load；旧流不投递，新流等待最终代；本地真实 deb 独立返回。
- RefreshCache 真实错误不重载、不重试；只读缓存目录令真实 AppStream load=false，原始错误可见，fetching 归零。
- 最终主程序九组显式模式矩阵、三组实际 QML 门控通过；内嵌 QML 与导出源码逐字一致。
- 11 个 deb 版本/依赖及源码包重提取 SHA 校验通过；fwupd ELF 与已验证 fix3 字节一致。

## 边界与保留证据

一次并行旧基线在 fixture metadata 发布前 SIGSEGV，保留原日志；同一个最新基线测试 binary 串行运行正常完成，并复现 stale 结果。五个相关旧 header 与精确 fix3 源码包逐字一致，编译 include 顺序及冻结 ELF SHA 已核对，排除混用 fix4 布局。

各轮实际 AppStream catalog、系统缓存、metainfo/applications、flatpak、HOME、XDG runtime 和 D-Bus socket 目标独立，无外指 symlink。裸 guest `/tmp`、`/run` 仍来自共享构建 rootfs，不声称整个 rootfs 完全隔离。并行基线崩溃未归因，不作为已修复项。

退休资源仍由 backend 持有至析构，保障详情/事务/流的旧引用；长会话反复 metadata 变化会保留更多对象。提前回收需显式跟踪 UI、事务和流引用，不能直接 deleteLater。

## 冻结包

`discover-6.7.4-3+termuxfix4-arm64.tar.gz`

SHA256：`6b2bf0284adf46f911eb250be232468ccfa7210e623ae07a2dfd17f711472a32`

包含 11 个 deb、源码包、70 项内部 SHA 清单及完整测试/审计记录。四个部署包位于持久目录的 `artifacts/discover-fix4-verified/`，确切散列见该目录 `PACKAGES.json`、`ELF-SHA256.json`。
