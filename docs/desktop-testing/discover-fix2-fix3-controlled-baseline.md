# Discover fix2/fix3 受控对照准备

## 已准备的精确产物

`/tmp/termux-desktop-fixes-20261030/artifacts/discover-controlled-baseline-payloads/` 含从已校验 deb 读取的 fix2/fix3 主程序、Common、PackageKit plugin、fwupd plugin；`MANIFEST.json` 记录来源 deb 与每个 ELF 的 SHA256。未安装到设备或替换生产文件。

## 第一层：固定数据的实际 ConcurrentPool 对照

使用同一个专用 probe 二进制，分别通过各自目录加载精确 fix2/fix3 Common 库；不使用 LD_PRELOAD，不向正式 Qt 进程注入内容。两边其余 Qt/AppStream 库保持完全相同。

运行前必须显式指定 variant、renderer、当前 runtime 和 snapshot，禁止猜测/自动换模式/重试：

- variant 只能 fix2 或 fix3；每次运行核对目标 ELF 散列。
- 原小米场景使用 QSG_RHI_BACKEND=vulkan，QT_QUICK_BACKEND 未设置。
- HOME=/root、XDG_DATA_DIRS=/usr/share:/usr/local/share、LANG/LC_* 的设置与新正式实例逐项对照；默认缓存路径、locale、flags、loadStdDataLocations 使用实际构造代码，不通过 overrideCacheLocations 改变路径选择。
- Qt Wayland 客户端只建立无窗口连接，采用当前明确传入的 runtime，不能复用旧 PID 或旧 socket 地址。

快照需同时固定 cache、原始 catalog/metainfo 与用户 metadata，而不只是两个 xb：

1. 对 cache、标准 metadata 源、symlink 目标记录内容 SHA256、size、mtime、symlink target、目录是否存在。保留实际输入不存在的状态。
2. symlink 不能逃回生产目录；例如 `/var/lib/swcatalog/yaml` 的绝对链接还要求在独立 proot 中把对应 `/var/lib/apt/lists` 输入映射到同一快照。
3. 每个 variant 从同一快照恢复自己的可写 cache/home/tmp 副本，bind 到相同的 guest 默认路径。生产目录不作为写入目标；不调用 refresh-cache，不修改读取 flags。
4. 每次记录 loadFinished 结果、组件总数、distro/KCalc/Kate ID 数量、搜索结果及结果流结束时间。按 fix2/fix3/fix3/fix2 顺序执行，避免简单先后顺序造成误判。
5. 核对生产 cache 的前后 SHA；所有新增设备文件限定到自有目录，结束删除。

只保存了旧实例的 xb 而没有完整原始输入快照时，不能把后来采集的 raw metadata 称为原失败输入完全一致。应明确标记该限制。

这一层排查库、数据和异步查询差异，不能替代完整 Discover 启动或正式 UI。

## 第二层：实际 backend/完整进程时序

仅第一层不足且新正式日志支持时执行。Discover 启动会自动调用 PackageKit；仅换 HOME 或 session bus 不能保证生产缓存只读。完整进程必须同时隔离 system/session bus、APT lists/cache、PackageKit 写目录、用户 home/tmp/run 和 AppStream 写目录；在隔离不充分时直接拒绝运行，不连接生产 PackageKit 执行刷新。

最小替代是实际 PackageKitBackend 的独立测试进程和隔离 D-Bus fixture，测试角色与输入明确声明，不伪造在线，不将 fixture 结果记为正式安装验证。

对照需区分：

- 精确 fix2 与精确 fix3：是否相同数据下均能返回本地应用。
- fix2 加相同只读诊断、fwupd 行为仍旧：判断诊断本身的时序影响；此项须标为诊断构建，不能称精确 fix2 产物。
- fwupd 独立错误状态：已有真实插件基线/候选回归证明 fix2 会保留聚合等待、fix3 正确收尾；它不能解释 AppStream 应用结果缺失。

fix3 的源/cache 目录日志在 loadAsync 前执行，可能延迟初始化。因此新实例恢复既可能来自重启，也可能受诊断时序影响；不能据此认定 PackageKit 原缺口修复。

## 结论规则

- 两个版本在固定快照下均返回 KCalc：只证明当前输入/库正常，原 PK 问题尚未复现。
- fix2 失败、fix3 成功：还须分离 fwupd 收尾和诊断时序，不能直接归因 PK 逻辑修复。
- 池组件数正常而 UI 无行：沿 ResultsStream/resolve/聚合生命周期调查。
- 池成功但组件为零：检查实际输入选择和加载时刻，不重启/清缓存/强制在线掩盖。

新正式日志到达后再选择执行步骤；本准备阶段没有启动额外设备进程或窗口。

## 2026-10-30 小米受控对照结果

正式 App v5 + fix3 的新会话日志显示 PackageKit load=true、distro=1，首页多个应用 stream count=1，fetching 最终归零。当前正式会话确实已能读取应用；此前缺失结果的原因仍未确认。

在设备专用目录固定完整当前输入：`/var/lib/swcatalog`、`/var/cache/swcatalog`、`/var/lib/apt/lists`（包含 catalog 绝对链接目标）、`/var/cache/app-info`、`/usr/share/metainfo`、`/usr/share/applications`、`/usr/share/appstream`、用户 `.local/share`，并记录其余标准位置不存在。每个运行从同一快照复制独立数据，映射到相同 guest 默认路径；HOME/tmp/run 独立，仅连接明确指定的当前 Wayland socket，无窗口、不使用 PackageKit 或生产 D-Bus。

使用相同原异步 ConcurrentPool probe，分别加载精确 fix2/fix3 Common；`QSG_RHI_BACKEND=vulkan`、`QT_QUICK_BACKEND` 未设置，HOME、locale、XDG 与正式实例一致，无 overrideCacheLocations。ABBA 结果：

| 轮次 | Common | load | 组件 | distro | KCalc | 完成耗时 ms |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | fix2 | true | 2471 | 1 | 1 | 1016 |
| 2 | fix3 | true | 2471 | 1 | 1 | 1015 |
| 3 | fix3 | true | 2471 | 1 | 1 | 1023 |
| 4 | fix2 | true | 2471 | 1 | 1 | 1039 |

本轮沿用相同 probe，仅检查总数、distro 与 KCalc 查询；未额外添加 Kate 查询。probe SHA256 为 `cbc2596a4f6b3bfff124310491f4c92db9ac1dd6e31502061ca75b64c01a4a93`，Common SHA 与上文 manifest 完全一致。生产输入在快照前、快照后及四轮后，内容 SHA、size、mtime、链接/不存在状态 manifest 逐字节相同。测试文件已全部从设备删除。

结论：当前输入下两个 Common 均正常，原 PackageKit 缺结果未复现。此结果不证明完整 Discover 启动时序一致，也不能把新进程恢复归因为 fwupd 状态修复或 PackageKit 行为修复；旧失败实例未保存完整 raw metadata 输入是限制。

本机证据 `/tmp/termux-desktop-xiaomi-20261030/controlled-pool-evidence.tar.gz`，SHA256 `78aa77941c1e826943db4ac79b13474a07d19c18956752c4d25278a1709ce617`，仅含结果及输入 manifest，未外传设备数据。执行脚本保存在 `/tmp/termux-desktop-fixes-20261030/run-controlled-pool-device.sh`。
