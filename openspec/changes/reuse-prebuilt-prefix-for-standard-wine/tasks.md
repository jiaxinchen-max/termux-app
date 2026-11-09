# 任务

- [x] 1.1 核对现有普通 Wine 依赖、组件挂载、模板生成和恢复校验分支，形成增量设计。
- [x] 1.2 用户确认本规划及运行准备脚本/恢复工具修改范围，记录实施授权。
- [x] 2.1 审计兼容模板骨架与所选 Wine DLL/PE 架构映射，扩展元数据和打包工具，保留可复现来源与摘要。
- [x] 2.2 扩展安全恢复工具与资产安装器，支持受信任的所选组件源和独立文件恢复，不放宽路径/摘要/原子发布校验。
- [x] 2.3 将普通 Wine/Proton 接入免完整初始化路径，更新配方及运行身份，保持 Hangover 回归与非空保护；同步旧活动变更范围。
- [x] 3.1 完成架构、错包、来源越界、损坏、取消、缓存及路由定向测试，直接源码复核。
- [ ] 3.2 独立环境验证首次/第二配置、PE32/PE64、中文与图形及无完整初始化；记录时间与覆盖限制。
- [x] 3.3 正常构建原名 APK，核验 ABI、签名、testOnly=false 和资产摘要，提供用户手动安装。
- [ ] 3.4 用户安装后真实 App 复验，最终接受后更新产品知识；不自动提交、推送、发布或归档。
- [x] 3.5 修复App更新误报镜像必须重建：分离镜像安装与配置准备身份，回归有效镜像复用、错镜像拒绝、启动凭据一致，重新交付原名APK；禁止以重建绕过。

2026-11-08 用户明确“批准执行”，批准本规划及运行准备脚本/恢复工具范围。

- [x] 3.6 修复预构建资产缺失菜单程序的遗留启动项：仅规范化已知命令，记录来源，保留其他服务，重新生成两套模板并验证。
- [x] 3.7 修复PRoot取消后脱离父进程的Wine残留：会话精确归属、TERM/KILL有界清理、清理后终态，回归无关会话保护及正常/取消/信号/超时。
- [x] 3.8 审查两项修复并构建原名正常APK，核验签名、testOnly与资产摘要，交付用户手动安装后再做设备验收。

2026-11-08 用户明确要求修复实际验收发现的两项bug，授权3.6–3.8及启动脚本修改；不包含Git推送/发布/归档或设备wineboot与组件补装。

3.6–3.8本地实现、定向回归、代码复核及APK交付完成。84项Python、19项JVM、完整启动shell场景和真实主机Wine/Proton PE32/PE64及服务清理通过。Android新APK已手动安装并核对完整SHA256；3.2/3.4仍未完成，准备耗时波动仍待查。

2026-11-08 实际App复验：Wine10两份全新prefix分别176.136秒/8.814秒恢复，PE64/PE32探针及桌面通过；Proton11首份48.634秒（含首次固定组件下载）恢复，PE64探针通过。三次均未重建共享镜像、未执行完整wineboot、未出现缺失winemenubuilder启动错误；App内退出后三次均无精确会话归属进程/主脚本/任务锁残留。原有18份元数据摘要保持一致。

Proton11出现err:seh:install_bpf低地址映射导致未安装seccomp，按用户严格要求终止后续功能验证。探针PASS不代表此运行时路径正常，也不据此宣称游戏崩溃。未修改参数、组件源码、注册表或内核设置绕过；Proton第二配置/PE32、真实App中文与Winefile细项仍未执行。证据为.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/runtimefix-android-acceptance.json；产品文档及用户最终接受保持Pending。

- [x] 3.9 按用户“补一下验证”补充同一PE64程序的主机/Android普通API与直接syscall对照，记录实际语义失败后停止后续测试，并验证App内退出清理。

2026-11-08 直接syscall补验：同版Proton主机普通/直接NtQuerySystemTime均成功；Android实际App普通API前后均成功，直接syscall返回0xfffffff2（低32位-14）且未写入时间输出，确认此路径有功能bug。复用既有预构建前缀及组件，未改参数/注册表/运行时，无完整初始化；App内退出后无会话进程/锁残留，原有30份元数据与RootFS安装身份不变。证据：.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/proton-direct-syscall-validation.json。未继续其他功能测试，3.2/3.4不因此完成，未修改业务实现。

2026-11-08 用户明确“批准”，批准以下 SUD 对照、源码修复与独立构建验证范围；不包含正式组件替换、发布、Git 操作或完整 prefix 初始化。

- [x] 4.1 检查编译机现有 Wine 11.19 构建、依赖及源代码身份，准备独立 SUD 对照与无副作用探针。
- [ ] 4.2 使用独立预构建 prefix 副本验证 Wine 11.19 的普通 API 与直接 syscall，记录环境、返回值、时间语义与精确清理；任何实际功能失败即停止后续功能测试。
- [x] 4.3 以设备当前 Proton 精确提交准备隔离源码，移植上游 SUD 及必要配套实现并复核，不覆盖旧目录。
- [x] 4.4 独立构建固定来源候选，核对依赖、架构及摘要，记录可复现构建材料。
- [ ] 4.5 对修复候选执行同一语义探针与相关回归；失败停止且无兜底，不以 Wine 对照替代 Proton 验收，产品接受继续 Pending。

2026-11-08 用户进一步明确 Native glibc / PRoot 加载与动态库路径必须分开。现有 Wine 11.19 包经审计属于 Termux glibc 目标，包含固定 Termux glibc 路径及 i386 Unix 加载器；主机启动在缺少 /lib/ld-linux.so.2 处停止，未进入探针，不能当作 PRoot/SUD 结果。4.1/4.2调整为先拒绝错误目标，再独立构建 PRoot 对照；不通过补加载器或跨后端借库继续。4.3已准备精确提交的隔离源码，但移植与构建尚未完成。

2026-11-08 编译目标纠正后的证据：官方 Wine 11.19 PRoot 独立构建成功，35个 ELF 静态检查通过，仅 x86_64-unix，未发现固定 Termux glibc 路径；PT_INTERP 为 /lib64/ld-linux-x86-64.so.2，动态依赖及 GLIBC 符号版本已记录。原 glibc 包继续拒绝。Proton 精确基线 SUD 移植及直接源码复核完成，候选仍在构建；编译机缺少的开发依赖及 Linux UAPI 头文件在构建端补齐，未更换运行时 libc/加载器。尚无新候选运行通过结论。证据位于 artifacts/standard-wine-prefix/sud-fix/backend-audit-snapshot.jsonl 和 source-review.md；4.2/4.4/4.5 保持未完成。

2026-11-08 后续依赖门禁未通过：Proton 在独立 Debian13 SDK 构建成功，44个ELF格式/路径检查通过，但固定精简RootFS缺少 libgstgl-1.0.so.0 及其 ARM64 传递依赖 libgudev-1.0.so.0（总726344字节）；旧固定Proton组件也声明同一gstgl依赖，属于已有镜像依赖闭包缺口。按用户严格失败终止规则，停止Android探针及可用包交付；未补库、未替换镜像/正式组件。4.4/4.5不标完成。证据：.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/proton-sud-rootfs-dependency-audit.json；完整来源、闭包与待确认后续范围见 artifacts/standard-wine-prefix/sud-fix/target-audit-result.md。Wine11.19正确目标的主机API/直接syscall对照通过，Android仍未执行；4.2保持未完成。

2026-11-08 用户批准执行固定镜像依赖闭包修复，授权以下构建工具及独立候选验证；不包含正式引用替换、发布或 Git 操作。

- [x] 5.1 增加外置 Wine/Proton 与固定 Box64 包装映射的递归依赖审计和保留清单，测试缺库、传递依赖、错误架构和错误后端路径。
- [x] 5.2 固定 Debian ARM64 包来源及摘要，在构建端生成独立完整镜像与新身份；验证旧镜像不变、闭包完整及安全恢复。
- [ ] 5.3 静态门禁通过后恢复独立设备环境，验证媒体加载、直接 syscall 和 PE32/PE64及会话清理；失败立即停止，保留明确未覆盖项。

2026-11-08 本轮完成构建端外置闭包审计、源码声明别名解析、原生文件/链接保留及能力减少拦截；102项定向测试通过。固定Debian ARM64包已在独立镜像副本补齐GstGL和GUdev，新增原生库726344字节；原归档摘要不变。树内1403个ARM64 ELF直接依赖检查通过，但联合外置审计失败：旧Wine和Proton各9条不可解析边，涉及FFmpeg56/58、ISDN、相机、扫描仪、智能卡和pcap；新SUD候选少7个旧Unix模块，configure明确因开发依赖缺失关闭Vulkan、FFmpeg等功能，能力保留门禁失败。此前“构建成功”仅为编译/install成功，不等于完整运行能力保留。已停止功能验收；未封存成功保留凭据、未生成新RootFS归档或替换正式引用、未构建APK、未在设备运行候选。4.4、4.5、5.2、5.3保持未完成。证据见.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/rootfs-external-closure-fix.json及artifacts/standard-wine-prefix/sud-fix/rootfs-closure/RESULT.md。

2026-11-08 用户批准“补齐后继续”，继续5.2/5.3，并增加明确实施检查：

- [x] 5.4 补齐独立SDK所有原能力对应开发依赖，按同一Proton基线和SUD补丁在新build/stage目录重编，逐项复核configure和模块/动态库差异。
- [x] 5.5 固定历史FFmpeg与未包装设备库的真实ABI来源，补齐原生/模拟传递闭包及相应搜索路径，禁止伪造SONAME或引入另一后端库。

2026-11-08 补齐后继续：精确Proton基线+SUD在独立SDK完成完整能力重编，39/39原Unix模块保留、51个ELF目标/路径/无调试检查通过。FFmpeg58/56及Samba4.13 .so.0采用固定真实ABI；DNS保留真实libresolv链接以匹配固定Box64包装。4项依赖差异经摘要绑定源码/符号复核，不能视作硬件能力验收。新旧三套运行时联合闭包、1762个镜像ELF及5329条真实提供者版本边通过，111项定向测试通过。独立镜像433810184字节、SHA256 bd6a4606ac8b90e6bdf71460b97c16f673469848937e931bebd8ba729b972812，安全恢复及2133个受保护文件完整验证通过，原镜像/正式引用未改。4.4/5.2/5.4/5.5完成；4.2/4.5/5.3及3.2/3.4仍未完成，设备候选尚未执行，ADB传输修复中。证据evidence/full-capability-candidate.json。

2026-11-08 用户要求只打包必需库，进一步明确手机不需要扫描仪、专用相机和 Samba Python；本轮据此限定裁剪目标，不扩展到通用采集、网络、图形/媒体及旧 Wine 的 OSMesa。

- [x] 6.1 审计新增文件用途与反向依赖，明确没有 man/开发文件，记录用户指定插件范围及规划。
- [ ] 6.2 实现摘要绑定的独立候选裁剪计划/应用工具，只删新增排除插件和独占闭包，保护共享依赖及原树。
- [ ] 6.3 完成针对共享依赖、错误摘要、路径/符号链接及意外剩余引用的测试，重新运行联合闭包、全树 ELF 和符号版本门禁并源码复核。
- [ ] 6.4 生成新的独立镜像与真实删除凭据，安全恢复并全量校验，记录实际压缩收益和仍待设备验收范围。

2026-11-08 用户要求立即在现有Termux App环境验证，允许按需补齐库；执行精确Proton/SUD隔离候选，复用现有预构建共享RootFS和预生成prefix，不引入LLVM/整镜像。补充库放入App管理的独立客体glibc组件目录，不能混用Bionic或Native后端库。先SUD直接语义，成功后PE64/PE32及独立GstGL验证；实测失败立即停止。此前全量镜像和插件裁剪任务不作为本轮前置。

2026-11-08 本轮实际设备验证：73,929,128字节精确Proton+SUD候选经设备直接下载及SHA256核对、51个ELF摘要验证后，在现有共享预构建RootFS中运行。独立prefix仅从预生成资产恢复；首次准备缺8项i386原生服务程序，按同提交configure源码纠正诊断布局，四项定向检查通过，未借旧文件或改注册表；第二次在Wine启动前遇到诊断X11的10秒等待过短，按日志延长为180秒并复用从未启动过的prefix，随后正常进入Wine。实际启动出现PlugPlay/Eventlog/winebus服务1053超时，观察后立即取消并清理，未继续PE32/PE64或GstGL。直接syscall报告未生成，不能宣称SUD通过；服务超时根因尚未确定，不能归结为缺库。小组件只含ARM64客体glibc GstGL/GUdev，压缩199472字节、库展开726344字节，已传输核验但未加载。未引入LLVM/新RootFS；精确会话清理通过，34份原有元数据SHA256不变；未发现完整prefix初始化日志。4.5/5.3及App/用户接受项继续未完成。证据：.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/minimal-device-validation-result.json；严格Harness脚本在本checkout缺失，记Unavailable。

2026-11-08 用户明确要求“没有的设备不启动…裁掉”，授权以下独立候选/离线prefix设备裁剪及原探针复验，替代此前禁止修改注册表对本次明确设备策略的限制；禁止顺带删除核心服务或改已有游戏prefix。

- [ ] 7.1 核对原源码的物理/虚拟设备边界，记录精确裁剪清单与保留服务。
- [ ] 7.2 在同基线独立源码补齐全关闭时跳过UDEV的判断，编译PE32/PE64 winebus，校验产物与原构建不变。
- [ ] 7.3 制备受摘要约束的独立手机设备策略及prefix，关闭实体枚举，移除USB/蓝牙专用模块；验证保留键鼠及核心服务、未知输入失败和原资产不变。
- [ ] 7.4 在既有共享RootFS验证裁剪后的同一SUD探针；严格检查服务失败及清理，成功后才继续相关回归，未覆盖项明确记录。

2026-11-08 用户明确要求回到SUD功能验证并说“继续”。7.1–7.4暂停、尚未实施；继续4.5同一探针，不以无功能证据的设备日志提前取消。重新从预生成资产恢复独立prefix，运行时/探针/Box64摘要保持不变；仅验收直接调用返回值、时间输出、前后普通API及会话清理。

2026-11-08 按用户纠正后的边界完成精确Proton+SUD设备直接语义验证：同一候选/Box64/探针摘要，现有预构建RootFS与全新预生成prefix，未裁硬件、未引入新库或LLVM。v4在Wine启动前发现旧显示socket，v5仅换独立诊断display并复用从未启动过的prefix；v5自然退出0，NORMAL_STATUS/DIRECT_STATUS/NORMAL_AFTER_STATUS均0，直接时间实际写入且位于前后普通API时间之间，DIRECT_SYSCALL_SEMANTICS_PASS。设备/UDEV/服务警告仍保留，但未阻止本次探针通过；不据此声称所有服务或游戏无问题。无完整初始化；精确清理通过，34份原有元数据摘要不变。4.5直接语义部分通过，相关PE32/PE64回归尚未执行，因此4.5复选框保持未完成；4.2/5.3及3.2/3.4仍未完成。证据evidence/proton-sud-android-semantic-pass.json。

2026-11-08 用户批准将已验证SUD候选接入APK及资产，并在构建镜像补齐库；批准以下源代码/构建资产修改与本地编译验证。

- [x] 8.1 保存已验证Proton归档与源码补丁来源，更新版本2组件及离线prefix资产，校验每文件来源及没有完整初始化分支。
- [x] 8.2 基于原精简镜像生成仅补GstGL/GUdev的新v2归档、来源及清单；安全恢复后全量校验原内容不变及两库闭包。
- [x] 8.3 同步APK内catalog/image/prefix固定身份，完成定向回归及直接源码复核；无新用户技术配置项。
- [x] 8.4 正常编译原名arm64 APK，验证签名/testOnly=false及所有嵌入资产，提供资产发布前的具体结果；用户手动安装后复验不冒称已完成。

2026-11-08 第8组本地交付完成：精确已验证Proton+SUD归档SHA256 a62eed7a…接入版本2；新离线prefix由既有打包器生成，初始化参考来源与所选运行时分别绑定。原精简镜像12698项全部保持一致，只补GstGL/GUdev、真实SONAME链接和来源/版权文件，新归档323406172字节，比原归档仅增加490852字节；安全恢复、全量逐项比对及1403 ELF声明上下文依赖检查通过。设备隔离GstGL实际加载退出0，GSTGL_LOAD_PASS，精确清理及34份原记录保全通过。13项打包、33项prefix恢复、14项镜像事务、30项JVM通过；直接源码/资产复核完成。原名APK53165656字节（50.70MiB），SHA256 1073c06ea6562e75295368910dcf9b2a73b6be6ccfc2e498a30315b77c980370，arm64、有效签名、testOnly=false，内嵌固定资产逐字节一致。与上一APK字节数相同；相对早期47.14MiB增加来自三套免完整初始化prefix资产。Android新资产经业务恢复工具恢复成功，SUD/PE回归仍运行中；不得把这次隔离验证冒称实际App安装验收。两个新Release URL尚未发布，资产发布及用户手动安装后验收Pending；未提交、推送、外部发布、设备安装或归档。严格Harness脚本缺失记Unavailable。证据evidence/apk-sud-*.json，复核reviews/apk-sud-asset-review.json。

## 第9组：prefix 资源独立交付（已确认）

- [x] 9.1 核对三套资源用途及现有下载/缓存/恢复入口，制备独立确定性归档并逐字节往返比对；记录 SHA-256 与体积。
- [x] 9.2 用户确认本增量规划及准备入口/资产来源定位的修改范围。
- [x] 9.3 增加隐藏的固定 prefix 资源描述，按所选 Wine 接入已有组件依赖、网络/缓存与取消机制。
- [x] 9.4 从受校验安装目录绑定 prefix，APK 移除三套 payload；保持准备/启动身份一致、镜像身份独立、无完整初始化及非空保护。
- [x] 9.5 验证缓存/网络、错包/损坏、取消/路径安全、按需依赖、镜像复用及 prefix/PE 回归，完成直接源码复核。
- [x] 9.6 正常构建原名 APK，核对签名/testOnly=false、资产清单及实际体积，准备具体发布清单；外部发布和手动安装验收分别记录，不能冒称完成。

2026-11-08 用户明确“还是保持独立,编译出apk,清理导入的游戏和prot,继续验证”，确认三套资源独立网络/缓存交付与第9组实施范围，授权清理设备已导入游戏和PRoot测试环境。新APK由用户手动安装；本轮不自动执行Git操作或外部发布。

2026-11-08 第9组本地实施及APK交付准备完成：三套固定prefix保持独立，作为隐藏内部依赖按所选Wine自动获取，复用原下载/缓存、安全安装及任务取消；只有明确准备所选Wine时才下载资源，base-only和身份查询不触发下载。APK仅保留bundle校验描述，当前和旧v11模板payload均移出APK；完整源资产保留构建输入目录。安装器核对固定组件版本/SHA、源bundle及两个payload摘要，拒绝路径越界，并从临时文件原子复制到恢复目录；准备与镜像身份仍独立，无wineboot/后端回退。66项JVM、60项Python及三份实际确定性归档往返通过，直接源码复核完成。清除增量APK打包空洞后，原名APK45645627字节（43.53MiB），SHA256 410a270ada10f3623793aaadd2ff14ff3cd2de300a242eae22c3dd3d263c7422，arm64、签名有效、testOnly=false、APK无任何prefix manifest/delta。原下载路径已更新并HEAD核对字节数。

设备按本轮授权清除已导入游戏/配置/容器、PRoot镜像/组件、测试副本及任务状态；Termux工具、home、Native组件及外部下载缓存保留。清理23个目标，全部残留检查通过，释放19082129408字节，空闲35391688704字节。清理前已归档上一轮精确资产的Android SUD/PE64/PE32成功、无完整初始化及精确清理证据。新的App资源下载/缓存集成仍待用户手动安装后的实际验收，不能用旧APK隔离测试代替。五份新Release资源已就绪但未发布，需独立明确发布授权。3.2/3.4及产品接受保持Pending；无Git操作、发布或归档。

2026-11-08（设备显示11月9日）用户手动安装后要求“通过app界面验证”。已核对APK完整SHA256；所有安装、导入、Wine版本选择、准备、启动和退出均通过实际App界面触发，未直接调用后台验证服务、修改私有游戏元数据或shell启动Wine。精简镜像网络下载及安装通过；Wine、Proton、Hangover的三套独立prefix均由当前APK从已发布资产自动下载校验恢复。六份独立配置（各PE64/PE32）准备分别7.068/6.683、7.419/7.723、8.668/7.563秒，均无完整wineboot、无游戏准备时镜像重建。六个图形测试窗口均实际显示动态version.dll、中文文件名写入及中文GUI PASS；确认关闭后六次App内退出均返回游戏库，最终无Wine/Box64/PRoot进程及启动锁。证据：.harness/runs/reuse-prebuilt-prefix-for-standard-wine/evidence/prefix-network-app-ui-acceptance.json；截图和界面动作记录位于artifacts/proton-sud-apk/network-device-acceptance。此结果仅覆盖所列界面冒烟范围；具体游戏、3D/音频/硬件、故障注入及用户最终接受仍不据此标记完成，产品文档保持Pending。

2026-11-08 用户明确要求“推送代码”，授权提交并推送本轮实现、构建来源、资产索引与 OpenSpec 记录至当前分支；不包含归档或产品文档最终接受。此前各阶段“未授权Git操作”为当时状态，由本次授权更新。

## 第10组：Native 免完整初始化（已确认）

2026-11-08 用户“批准执行”，批准前述 Native 预生成恢复设计及保护路径；不自动提交、推送、归档或安装设备 APK。

- [x] 10.1 核对 Native 准备、激活、字体、盘符、Wine 版本与 PRoot 资产差异，记录增量规划及实施授权。
- [x] 10.2 构建五个固定 Native 组件的独立模板，复用已验证骨架、重新绑定 DLL/用户/字体，记录来源与归档摘要。
- [x] 10.3 接入自动网络/缓存获取、可信校验、Native 原子恢复和准备收据，移除完整 wineboot/隐式恢复兜底。
- [x] 10.4 完成损坏/越界/错版本/非空/取消等安全回归、Native 身份/依赖定向测试及源码复核，保护 PRoot 既有路径。
- [x] 10.5 正常构建原名 arm64 APK，核对签名、testOnly=false、无 prefix payload，准备明确新资源发布清单。
- [x] 10.6 用户手动安装后通过 App 界面验证 Native Wine10 PE32/PE64、中文、首次/第二配置和退出；其余Native版本与最终接受/产品文档保持待确认。


2026-11-08 第10组本地实施与交付准备完成：五套 Native 资产复用既有验证骨架，全部来源绑定所选 Native 组件，Native Unix 加载器/库与 PRoot 保持独立；沿用原附加程序/DirectX/字体与默认注册表，不在准备阶段执行 Wine。容器按 Wine 版本隔离可写 prefix，旧路径不迁移、不删除；缺失、错包、损坏、非空、取消等明确失败，不走 wineboot 或后端兜底。22项Native恢复、33项PRoot回归、44项JVM、Native启动脚本集成及五套真实归档各两次恢复/逐文件校验通过，直接源码复核完成。正常原名arm64 APK45647375字节（43.53MiB），SHA256 1c4555621b1ad140fa51716771be84b03d41ad5baafec7f635ce52bae12657b9，签名有效、testOnly=false、无prefix payload。五份外部资产各3.7–4.0MiB，等待本增量独立发布授权；当前尚不能完成在线首次准备。未安装/清理设备，未运行Native Wine，App实际功能验收与最终产品接受保持Pending；不将本地恢复计时当作设备性能。严格Harness脚本缺失记Unavailable。证据evidence/native-prefix-delivery.json，复核reviews/native-prefix-review.json。无Git操作或归档。

2026-11-08 用户明确“发布,将apk给我下载手动安装”，授权发布五份 Native prefix 资产至 jiaxinchen-max/termux-app-packages。五个独立版本 Release 已发布，全部经无认证公开下载核对字节数和 SHA-256，与 APK 固定索引一致；未覆盖既有资产。原名 APK 下载服务实际 GET 完整摘要核对通过，仍为45647375字节、SHA256 1c4555621b1ad140fa51716771be84b03d41ad5baafec7f635ce52bae12657b9。用户手动安装及第10.6项 App 界面 Native 验收保持Pending；无Git操作、设备安装或归档。发布凭据evidence/native-prefix-publication.json。

2026-11-08 用户安装后继续界面验收，完整APK SHA256一致。通过App切换Native库、导入PE64、选择安装Wine10并保存准备；编辑页显示Backend: Native，导入无后端选择。App自动下载/安装所需组件及新native_prefix_wine_10.0资源3891955字节，摘要校验通过。但准备脚本在恢复前因glibc_loader_missing失败；只读核查确认设备缺usr/glibc/lib/ld-linux-aarch64.so.1、libc.so.6及usr/bin/grun，Python已有。Native基础环境未安装，准备入口未预先检查/引导基础安装，是流程缺口；不能据此判定prefix内容不兼容。依用户严格规则立即停止，未重试、安装基础环境、切换后端或执行Wine。目标prefix未创建，无Wine/Box64/PRoot进程。10.6继续未完成，后续PE32/PE64、中文、第二配置及退出验收均未执行。证据evidence/native-prefix-app-ui-acceptance.json和artifacts/native-prefix/app-ui-acceptance。

## 第11组：Native基础环境准备缺口修复（用户确认）

2026-11-08 用户“修复这个问题”，确认修复已实测的Native基础环境遗漏及对应准备/安装保护路径；不包含Git操作、设备自动安装或外部资产修改。

- [x] 11.1 将主机termux-glibc作为准备第一步，复用既有安装/状态/验证链路，安装前后都检查真实能力。
- [x] 11.2 增加基础就绪/缺失/失败/错误成功/重复准备回归，核对失败不继续组件或prefix、没有自动重试。
- [x] 11.3 完成源码复核和正常原名APK构建，核验签名/testOnly=false、资产不变及下载摘要；用户手动安装后的App验收保持Pending。

2026-11-08 第11组本地修复完成：Native准备入口先检查主机termux-glibc，缺失时通过既有官方包任务安装并重新验证，之后才准备目录组件及恢复prefix；主机依赖不读取归档安装凭据、不触发归档激活。已有有效基础环境直接复用；安装失败/取消或错误成功立即传播，不执行后续步骤。Python同时纳入安装脚本最终校验。29项JVM、4项实际安装脚本隔离夹具回归及shell语法检查通过，直接源码复核完成。正常原名arm64 APK45647393字节（43.53MiB），SHA256 3c1f00d6722f8513cd8855f6f0dfbf8320dfcc0a30510bd2937e66842a35804f，签名有效、testOnly=false、无prefix payload；已发布Native/PRoot资产及配方描述保持不变。更新原下载文件供用户手动安装；未在设备替换脚本、安装包或运行后台验证。真实App缺失基础依赖安装、prefix恢复及PE32/PE64验收继续Pending，10.6未完成；严格Harness脚本缺失Unavailable。证据evidence/native-base-prerequisite-fix.json，复核reviews/native-base-prerequisite-review.json。无Git、发布新资产或归档。

2026-11-08 用户安装并授权“清理历史游戏后验证”。核对APK完整SHA256为3c1f00d6…一致；清理7条历史游戏及对应配置/容器/启动数据，保留主机工具、共享RootFS、已安装组件与下载缓存，空Native游戏库截图确认。随后仅经App界面导入PE64、选择Wine10并保存准备。App首先自动安装官方termux-glibc，取得成功事件并通过真实主机探针，原glibc_loader_missing问题修复验证通过；随后Native prefix成功恢复2393个独立文件/856330933字节，无wineboot。后续图形归档解压因缺少7z报p7zip_missing，是新的基础工具遗漏；按严格规则立即停止，无重试/回退或Wine启动，准备锁清除，无Wine/Box64/PRoot进程。10.6的PE32/PE64、中文、第二配置和退出验收仍未执行，不标完成；需后续将7z纳入必需依赖。证据evidence/native-base-app-ui-acceptance.json和evidence/native-base-game-cleanup.json。未改设备脚本、后台启动验证、修改代码或Git操作。

## 第12组：Native 7z依赖遗漏（用户确认修复）

- [x] 12.1 在现有基础安装脚本、真实host探针及就绪展示加入7zip/7z，保证已装glibc但缺工具时重新准备而非假就绪。
- [x] 12.2 回归缺失/不可执行工具、安装错误成功及旧工具依赖检查，源码复核。
- [x] 12.3 正常构建原名arm64 APK，核验签名/testOnly=false及资产未变，提供下载；用户手动安装后的App验收待执行。

2026-11-08 第12组本地修复交付：官方7zip加入基础安装，脚本最终核验、真实host探针及界面就绪展示均检查7z可执行；仅共享工具预装不误判glibc已装。40项JVM、6项实际安装脚本夹具、shell语法及源码复核通过。正常原名arm64 APK45647402字节（43.53MiB），SHA256 6ddb363d7a9d70efb78c1fd84c760b93af44fa83a5440084b3f7e7200ec3fef8，签名有效、testOnly=false、无prefix payload；除安装脚本外核对的资产字节不变，已发布prefix/镜像身份不变，无wineboot或后端兜底。原下载路径已更新并实际完整GET核验。设备未安装/运行重试，仍需用户手动安装后App界面验收，10.6不标完成，产品接受Pending。严格Harness脚本缺失记Unavailable；无Git操作或归档。证据evidence/native-sevenzip-fix.json，复核reviews/native-sevenzip-review.json。

2026-11-08 用户安装第12组APK后，经完整SHA256核验。全程由真实App界面操作：Native Wine10保存准备自动补装官方7zip 26.06成功，原prefix收据复用；图形归档解压通过，首次中文locale生成后准备成功85.075秒，无wineboot/重建/后端兜底。PE64及新导入PE32均实际显示动态version.dll、中文文件写入及中文GUI PASS；第二配置准备0.154秒直接复用。两次确认OK后均通过App退出返回游戏库，任务按设计CANCELLED/COMPLETE，不冒称自然退出0；最终无Wine/Box64/PRoot/音频进程及启动锁。两次日志均有libXrandr.so.2缺失警告，未阻止本轮GUI/文件冒烟，动态分辨率与相关扩展未验证。10.6所列Wine10范围完成，其余Native Wine版本、具体游戏、3D/音频/硬件及用户最终接受不标完成；产品文档保持Pending。证据evidence/native-sevenzip-app-ui-acceptance.json，截图及动作日志artifacts/native-sevenzip-fix/app-ui-acceptance。无Git操作、设备脚本修改或后台测试。

2026-11-08 用户明确“推送代码”，授权在当前分支提交并推送第10—12组 Native 预生成prefix及基础依赖修复、构建输入/固定描述、测试和本轮验证记录；不包含无关X11子模块修改、本地缓存、设备操作、产品归档或最终接受。

## 第13组：中文locale移至glibc基础安装（已确认）

2026-11-09 用户“批准执行”，确认前述阶段归属及设计，授权安装/准备/启动保护路径调整；不自动提交推送或部署。

- [x] 13.1 将locale生成、输出验证及成功标记发布移入基础安装，缺失/失败立即终止；旧有效数据直接复用。
- [x] 13.2 同步host、界面及prefix就绪判断，游戏准备和启动只检查并使用locale。
- [x] 13.3 完成安装失败/错误成功/损坏/复用及游戏无生成回归、JVM验证和源码复核。
- [x] 13.4 正常构建原名arm64 APK，验证签名/testOnly=false及外部资产身份不变，供手动安装；实际App验收独立Pending。

2026-11-09 第13组本地交付：中文UTF-8/GBK locale已归入Native glibc基础安装，命令自然结束且文件校验通过才发布成功；host/界面/prefix均检查标记和两份非空文件，游戏准备及启动只读检查，缺失立即失败，无wineboot/重建/后端回退。11项实际安装器夹具（含5种错误产物子场景）、23项实际bootstrap夹具、完整启动脚本集成及29项定向JVM测试通过，源码复核无未解决发现。正常原名arm64 APK45647842字节（43.53MiB），SHA256 f63f06341f3f6e9f2d96dafc8267965f078abda6644d5655b8f65cdf26048619；签名有效、testOnly=false、包内4个脚本与源码一致、无prefix压缩包/Python缓存，外部资产描述与HEAD一致。下载目录实际完整GET校验通过。初次Maven镜像502导致构建依赖失败，改用本地init移除不可用镜像后成功，未改项目仓库配置。X11构建自动应用7个子模块补丁，gitlink未变，不属本增量业务修改。证据evidence/native-glibc-locale.json，复核reviews/native-glibc-locale-review.json；严格Harness脚本缺失记Unavailable，实际App手动安装验收及产品接受Pending，无提交/推送/设备部署或归档。

2026-11-09 用户明确“推送代码”，授权在当前分支提交并推送第13组locale安装阶段调整、回归测试及规划记录；不包含X11构建自动生成的7个子模块工作树修改，不包含APK/缓存，不代表App验收、产品最终接受或归档。
