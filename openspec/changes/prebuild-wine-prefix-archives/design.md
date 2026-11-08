# Design

## Context

问题与测量见 proposal.md 和 artifacts/hangover-pdb2-resumed-validation/verification-report.md。现有 rootfs_prefix_warmup.sh 的 prepare_external_prefix_template 在设备首次构建模板，external_template_valid 校验归档，warmup_rootfs_prefix 克隆后仍可能因缺少 wine-dlls 标记触发更新。当前设备已有模板不是可直接发布的干净参考构建。

## Goals / Non-Goals

目标：在当前 PRoot Hangover/aarch64/WoW64 新安装路径移除设备全量初始化；制品格式保留扩展至其他明确支持架构的能力。每个运行组合一个完整压缩包，不把 WoW64 所需 system32/syswow64 拆开，不根据游戏 EXE 位数选择不兼容 C 盘。其他 Wine/Native 组合只有独立构建并验证之后才声明支持，本期不承诺一次覆盖整个目录。

不将现有 Windows cmd 约百秒的服务启动成本承诺为同时修复；验收必须证实没有全量安装，分别记录服务启动和解压。无焦点 ANR 的因果尚未确认，单独复测，不使用对话框修复成功替代证据。

## Decisions

### 1. 构建时生成，静态组件内携带

新增构建/验证工具产出单个 prefix.tar.gz 及伴随 manifest，内部语义版本、hostAbi、backend、prefixArch、Wine deb/二进制摘要、翻译器摘要、兼容配方、初始化修订、归档摘要/大小、解压体积与文件清单。首期 gzip 与现有工具兼容，压缩算法不是架构身份；后续可独立评估 zstd。

将该子制品随新版本 Hangover 组件携带（独立文件供复用，不纳入 Wine deb 或基础 RootFS 安装目录），复用现有组件下载、size/SHA 校验及 immutable 版本存储。不引入新的 HTTP API 或运行时远程索引字段；组件内容布局、内部 manifest 和版本引用变更须测试。component archive 摘要包含子归档字节，子归档 manifest 绑定 Wine deb 等输入摘要，不把最终外层归档摘要写回自身，避免循环摘要。

不把设备当前游戏 prefix 直接当源包，不覆盖 v2 发布字节；构建输出先是本地候选，Release/索引正式切换待独立授权。

### 2. 参考构建与自然完成

先只读核对用户指定编译机 uname、工具链与目标执行能力。容器本身不被当作第二层隔离承诺；使用独立工作目录和受控 RootFS。优先原生 ARM64 执行；需要模拟执行时使用明确记录且能通过目标复验的执行适配器，不能把交叉编译能力等同 ARM64 运行能力。

目标 Wine/翻译器及基础依赖必须与最终运行身份一致，使用稳定 guest /root 和 /mnt/games/prefix，禁用 Mono/Gecko交互安装，配置中文和字体链接。通过中性首次进程触发一次隐式 --init，监测其完成及全部注册子任务；不得再无条件调用 -u。固定启动事件超时、命令非零或子任务未完成均拒绝发布候选，不能把文件数量增加当作完成。成功后安全停止 wineserver、等待全部相关进程结束，保留构建日志及输入摘要。

归档保留必要注册表、DLL、字体与合法 Wine 链接；去除主机/用户私密信息、设备绝对路径、构建临时记录和 App 专属就绪标记。必须在另一个绝对路径/隔离测试前缀中证明可移植性。

### 3. 身份、选择与安全解压

将预生成输入身份和初始化修订加入当前完整模板键。已选组件到模板的路径由受信任组件解析产生，不接受游戏/归档任意指定输出路径。校验 manifest 字段及数值范围、SHA、体积上限、条目名、重复成员、硬链接/符号链接链；拒绝绝对成员名、..、特殊设备/FIFO及链接越界写入。Wine 的 dosdevices/z: 指向 guest 根是显式受控特例，不能因为该链接合法便允许后续归档成员沿它写入。宿主不能跟随该链接读写验证外部路径。

在任务私有 staging 解压并核对注册表、system32/syswow64 核心 DLL 和清单。沿用持久互斥及同身份缓存复用；空间预算包括压缩包、展开体积及发布期间重叠，下载在 tmpfs 等位置不能错误忽略。取消/失败只回收本任务暂存，不写成功标记，保留已有游戏与有效缓存。

### 4. 设备只解压及独立发布

prepare_external_prefix_template 对首期组合改为消费预生成子制品。没有有效预生成输入时返回明确错误，不能转回 warmup_rootfs_prefix 生成模板。新游戏在新目录解压，并由 App 写实际 runtime、translator、wine-dlls、prepared-identity 等运行标记；只有通过完整核对后才发布就绪。

现有 .update-timestamp=disable 仅在精确身份及安装完成证据成立后使用；它不能掩盖缺失注册表、必要 DLL 或不同版本模板。同步消费分支，避免解压后因标记缺失又进入 seed_missing_prefix_dlls / wineboot -u。普通启动仍允许 Wine 自身启动服务，但不能出现安装/注册全量阶段。原有非空 C 盘不得覆盖，用户新安装授权不等于可任意删除游戏文件。

### 5. 控制台生命周期

RuntimeSetupConsoleDialog 的完成/dismiss、terminal/session observer 与 Handler 回调在主线程串行，幂等释放。跟踪窗口实际附着和宿主 Activity 结束/销毁状态；生命周期结束先取消轮询、清除监听及引用，避免延迟回调访问旧页面。若窗口移除存在不可消除的竞态，只处理已确认生命周期导致的特定异常，并记录上下文，不用 catch Exception 隐藏错误。

Service 是后台准备任务的持有者；停止 UI 观察不等于取消后台任务。确认 preparation.stop 的真实语义后调整调用位置，复用既有显式取消路径，防止完成状态被覆盖。失败保留控制台日志，重新打开从持久任务恢复；不改终端引擎。

### 6. 各层边界与关联规范

Activity 只展示/观察；Service/任务管理持有状态；shell 负责验证解压及就绪发布；组件层负责下载与 immutable 身份。X11 仅构建及游戏启动按原需使用，设备解压不额外启动 X server；输入与手柄不变。Native/PRoot 保持独立，不在编辑页开放切换。旧 optimize-proot-runtime-storage 中“设备首次构建”条款需在实施时改为预生成支持组合的例外，旧证据与未完成任务保留，不伪造正式基线。

## Risks / Trade-offs

- 制品增加下载字节，但把漫长初始化移出设备；记录压缩/展开尺寸及实际解压耗时后判断效果，不预承诺秒数。
- 构建机未必可运行 ARM64 → 先验证执行适配器，再生成参考模板；若不可用明确记录阻断，不拿旧游戏目录替代。
- 预生成注册表与路径依赖 → 跨目录重定位、中文、32/64 smoke、Winfile及普通启动无全量安装的实测。
- 模板损坏和恶意链接 → 结构/摘要校验、私有 staging、安全失败和目标不可见性测试。
- 对话框竞态难以 JVM 覆盖 → 单元生命周期状态测试配合真实离开/旋转/重入、完成及取消交错实测。

## Migration Plan

只支持新制品身份的新安装，不迁移旧模板/备份；不新增备份 v2 承诺。先本地候选、测试和原命名 APK 构建；用户批准发布和手动安装后再做设备验证。回滚恢复完整旧组件选择/初始化配方和 APK；候选不替换既有制品，已有 C 盘不被回滚步骤重写。

## Protected Paths 与流程限制

权威 policy 文件在恢复工作树缺失，policy id 为 Unavailable，不捏造编号。潜在高风险范围为分发/组件版本描述、共享 RootFS 准备与模板发布脚本；此规划明确其影响、上面的回滚及 tasks.md 验证，需求设计确认时同时确认此范围。发布、Git stage/commit/push、部署和设备清理不包含于本次规划批准。Harness strict-run 不可执行，OpenSpec CLI strict 只能证明文档格式，不能替代 Harness 或产品验收。

## 固定资产体积实测补充

用户进一步询问是否可作为固定资产、免除 App 安装后的初始化。可以：构建阶段完成 wineboot，发布不可变模板资产；App 准备新游戏只校验、解压和写入该游戏标记。模板可独立下载，也可嵌入 APK；首期优先独立资产以保持 APK 小且运行库更新无需将所有模板重打入 APK。

当前现成模板 gzip 为 194,927,112 bytes（185.90 MiB），模板 manifest 的 prefixKiB=880576（约 859.94 MiB）。后续执行过测试的游戏前缀当前实际分配 871,448,576 bytes（831.08 MiB），不是同一时点的干净归档展开测量，因此不能用此差值宣称压缩或裁剪收益。主要内容为 system32 与 syswow64 的 DLL，注册表约 3.2 MiB。设备每游戏仍需独立可写展开目录，不直接在压缩包运行或共享可写模板。最终构建产物体积需重新核对；证据 artifacts/hangover-pdb2-resumed-validation/prefix-asset-size.json。

## Winlator 对照后的体积优化候选

用户指出完整前缀资产偏大后，对官方 Winlator 固定源码及归档完成只读核对：其 container_pattern 约 7.06 MiB，默认容器通过 common_dlls 清单从公共 Wine 目录补 DLL。我们既有模板的 1713 个文件与公共 Wine/字体逐 SHA 匹配；排除这些重复内容的残余 tar 经 gzip-6 试压约 10.65 MiB（原完整包 185.90 MiB）。证据为 artifacts/winlator-packaging-comparison/comparison.md 与 our-template-duplicates.json。

建议在方案确认时采用“小模板＋精确文件还原清单”：只分发注册表/目录/专有文件，公共 DLL/字体由已验证运行库复制到各自独立 C 盘。归档安全及每游戏独立可写要求不变；新清单也必须验证源/目标路径、SHA、身份与文件类型。该候选尚未完成真实还原及启动验收，不以压缩试算宣称已实现，也不承诺每游戏展开空间同样减少。确认实施前同步 proposal/spec/tasks 的还原要求，不能仅改设计绕过规范确认。

## 当前 APK 试用接入授权与范围

2026-11-07 用户明确要求“接入apk试试”，批准本轮实现及上述共享 RootFS 准备脚本、身份和分发输入范围。采用已独立还原验证的小模板＋公共文件 SHA256 清单：APK 内嵌约 3 MiB 本地试用资产，以当前自编译 Hangover pdb2 的精确 loader、翻译器及公共文件摘要绑定。清单和归档参与配方身份；每游戏复制独立文件，先暂存校验再发布，不在设备重新 wineboot。其他运行组合不替换。容器裁剪延期。

本次是本地 APK 候选，不发布/覆盖现有 v2 组件，不新增虚构 URL；未来正式组件发布及编译机可复现生成仍保留未完成任务。资产来自独立实验前缀清洁化，删除 App 运行标记，记录来源与未完成图形验收；不是从用户游戏前缀导出。APK 安装后经真实 App 显示链路继续中文/图形验收，不以控制台通过替代图形通过。每游戏展开仍约824 MiB。Git、Release发布和设备安装不包含在本轮操作内；用户自行安装。


## 普通 Wine/Proton 后续扩展（2026-11-08）

用户已批准 `reuse-prebuilt-prefix-for-standard-wine`：复用同架构初始化骨架，各目录 Wine 使用自己的系统 DLL 与精确组件身份。普通 Wine 10.0/Proton 11.0 新配置改为只恢复模板；本文件先前“其他运行组合不替换”等表述限于首期历史阶段，自定义 Wine 仍保持原流程。实施及验收由新变更追踪，未提前归档或宣称完整兼容验收。
