# 技术设计（用户已确认实施）

## 已核对的实现

- `desktop_install.sh` 的保留桌面使用 `ubuntu:resolute`。
- `RootfsSetupRecipe` 固定 `debian-13-games-rootfs`、版本 12 和 `rootfs_debian13_hangover_pdb2_minimal`。`GameContainer` 和基础安装入口也保存该运行身份。
- `runtime-rootfs-v12/wine-runtime.properties` 记录 `debian:trixie-20260824`；`image.json` 固定 Debian 预装归档及 SHA。`setup-container.sh` 只是禁止设备客体安装的说明，没有可直接替换 FROM 的构建配方。
- `prebuilt_rootfs.py` 固定镜像身份和 Wine 来源摘要；三个 `prefix-*/bundle.json` 绑定基础配方、运行库文件与独立 Wine 来源。
- 当前安装、启动、备份/恢复及 `GameStoragePaths` 共享 `games-shared-rootfs/rootfs`，模板缓存也未按发行版隔离。只改默认组件会把旧容器指向不兼容的全局根。

## 构建与依赖

采用官方 Ubuntu 26.04 aarch64 OCI 根，实施时核实 `26.04` 与 `resolute` 一致并锁定实际 digest、APT 来源及完整包版本。历史桌面实验 digest 仅作参考，不当作本轮新核验。补齐离线构建脚本和来源锁，再预装所需 Hangover、X11、音频、Mesa、字体、UTF-8/GBK locale，并按必要文件闭包裁剪。

Wine 选择值先保持 Hangover 11.16、Wine 10 和 Proton 11。逐来源核对 ELF interpreter、SONAME、glibc 符号版本和翻译器依赖，记录可复用与须重建的组件；不混装 Debian 基础系统库或把 Debian ELF 更名为 Ubuntu 版本。不能满足 ABI 的来源必须适配或重建，失败阻止默认切换。

两类运行链路分别验证：Hangover 是定制的 ARM64 Wine，直接运行 ARM64 ELF 的 wine/wineserver，通过其内部 wowbox64/libwow64fex 和 libarm64ecfex 模块执行 x86/x64 PE；不是把普通 x86_64 Wine 交给外部 Box64 的同一方案。本次普通 Wine 10 和 Proton 11 的 wine/wineserver 是 x86_64 ELF，必须由独立 Box64 翻译执行。三类来源各用对应前缀、运行库与模板，Hangover 的 HODLL/HODLL64 仅传给 Hangover，不能用 Hangover 的通过替代 Wine/Box64 的资格，也不能互换 DLL、ELF 或媒体 ABI。2026-11-17 核对实际包的 ELF e_machine：Hangover wine/wineserver 为 183（AArch64），Wine 10/Proton 的入口与 wineserver 为 62（x86-64）；独立 CLI 的原始退出 137 保留为诊断结果；App 主动退出会强杀并记录 CANCELLED，因此不再将单一 CLI 137 作为迁移阻塞，不修改生产退出码或关闭 JIT。验收以实际桌面、功能和清理结果为准。

实际 ABI 核对发现原 Hangover 11.16 的 `winedmo.so` 依赖 FFmpeg 7，而 Ubuntu 26.04 提供 FFmpeg 8。固定原组件 SHA 与同一 Wine 源码提交 `3ea3a14941b6a122b892c84141ce9662c008081e`，在官方 Ubuntu 26.04 交叉构建环境中只重编译该 Unix 模块，验证两组 Unix call table 尺寸和其余 SONAME 一致；包内仅允许该模块、control 和 md5sums 变化，其他 PE、ELF 和模拟器包逐字节保留。适配包版本为 `11.16~ubuntu26.04+idap.pdb2.1`。设备通过已核验原包加 40 KB 补丁本地重建时，另核对全部 payload 与主机构建结果；归档压缩字节不同必须明确记录，不能声称下载了候选源包。

按用户澄清，扫描仪、相机、智能卡、CAPI/ISDN 等 Wine 可选模块不属于本次游戏运行验收范围，不为它们补装系统库，也不删除模块来制造全模块审计通过。全模块静态扫描保留原始失败结果；核心启动、图形、音频、中文、文件访问与游戏视频解码分别提供来源和运行证据，视频依赖问题不得归入可选外设后忽略。

Wine 10/Proton 的 winedmo 另依赖 FFmpeg 4 的 58/58/56 ABI，固定 Box64 0.4.4 的 wrapper 也仅支持该组 ABI。候选采用官方 FFmpeg 4.4.8 源码，在相同 Ubuntu 26.04 构建环境生成 aarch64 兼容库，限版本化运行库与法律文件，独立放置于 /usr/local/lib，不替换 Ubuntu FFmpeg 8，不引入 Debian 基础库，不启用外部设备模块或自动外部依赖。来源、构建参数、库与归档摘要单独记录；编译通过不代替 Box64 解码和 Wine 集成验收。

三类前缀模板均在目标环境重新准备或逐文件验证，生成真实 `runtimeFiles`、来源摘要和新配方摘要；不能仅改 `baseRecipeSha256` 掩盖未验证的兼容性。沿用小差量模板及按需外置运行组件，完整 rootfs 不放进 APK。

## 身份、路径与旧数据

使用已批准的新配方 v13，运行包身份 `ubuntu-26.04-games-rootfs`，镜像组件 `rootfs_ubuntu2604_hangover_pdb2_minimal`，资产目录 `runtime-rootfs-v13`。原有公开 Debian 制品不覆盖，用户 APK 文件名保持原样。

用户明确不考虑历史数据兼容性。因此保持既有 `games-shared-rootfs/rootfs`、模板和任务路径，完整切换到新的 v13 配方，不增加多发行版根选择器、旧记录映射、旧模板迁移或历史备份恢复。模型和任务仅接受新的 Ubuntu 身份与版本；旧身份明确拒绝并要求重建，不为旧格式添加兼容分支。

显式重建沿用暂存、previous、回执提交和失败恢复。previous 仅用于本次事务回滚，不提供永久的 Debian 并存管理；不自动迁移或删除其他游戏 C 盘。重新生成的三类模板绑定新配方，既有前缀不作为兼容性验收对象。

## 安装事务及模块边界

Activity 沿用现有基础安装/重建入口，清楚报告旧身份与重建要求。Service 持有任务状态和操作锁；对受影响会话先请求停止并确认退出，停止失败不得替换根。下载由组件框架验证 URL、大小及 SHA；shell 只准备宿主依赖、还原和校验镜像，不能在设备执行 guest apt 或首次 wineboot 兜底。

沿用专用还原器的路径逃逸、链接、成员数量与展开空间限制，在既有私有暂存目录还原。校验镜像、运行入口、locale/字体和模板来源后同文件系统原子发布，发布后再设置就绪回执。取消、空间不足、错误摘要、进程停止失败及中断均记录失败并清理本任务暂存；previous 可恢复，安装失败不得发布新的就绪状态。

启动使用已保存的身份、配置及前缀，只消费准备完成的环境，不自动下载、修复或重建。X11、渲染桥、输入、手柄和 PulseAudio 继续走现有边界；本次不改合成器和桥接器架构。备份保存当前配方、镜像及前缀来源，恢复先验证新身份；不支持历史Debian备份或跨版本转换。

## 验证和交付

先验证官方根来源与 Ubuntu ABI，再制作候选制品和前缀；本地与真实 Android PRoot 都通过后，才形成可审阅的正式 URL/字节数/SHA 及索引差异。外部 Release 发布另需明确授权，发布完成并核对后才能启用默认下载，禁止填入尚不存在的地址。

验证覆盖新装、重建、三类 Wine 的 PE32/PE64、中文、文件访问、音频、可用图形路径、退出及残留进程；故障覆盖错误镜像、缺依赖、磁盘不足、取消、停止失败、恶意归档和备份身份错配。模拟器无法验证的硬件能力记 Pending，不当作通过。比较压缩、展开、APK 大小并报告变化，不预先承诺体积下降。

仓库未发现保护路径清单。预计触及运行配方、组件索引、还原工具和会话脚本，影响与回滚按上述事务记录；实施前若新发现保护规则，再按适用范围处理。Harness bootstrap、strict 脚本、OpenSpec CLI 和产品基线当前缺失；采用中文手工规划保留证据，不宣称严格门禁或产品一致性通过。
