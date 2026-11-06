# Design

## Context

体积动机与口径见 proposal.md 和独立测量报告。当前 checkout 的基础配方为 v10，默认七个组件；guest 清单 schema 2，setup spec schema 3，LaunchSpec schema 6，基础环境备份输入与归档清单均为 schema 1。

现有 `RootfsSetupForegroundService.enqueue()` 先准备整个组件集合，再创建 RootFS 任务；`setup-container.sh` 安装 Hangover deb 并复制 portable Wine、Box64 和 DXVK。构建完成后 `build_prefix_templates()` 串行预热三套模板。`RootfsProotBackend` 的组件需求、清单校验、启动及维护脚本均假定相应文件已嵌入 RootFS。`runtime_complete()` 在构建与备份脚本有重复定义，并硬依赖 Box64。

当前手柄脚本对 Hangover 会修改 guest 的 `/usr/lib/wine` 并保存 `.stock`；portable Wine 的 PRoot 路径只向游戏前缀写手柄 DLL。因此不能把所有 Wine 目录简单替换成“只读挂载”。当前 PRoot bind 也不是内核强制只读能力。

正式 `openspec/specs` 为空；历史 change 中双后端、组件交付与资产恢复约束作为参考，现代码及 docs/project-status.md 是当前事实依据。权威 Harness 文件与正式产品知识目录缺失，限制记入运行台账，不创建伪造的规则或产品基线。

## Goals / Non-Goals

**Goals:** 在保持可运行性与可恢复性前提下减少常驻实体和无关初始化；将精简包、组件引用和模板缓存拆成可分别验证的交付阶段；以全新安装为交付边界，不承担旧测试数据迁移。

**Non-Goals:** 本轮不对 Hangover 的 Debian 安装本体做任意目录外置，不引入 union/overlay 内核依赖，不声称 PRoot bind 提供安全隔离，不改变 Native、桌面、X11/输入底层及现有显式渲染选择。

## Decisions

### 1. 固定源码构建与链接期符号分离，正式索引独立接入

用户在实施中明确改为源码重编译，并指定远程编译机。固定 Hangover `hangover-11.16` commit `56e385b7ff490edd281b288f420677093563560b` 及其 Wine gitlink `3ea3a14941b6a122b892c84141ce9662c008081e`，不追踪移动分支。编译机为 Ubuntu 18.04 x86_64 容器，允许 chroot、不能创建挂载命名空间；使用独立 Debian 13 amd64 用户空间交叉编译 arm64，不需要嵌套容器或修改已有 Wine 工作目录。chroot 只隔离依赖，不能描述为安全沙箱。原始 rootfs、LLVM-MinGW 及全部产物记录 URL、SHA-256、源码身份和工具版本。

优先使用 Wine 上游已有 `CROSSDEBUG=pdb`，由编译器生成 CodeView、链接器输出独立 PDB；PE 不嵌入 `.debug_*`，不再对 ARM64X 运行 DLL 做发布包后处理。验证各 DLL 的 CodeView GUID/age 与 PDB 对应，保留构建日志、配置、源码锁与符号清单。ELF 使用常规分离 debug 流程。不得通过 `-g0` 丢弃全部符号冒充符号分离；若 PDB 不支持或不匹配则本次构建失败，不能切正式索引。

保持原上游 Debian 13 配方中的 ARM64EC/aarch64/i386、功能依赖、资源、Wine builtin 标记与对齐约束。原包实际路径、模式、链接、依赖控制字段和维护脚本作为基线，额外开发库不得静默启用原包没有的模块。本次显式使用 `--without-opencl --without-pcap` 对齐原包缺失的两个可选后端；这些选项不是删除原包已有能力。源码候选与旧包字节不要求相同，但应检查组件/架构/能力清单、导入导出、异常展开、load-config/混合架构元数据并做真实运行验证，不用旧包和新编译文件逐字节相等替代兼容验证。所有生成文件发布至独立候选目录和独立包修订，旧资产不覆盖。FEX/Box64 是否重编译在验证固定 gitlink 和上游构建依赖后记录；复用的原组件必须保留其摘要，不误报为本次编译。

先前发布包 strip 工具保留为实验路径，不作为交付入口。LLVM 19/22 在原 `advapi32.dll` 的 `--unwind` 即报 Malformed unwind data；源码审阅确认其 ARM 解码器遍历整个 `.pdata`，没有按 exception directory 限制 native 表，可能误读同节后附 hybrid 表。该实验没有产出成功候选，不能跳过该错误声称全部校验通过。源码构建仍需明确记录验证工具对混合架构的能力边界。

正式索引在候选兼容验证、最终来源发布与 size/SHA 校验完成前不切换；发布另需明确授权。用户指定的 SSH 编译和构建依赖准备属于本轮已授权范围，不包括用户设备部署、Git 提交/推送或外部 Release 发布。认证信息不写入仓库、构建日志或文档。

### 2. 默认基础环境与按需组件分离

第一版保留固定 Debian snapshot、默认 Hangover 与必要 ABI/音频/图形/中文/GBK 依赖；`BASE_ONLY` 只准备默认 Hangover 源输入。独立 Box64、Wine/Proton 和 DXVK 不再复制进新基础 RootFS。原 Hangover 压缩 deb 与客体安装并非两份同格式运行库，暂时保留其输入以支持恢复。

新 recipe 使用独立修订 v11；guest manifest schema 3 明确区分内置能力、外置组件支持与实际绑定，不把“支持某组件”误认为“该版本已安装”。基础就绪检查不依赖外置可选组件；配置就绪检查另验证所选完整依赖闭包。Java 和所有 shell 入口使用一致语义，公共 shell 检查集中在已有 rootfs helper 或专用辅助文件中。

`RootfsProotBackend.requiredComponentIds()` 按所选运行库、翻译器与 dxWrapper 计算最小集合，避免当前不同 Wine 前缀导致的需求不一致。WineD3D 不拉取 DXVK，Hangover 内置翻译路径不拉取独立 Box64。自定义组件保留现有 receipt 与架构验证，同样形成冻结绑定；本轮不趁机扩大支持的组合。当前 `install_custom_rootfs_component.sh` 将实体发布在 guest `/opt`，新布局需要改为发布私有版本实体并登记绑定，guest 路径保持原协议；旧自定义实体不迁移，新导入直接使用新版本根。

### 3. 冻结组件绑定与一致 guest 视图

复用 `ComponentInstallationReader` 的安装身份，新增可持久的 PRoot 绑定记录，至少包含：组件 ID、组件版本、摘要、私有版本目录、内容根的相对路径、guest 目标、角色与绑定修订。主机绝对路径仅在本地任务快照中使用，备份以身份和相对路径表达。

配置准备完成时绑定版本；启动、warmup、模板构建、维护工具与备份都消费相同冻结描述。新 setup spec schema 4 与 LaunchSpec schema 7 增加绑定集合，限制数量、角色、canonical containment 与固定 guest 目标；本轮不要求旧 PRoot schema/任务继续消费；旧 PRoot 任务不自动恢复或原地改写，应清理或明确拒绝。可保留共享解码器的无害兼容分支，但不为此保留旧配方资产。Native 的编码继续输出 schema 6，PRoot 新绑定才输出 schema 7，共享 decoder 按后端验证两者，避免 Native shell 仅接受 v6 时被全局版本常量升级破坏。

保持已知 guest 路径以减少 Wine 重定位问题，例如 portable Wine 仍见 `/opt/box64-wine` 或 `/opt/box64-proton`、Box64 见版本化 `/opt/box64/<id>`，DXVK 见 `/opt/games-runtime/<id>`。这些路径只作为挂载空目录存在于新 RootFS。挂载来源是冻结版本实体；不得启动时再跟随 active 指针，也不得复用用户可控的任意 `-b`。构建、启动和维护所有 PRoot 命令分支必须统一增加绑定，包括音频两条分支。

DXVK 安装和 controller portable 路径当前通过宿主文件路径读取 DLL，需要改用同一冻结内容根，不能仅在 guest 增加 bind 后继续从空的 RootFS 目录复制。向 prefix 覆盖 DLL 前解除目标链接，防止写入共享源。Hangover 继续安装在 guest，现有手柄 `.stock` 行为保留并纳入回归。对外置树禁止程序主动写入，通过前后哈希、链接陷阱和异常写路径测试验证；文档不将此描述成面对任意同 UID 程序的安全只读沙箱。

组件升级只切换 active 指针，不移动旧版本。配置、运行任务、模板构建及新布局备份/恢复引用形成保留集合；引用扫描、绑定发布和清理在同一互斥范围内执行，避免检查后删除。无需为本轮新增自动淘汰用户运行库。

### 4. 持久准备任务与锁顺序

Activity 继续只负责发起任务和展示进度，I/O、目录扫描和 Wine 初始化全部在 Service/后台。现有准备任务先完成基础依赖与所选组件下载，再发布本次冻结绑定，再准备模板和独立 prefix。全过程可见的父任务或持久准备记录必须覆盖“组件准备前尚未创建 RootFS task”的窗口。

复用组件下载断点续传、校验和失败状态，不从 shell 发起第二套下载。调整 `RuntimeInstallationGate` 以识别同一父任务的依赖子任务，避免一边持有 RootFS 安装槽、一边等待被该槽拒绝的组件任务。锁顺序固定为：依赖准备协调 → 全局运行时变更槽 → 版本引用/绑定 → 模板身份 → 前缀；下载网络等待不持有 shell 独占变更锁，组件任务保留父关联供恢复对账。

准备记录描述依赖准备、基础准备、模板准备、prefix 准备与完成阶段，不假定一定需要新增 public 状态枚举。取消与进程恢复以持久记录为准，释放本任务锁与进程树，保留已校验不可变组件。正常启动若未准备则明确失败并指向准备流程，移除启动脚本中的隐式 warmup 回退；不能借优化修复非空损坏 prefix。

X11/音频沿用现有任务拥有者与清理语义。模板构建串行复用既有单 X 会话逻辑，禁止按每个模板反复重启留下半退出会话。新绑定同样用于 wineboot、wineserver、字体处理与退出路径。Native 手柄和原启动链保持原行为。

### 5. 模板按完整身份按需构建

删除 base build/restore 的全包预热循环；实际配置准备在缺少有效模板时只请求当前身份。模板键由规范编码的 Wine ID/version/SHA、实际 translator ID/version/SHA 或内置 translator 身份、架构、基础兼容 recipe hash、字体/初始化修订生成 SHA-256；默认 Hangover 以其包身份和内置 translator 修订参与计算。

缓存继续位于 `games-template-cache`，采用新版本子目录与 sidecar manifest，记录身份、归档 SHA-256、构建状态。归档在临时路径生成并检验后原子 rename；按身份构建锁及持久 task owner 处理重复请求，进程死亡后按任务对账回收失效锁。哈希在后台计算，不在 Activity 主线程校验大归档。

旧的仅按组件 ID 命名的模板不导入新缓存；全新安装直接删除旧缓存，按实际选择生成完整身份模板。有效的独立 prefix 不因缓存键变化重新初始化；非空无效 prefix 继续报显式恢复/重建错误。

缓存清理提供现有组件管理流程内的显式操作，计算配置、任务与新布局恢复引用，仅回收无引用模板和安全暂存。禁止以空间不足为由自动删除 active/previous 或独立游戏目录。

### 6. 全新安装与新布局备份恢复

用户于 2026-11-06 明确“不用考虑老数据，当个全新安装对待”，覆盖此前保留旧环境的迁移要求。此次不实现 v10/manifest v1-v2/旧任务/旧模板/旧备份/旧本地组件迁移，不为旧布局维持双配方或 previous。共享 Native 编码/启动能力保留。历史验证证据留存，不将旧兼容测试删除冒充新功能完成。

设备重置限 PRoot：停止 App 及其运行会话，核实没有运行进程后，删除 `usr/var/lib/proot-distro/games-shared-rootfs`、`games-template-cache` 和 `front/runtime/setup` 旧准备状态；新布局绑定记录若存在也重置。保留可复用的组件版本源、宿主 Termux、Native 前缀和外部文件，不执行整应用 pm clear。实际清理前后记录磁盘与保留数据摘要；本次无旧环境回滚，失败后从干净状态重新准备。

新环境仍在 staging 内构建，验证基础清单、配方与当前依赖之后发布。中断的 staging 不可被当作就绪，恢复只能重试或清理该任务半成品。空间预检计入新 RootFS、当前所需模板与 prefix、暂存峰值，不再计入本次已删除的旧 RootFS 回滚副本。新布局日后显式重建或恢复时，已就绪目标仍须在新候选验证成功前保留，防止失败破坏当前可用环境。

新格式备份输入和归档清单增加布局与依赖闭包：base、备份时全部已就绪 PRoot 绑定的去重版本、receipt、摘要、guest 映射和绑定记录；模板可选。备份在冻结引用与变更锁下采集一致视图。恢复校验成员路径、链接、大小界限、摘要与依赖闭包后暂存发布，相同摘要版本可复用，冲突版本保留原件并失败。恢复不依赖联网补齐，不导入原设备绝对路径。旧格式备份可直接拒绝并说明不支持，无需迁移。备份新闭包未完成时不得提供遗漏外置依赖却宣称成功的归档。

### 7. 验证与收益判定

先为制品处理做成功/失败与 ARM64X 静态比对，再做隔离设备 smoke；未获得部署授权前仅生成候选，不安装到用户当前环境。布局和模板完成后执行定向 JVM、shell 合同与故障注入，再构建 APK，按用户既有带宽偏好提供手动下载安装。正式设备验收从 App 入口完成，脚本诊断不替代 UI 结果。

对同一输入与组件选择记录优化前后 RootFS、唯一组件实体、模板、暂存、瞬时峰值和总占用。至少覆盖：Hangover-only 基础构建无无关下载；两个游戏相同 Wine 不复制整树；更新 active 后旧任务仍用原版本；模板首建/复用/损坏/取消；新安装后非空 C 盘损坏保留；备份到空组件目录离线恢复；首次构建中断重试与新格式恢复失败保护；32/64 位、图形、手柄和退出清理。未验证项保持未完成。

## Risks / Trade-offs

- ARM64X 工具破坏运行元数据 → 独立候选、严格前后比对及真实运行验证；失败不切正式索引。
- 外置运行库被误写或旧版本被淘汰 → 冻结内容根、引用保留、链接替换与哈希回归；不承诺 bind 安全只读。
- 首次配置准备变慢 → 进度、持久状态、取消恢复与后续模板复用；不把耗时挪到隐式启动。
- 分层后备份遗漏导致恢复不可运行 → 备份依赖闭包与空目录离线恢复必须作为发布门禁。
- 首次安装和新布局重建有暂存峰值 → 提前空间检查，区分首次清理释放和最终去重收益，不再为本次旧布局预留 previous。
- 历史 OpenSpec 的 active pointer 设计与当前固定共享目录存在漂移 → 本轮按当前固定共享目录设计事务发布，记录历史差异，不重写历史规范掩盖。

## Fresh Installation Plan

1. 依据用户新授权清除旧 PRoot 基础环境、旧模板与准备状态，记录不可回滚的测试数据清理；保留可复用组件源和 Native。
2. 接通 v11 单布局、冻结绑定、引用保护与按需模板，不再维护旧 PRoot 配方迁移链。
3. 完成新格式备份闭包与空目录离线恢复；拒绝不支持的旧备份，禁止输出残缺新备份。
4. 构建 APK 供用户手动安装，按全新 PRoot 准备验收；中断不发布半成品。
5. 精简包验证与正式发布授权齐备后，单独确认真实 URL、size/SHA 并更新目录身份；不覆盖旧 Release。

## Protected Paths 与人工检查点

当前权威 policy 文件缺失，无法证明完整策略清单。历史设计中可追溯 policy id 为 `runtime-script-critical`（`openspec/changes/add-games-dual-runtime-backends/design.md`），本轮按高风险路径处理，不把历史标识冒充已恢复的权威策略。

| 范围 | 影响 | 验证 | 回滚 |
| --- | --- | --- | --- |
| `local-games/setup_rootfs_runtime.sh`、`runtime-rootfs/**`、`rootfs_prefix_warmup.sh`、`rootfs_script_common.sh` | 基础构建、就绪、模板和全新安装 | 新布局、损坏保护、空间与故障恢复 | 清理前记录范围；旧测试环境不回滚，新构建失败可重试 |
| `local-games/start_rootfs_game.sh`、`install_custom_rootfs_component.sh`、必要的 `controller_runtime.sh` 及辅助脚本 | 版本绑定、DXVK、手柄与启动边界 | argv/路径/绑定、音频两分支、32/64 位与进程清理 | Native 合同保留；新 PRoot 准备失败不发布 |
| `local-games/backup_restore_rootfs.sh` 及备份 Service/codec | 离线完整性、并发和事务发布 | 空组件目录离线恢复、恶意路径/摘要、中断回滚 | 旧备份明确拒绝；新布局恢复发布前保留当前环境 |
| `RuntimeInstallationGate`、组件版本引用及相关 Service | 任务 admission、更新与清理 | 并发、父子任务死锁和重启对账 | 失败不切绑定与 active |
| `distribution/termux-app-packages/**` 与条件性 `termux-app-packages/index-v1.json` | 候选制品、供应链身份 | 原始摘要、ARM64X、来源验证 | 旧资产与目录版本保留 |

上述路径的修改已随物化规划交由用户确认；用户随后明确“开始实现”，本轮记录为规划与所列高风险路径范围批准。原实现确认不包含 Git stage/commit/push、PR、外部发布、部署或归档授权；本次清理另由用户“当个全新安装对待”明确授权，范围限上述旧 PRoot 环境。

## Open Questions

精简工具的可用版本与实际兼容性在制品试验任务中确定并固定；若无法通过 ARM64X 前后比对或运行验证，该任务保持阻塞并报告，不能自行降级完整性要求。当前无需用户提供新业务参数；最终发布才需要另行获得相应操作授权与可用认证。


## 2026-11-06 pdb2 测试阶段分发决策

此前禁止发布和推送的文字记录原阶段授权边界；用户本轮明确要求适配自编译 Hangover 并使用 PAT 推送制品和代码，新增授权以本节为准。根据已通过的 App wineboot、Winfile 实际图形、32/64 位程序和退出验证，发布不可变 v2 测试组件；完整 ABI、手柄及整个存储改造验收仍保持未完成。该测试分发无需伪称全部兼容矩阵完成。

运行包沿用发布资产名 `rootfs_hangover_11.16.tar`，放在独立 v2 tag 下，保留 v1。原始候选 manifest 与补充运行证据分别提供，历史 NotRuntimeTested 状态不得篡改；符号保留为独立开发制品，不写入运行 tar。先验证实际公开 URL 下载字节、大小和 SHA，再改 App 和组件仓库三个目录。新 SHA 由现有 RootfsSetupAssetInstaller 自然进入 recipe/template 身份，不写假 receipt，不重用旧身份。对先前已手工替换的测试设备，本次不追加旧数据兼容路径；集成 APK 的网络全新安装验收待手动安装后执行。

回滚：恢复目录到 v1 的版本、URL、size/SHA 并重新构建；旧资产始终保留。认证只在临时进程内存使用，不写入 Git、remote 或日志。代码推送到与当前 HEAD 对应的现有 `master-x11-submodule-tmp` 分支，不强推、不切换或改写其他分支。外部原生依赖中预先存在的构建脚本补丁由 Gradle 生成，本轮不提交到上游 submodule。
