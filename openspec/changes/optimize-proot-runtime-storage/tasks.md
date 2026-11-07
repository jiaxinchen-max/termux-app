# Tasks

前置条件：proposal、design、三份 capability spec 与下列任务经人工确认，且 design 所列高风险脚本/组件路径取得范围批准；用户已在规划物化后明确“开始实现”，本轮记录为规划与所列路径范围确认。阶段 1 的制品试验与阶段 2 的按需布局可分别验证，正式索引在候选和来源验收前保持原包。

## 1. Hangover 本地精简制品

- [x] 1.1 固定 Hangover 11.16 与 gitlink、Debian 13 用户空间、LLVM-MinGW 摘要和上游配方，检查远程构建能力并记录构建身份；验收：输入身份可复核、原有目录和输入未变、不混入宿主旧 ABI。
- [x] 1.2 实现源码交叉编译与链接期 PDB 分离、ELF debug 分离及候选打包；验收：PE 无嵌入 DWARF，PDB 标识可映射，原组件复用有明确摘要，工具/格式/构建错误不产出成功标记。
- [ ] 1.3 实现 ARM64X/PE/ELF、架构/能力清单和符号对应校验并补充分发说明；验收：保留导入导出、重定位、异常展开、load-config/混合架构元数据、Wine builtin 标记、文件属性和 deb 依赖/脚本；记录工具限制、配置/日志和重复构建的可复现性结果，不把源码重编译误称为旧 DLL 字节不变。
- [ ] 1.4 在获准的隔离运行环境验证候选的 wineboot、32/64 位程序、图形与手柄，并记录未覆盖 ABI；验收：真实日志和制品 SHA 对应，失败不改正式目录，未部署时保持此项未完成。

2026-11-06 范围修订：用户明确“不用考虑老数据，当个全新安装对待”。取消旧 PRoot 数据、配方、任务、模板和备份迁移要求；Native 共享协议不变。保留新布局失败保护、依赖完整性和离线备份正确性。

## 2. 冻结依赖与最小准备集合

- [ ] 2.1 增加版本化 PRoot 绑定记录、最小依赖 resolver，不要求旧内嵌布局迁移，覆盖内置/自定义 Wine、翻译器和 DX；验收：JVM 用例证明 Hangover-only 不要求独立 Box64，WineD3D 不要求 DXVK，Wine/Proton 只要求选中集合，非法组合仍失败；自定义导入发布到私有版本根，旧 guest 自定义实体不迁移。
- [x] 2.2 增加 setup spec v4、LaunchSpec v7 的绑定序列化/校验及旧 schema 读取；验收：往返、数量限制、非法角色/目标、路径含特殊字符、越界链接与冻结任务快照测试通过，未使用 eval/source 解析输入，Native 仍输出 v6 且旧 Native 启动合同通过。
- [ ] 2.3 持久化配置与任务的精确版本引用，组件 active 更新不重定向已有绑定；验收：更新 active、删除候选版本及并发引用/清理故障测试证明原版本仍保留，相关字段与身份文档同步。
- [ ] 2.4 调整父准备任务、依赖子任务与 RuntimeInstallationGate 的协调和恢复；验收：组件下载、RootFS 准备不会相互等待死锁，取消/进程重启可对账，Activity 不承担磁盘扫描或初始化。

## 3. 基础环境与 guest 视图

- [ ] 3.1 增加 recipe v11、guest manifest v3 及基础/配置就绪语义，基础配方只保留 Hangover 与必要系统能力；验收：构建合同证明不安装/复制 Wine、Proton、独立 Box64、DXVK，不因缺少可选组件误判损坏，CJK/GBK/音频/图形依赖保留。
- [ ] 3.2 统一构建、启动、模板、wineboot/wineserver、维护命令的冻结绑定映射，覆盖音频两分支；验收：shell 合同检查实际 PRoot argv 的所有相关入口，两个配置使用同一版本时只有一份完整组件实体。
- [ ] 3.3 调整宿主侧 DXVK 和 controller 数据来源至冻结内容根，保护 prefix 链接与 Hangover 既有客体补丁语义；验收：运行前后组件树哈希不变，链接陷阱不改共享源，Native 与 PRoot 手柄合同测试通过。
- [ ] 3.4 更新空间预检与布局诊断说明，区分 active、外置唯一实体、模板、staging、previous 和峰值；验收：缺空间时任何发布前失败，已存在源不重复计量，不用压缩包大小代替安装估算，自定义组件采用保守策略。

## 4. 模板按需与启动消费边界

- [ ] 4.1 为完整 Wine/translator/架构/配方/初始化身份建立模板键、manifest 与归档校验，旧模板不导入；验收：同 ID 不同摘要/翻译器不串用，旧模板不能无凭据进入新缓存。
- [ ] 4.2 在实际配置准备中只构建所选模板，去掉 base build 和 restore 的全包预热；验收：Hangover 首次准备仅构建一套，再次复用，选择 Proton 才新增其模板，持久控制台显示阶段和取消结果。
- [ ] 4.3 增加模板构建锁、owner 对账、暂存校验与原子发布；验收：双请求仅一个构建者，取消/超时/崩溃/截断归档故障不会发布半成品，后续请求能恢复且原有效模板不丢失。
- [ ] 4.4 固化启动只消费已准备前缀，准备中/缺依赖/非空损坏 C 盘明确失败；验收：启动分支不下载、不隐式 wineboot、不重建 base，不覆盖损坏前缀，相关使用说明与合同测试同步。
- [ ] 4.5 增加显式模板清理与引用保护；验收：只回收无配置/任务/事务引用的缓存，运行库与游戏前缀/存档/外部文件哈希不变，需要时可通过准备重建。

## 5. 全新安装与备份恢复

- [x] 5.0 按用户全新安装授权清除测试设备旧 PRoot 基础环境、模板及准备状态；验收：清理白名单均不存在，组件源与 Native/游戏配置元数据前后相同，实际净释放 4.75 GiB，证据位于本变更 `evidence/fresh-install-reset/`。
- [ ] 5.1 完成 v11 全新安装与中断重试发布；不迁移旧配方/任务/模板/备份/自定义实体，不保留旧 RootFS previous；验收：清理范围与实际释放有证据，组件源和 Native 保留；首次构建/校验/发布故障不会把半成品标记就绪，后续可重试。
- [ ] 5.2 增加备份输入/归档清单 v2、就绪绑定依赖闭包、版本去重与一致快照锁；验收：同版本只打包一次，基础环境与冻结组件身份完整，源绝对路径不进入可执行恢复映射，模板仍可选。
- [ ] 5.3 实现新归档的严格预校验、依赖暂存与事务恢复，旧内嵌归档明确拒绝；验收：空组件目录断网恢复通过，恶意路径/链接/大小/摘要/缺项/版本冲突均在替换前失败，原 base 与组件 active/previous 不受损。
- [ ] 5.4 完成全新安装/新备份说明与中断恢复测试；验收：恢复无模板时不全量预热，恢复中断能对账，恢复后的已就绪绑定可直接预检，未准备配置不会被虚标就绪。

## 6. 集成验证与制品交接

- [ ] 6.1 完成定向 JVM、RootFS/controller/启动/备份 shell 合同及模块边界检查，记录 OpenSpec strict 与可用 Harness gate；验收：适用检查通过，缺少权威 Harness 时单独报告 unavailable，不伪造 strict-run 结果。
- [x] 6.2 构建可手动下载的 APK，提供版本、SHA-256、候选包与兼容说明；验收：实际 APK 构建成功，保持用户手动安装策略，不通过低带宽 ADB 传输大制品。
- [ ] 6.3 获得明确部署范围后从 App 验证 Hangover-only、新选 Wine/Proton、重复复用、取消恢复、32/64 位、渲染、手柄、退出清理和离线备份恢复；验收：每项有对应版本与证据，失败/未覆盖项不勾选完成。
- [ ] 6.4 测量同输入优化前后 active/唯一组件/模板/staging/总占用与峰值，完成实现审查与回滚演练；验收：分别给出精简与去副本收益，不重复累计，旧环境清理释放与最终布局去重收益分别报告。
- [x] 6.5 根据用户 pdb2 测试阶段发布与代码推送授权，接入真实来源与新身份并验证目录；验收：独立 v2 URL 的实际下载 size/SHA 匹配，App 与组件仓库目录一致，旧资产保留；发布说明列明已通过运行项和未完成矩阵，不宣称整体验收。

## Workflow follow-up

- 用户最终验收后维护 `docs/product/wine-library.md` 与 `docs/product/runtime-components.md`，完成产品一致性证据、审查和 manifest Verified；在此之前保持 Pending。
- 归档需要单独授权、delta-to-baseline 同步与可用的完整 Harness 归档门禁；本变更及方案确认不包含 Git 或外部发布授权。

## 2026-11-06 v11 启用切片进度（未完成项保持未勾选）

用户明确“启用 去重布局再验证”。已接通 2.1/2.3/2.4 的最小依赖、私有版本、父子归属及冻结发布，3.* 的 v11 基础/外置映射和分开空间预算，4.1/4.2/4.4 的所选缓存与普通启动消费边界；本地 JVM 633 项通过，冻结绑定 13、staging 8、模板 4、备份保护 2 及 launcher/controller/prefix/模块边界合同通过。详细实现与未覆盖范围见 `.harness/runs/optimize-proot-runtime-storage/evidence/enable-v11-20261106/verification-report.md`。

6.2 已交付默认名 APK，0.118.0/118，签名/CRC/ARM64/资源一致性通过。633 项 JVM 通过不等于所有上述任务验收完成：自定义导入/手柄设备验证、引用清理、全部并发/崩溃矩阵、备份 v2 与实际空间测量仍待完成；旧备份引擎显式拒绝 v11。用户随后已手动覆盖安装并完成本轮部分设备复验，未通过 ADB 上传；6.3 因 Hangover 首次准备失败及完整兼容/恢复矩阵未覆盖保持未完成。

## 2026-11-06 v11 实际设备复验

用户“已经安装，清理环境重新验证”授权下，核对安装 SHA 后完成白名单清理、App base 构建、外置 Wine 10 冻结绑定与所选模板、Winfile 中文出图、重复准备与安全退出。基础镜像 4.84 → 3.32 GiB，减少约 1.52 GiB；7 个源组件完整内容与原 Native/组件元数据保持一致。默认 Hangover 首次 wineboot 失败，kernel32.dll c0000135；没有有效模板或虚假就绪发布，故 6.3 及全部兼容/恢复验收仍未完成。失败 stage 需手动清理，缓存回收不因此标记完成。源码精简候选未激活，备份 v2 与完整故障矩阵仍缺；详细证据 `.harness/runs/optimize-proot-runtime-storage/evidence/device-validation-v11-20261106/verification-report.md`。本轮只记录验证资料和台账，不改业务代码，不完成产品验收或归档。


## 2026-11-06 自编译 pdb2 直接上机

2026-11-06 用户明确使用自编译版本并要求“不要搞兼容，直接上”：停止独立映射验证，直接 dpkg 安装 pdb2 到当前共享 RootFS。设备 loader/ntdll SHA 与候选一致，包版本 11.16~trixie+idap.pdb2。App 新 Hangover 配置首次准备约 515.7 秒成功，Winfile 中文实际出图，安全退出无运行进程残留；真实 App 终端上下文 32/64 位 smoke 均退出 0，位数标记和 version.dll 动态加载通过。RootFS 安装后 3.32→1.89 GiB，减少约 1.43 GiB；App 运行后 1.90 GiB，最终可用约 2.90 GiB。7 份原有配置/绑定文件内容未变。外部 helper 的路径/上下文/LD_PRELOAD 失败单独保留，不判为已证实产品回归。当前仅替换测试设备现有 base，APK 远程索引/原组件源未切换，网络重建仍会使用官方包；完整 ABI/ODBC、物理手柄、正式来源/身份、备份与恢复矩阵继续未完成。详情 `.harness/runs/optimize-proot-runtime-storage/evidence/device-pdb2-direct-20261106/verification-report.md`。产品一致性 Pending，未归档、未执行 Git 发布。

任务 1.4 的 wineboot、32/64 位和图形子项已有真实通过证据；物理手柄与未覆盖 ABI 仍缺，因此任务不整体勾选。任务 6.5 的正式分发/索引仍未完成，不用当前设备直接替换代替新安装来源接入。


## 本轮分发接入

- [x] 7.1 发布 pdb2 v2 运行制品及来源/验证资料，验证公开下载大小和 SHA，保留 v1；符号与运行包分离。
- [x] 7.2 更新 App 与组件仓库目录至 v2，检查版本/哈希变化自然影响准备与模板身份，执行相关回归及默认命名 APK 构建。
- [x] 7.3 审查并提交、推送已获授权实现与目录改动；保留未完成任务，提供手动 APK 安装入口，不使用 ADB 传输 APK。


2026-11-06 分发接入完成：组件仓库 `main` 已推送 `581eb78`，App 的既有 `master-x11-submodule-tmp` 已推送实现提交 `2069d1dc`。公开 v2 runtime 全量重下载 size/SHA 匹配，设备 HEAD 成功；独立符号 316,123,585 bytes 的 GitHub digest 与候选一致。App 与组件仓库目录一致；633 JVM、35 打包用例、11 个 shell/模块命令全部通过。默认名 ARM64 APK 为 46,545,151 bytes，SHA-256 `b42fa8e41b1e2c5da86add348771124e908a909a394dab38909b4b18d544b546`；新 recipe 为 `6a87fd7bb8562d2f309eb084c45bfb4ab75f20077b1cd0beb977d3203a3c239a`。用户手动安装新 APK 后再做网络全新准备；完整矩阵和备份 v2 等任务不因本轮推送而关闭，未归档。推送后只补本段任务完成记录，不改变已构建 APK 的源码/资产。

## 2026-11-07 网络全新构建与功能复验

用户“既然如此，开始验证功能”授权下，核对已安装 APK 摘要，App 恢复中断任务并真正通过网络构建 pdb2 v2 基础镜像；Wine 版本、loader/ntdll 与 recipe 摘要通过，基础 RootFS 实际分配约 1.892 GiB，可选运行组件仅空挂载目标，base 不预热模板。导入/编辑无配置级后端切换，标题下显示 Backend: PRoot。

全新 Hangover 前缀首轮 321.238 秒失败：Wine 固定 5 分钟启动事件超时后，尚未复制完成的前缀缺 ws2_32.dll。来源 DLL 完整，未发布半成品模板或就绪状态。仅清理本任务失败 stage 后，通过 App 创建新 stage 重试，1207.684 秒成功；重试也出现固定等待超时，观测到 --init 与 -u 重叠执行，具体根因仍需修复实验确认。模板约 185.90 MiB，manifest/归档 SHA 与前缀标记一致。App 新容器 Winfile 中文窗口实际出图。

准备完成时另外捕获 com.termux 主线程真实崩溃：RuntimeSetupConsoleDialog.dismiss/complete 移除已脱离窗口的 View；随后记录无焦点窗口输入 ANR。交互验证中 ADB 连接中断，短暂恢复后再次超时，32/64 smoke、模板复用和安全退出均未完成，不借用此前直接安装的通过结果。1.4/4.2/6.3 及整体功能验收保持未完成。准备期间旧 9 份游戏配置记录摘要未变；断连后无法补最终快照。

工作区重建后验证事实记录：`.harness/runs/optimize-proot-runtime-storage/resumed-verification-state.json`；完整报告及原始证据：`/home/user/workspace/artifacts/hangover-pdb2-resumed-validation/verification-report.md`。权威 Harness 文件与 verify.sh 在恢复源码中仍缺失，strict-run 为 Unavailable，产品一致性 Pending。本轮仅补验证记录，未修改业务代码、提交、推送、部署或归档。


扩容恢复后补充：真实 App untrusted_app_27 终端 32/64 位 smoke 均退出 0、位数标记正确，各约 5.86 秒。126 DLL/约 67 MiB 热缓存普通复制在 PRoot 内仅约 0.42/0.37 秒，不能把分钟级安装称为普通文件复制慢。独立新前缀仍复现 --init 未完成、固定五分钟事件等待超时、-u 再次安装的重叠；DefaultInstall 单阶段约 214.458 秒，源码确认还包含 manifest/注册资源处理。探针由操作者主动结束并定向清理，不计为产品失败；Windows API 复制对照与性能细分仍在进行。最终核对旧 9 份配置摘要全部未变；模板复用、交互/安全退出和首次准备修复尚未完成，任务不整体勾选；strict-run 仍 Unavailable，产品一致性 Pending。


进一步对照已完成：同组 24 DLL/15 MiB，PRoot Linux cp 2.250 秒；Windows 批处理复制前后标记间约 12.536 秒（含输出/调度），Wine 命令总计 110.409 秒，退出 0、无超时且文件 SHA 全部匹配。多数耗时在复制之外，Windows 复制自身也有额外成本；不把此小样本当作完整 wineboot 性能归因。诊断结束最终采样无 Wine/PRoot/X11 残留，不代替 App UI 安全退出验收。详见恢复验证报告及 windows-copy-marked-full.json。


## 清空 App 数据后的真实验证（2026-11-07 UTC）

用户已正常安装交付 APK，并明确授权清理数据重验。已 pm clear com.termux、确认空数据后由真实 App 网络构建基础环境及两个配置；首个 prefix 54.463秒、第二个7.558秒，均从 APK 小模板还原，无 wineboot。独立32位探针写入位数标记；64位 Winfile 实际中文菜单及基本输入通过，退出后无运行子进程。关闭控制台/Home返回未复现 Java 崩溃或 ANR，但完整仪器交错矩阵未执行。

中文文件名乱码未通过；只读审计发现共享 RootFS 搬迁后6条 staging 绝对链接失效，包含 locale-archive，与乱码的最终因果待修复对照。另有 dbus-daemon 缺 libexpat.so.1 的 linker 错误。保留现场，不改设备链接或运行库。5.6/整体兼容验收继续未完成；strict-run Unavailable，产品一致性 Pending。证据：`.harness/runs/prebuild-wine-prefix-archives/evidence/clean-app-validation/verification-report.md`。本轮无业务代码修改、Git操作或归档。

## 8. 全新验证发现修复（用户明确要求修复）

- [x] 8.1 实现发布前受控 .l2s 相对化与发布后链接/中文 locale 验证；覆盖双层链接、别名、空格、逃逸/环/缺失和搬迁失败，失败不发布配方且保留旧环境。
- [x] 8.2 修复宿主 dbus/libexpat 的显式安装与执行探测；覆盖旧库/缺依赖修复成功与修复失败，普通启动不隐式下载；更新前缀准备提示。
- [x] 8.3 完成源码复核、相关 shell/Python/JVM 与 OpenSpec strict；以设备独立实验确认 locale、中文名称及 dbus 可执行，未覆盖项明确保留。
- [x] 8.4 标准编译并交付原名普通安装 APK，校验资产、签名、ABI 与 testOnly=false；用户安装后的全新 App 复验另记，不冒充已完成。


8.* 修复交付说明：10项链接回归、13准备/发布场景、638 JVM、5模板/17前缀/13绑定及launch/recovery/maintenance均通过；两活动变更OpenSpec strict通过。真实AppShell独立目录中，同Wine同中文文件，原locale回退ASCII且CreateFileW失败；规范化迁址后UTF-8/GBK正确且中文读取成功。显式升级libexpat 2.4.8→2.10.0约102KB后dbus可执行。原名普通安装APK为48,409,174 bytes，SHA256 `263be3e5920019f117c7d49b6844302d587b519aa824578ce566db11a5e77581`，testOnly=false。8.*完成不表示整个活动变更验收；用户尚未安装本轮候选，完整清数据App/GUI重验与原5.6仍待执行。详情见`.harness/runs/optimize-proot-runtime-storage/evidence/relocation-repair/verification-report.md`；strict-run Unavailable，产品一致性Pending，无Git或Release操作。


本轮已按用户修复授权交付RootFS迁址/中文locale与dbus依赖修复候选。Unicode中文文件读取、locale及dbus独立对照通过；额外独立Winfile未取得主窗口，保留失败且等待新APK全新真实App验证。没有活跃运行进程，宿主init尚有一个Winfile zombie；诊断前缀和误生成的基础挂载目标内容已定向清理。测试/制品身份见关联修复报告，产品一致性Pending，未归档。


## 修复APK安装后的全新真实App复验（2026-11-07 UTC）

用户已安装SHA256 `263be3e5920019f117c7d49b6844302d587b519aa824578ce566db11a5e77581` 并明确继续验证。按既有授权pm clear后从真实UI完成网络构建及两份prefix：基础2094.389秒，首份72.643秒，第二份165.642秒，均SUCCEEDED。发布前规范化6条链接，发布后verify6条及UTF-8/GBK检查退出0；独立审计无staging绝对断链。宿主libexpat自动升级，真实AppShell中dbus/PulseAudio/Python版本检查退出0。

Winfile真实图形窗口中文菜单、中文目录和中文文件名显示正确；从Winfile执行64位探针写入位数标记，中文CreateFileW探针输出UNICODE_FILENAME_PASS；第二配置32位探针位数标记通过。触摸双击及键盘方向选择通过；正常UI退出后无本轮运行子进程。两前缀公共文件独立，启动删除各18个管理DX DLL后其余1695文件mtime未变。无新增crash/ANR。

整体仍为部分验收：本轮基础构建/复制明显偏慢、完成后Ready状态刷新滞后、Python包postinst预编译环境告警保留待处理；完整生命周期仪器矩阵及原有分发/ABI等任务未执行，5.6及整体任务不勾选。产品一致性Pending，strict-run Unavailable，无业务代码/Git/发布/归档操作。证据：`.harness/runs/optimize-proot-runtime-storage/evidence/repair-apk-fresh-validation/verification-report.md`。
