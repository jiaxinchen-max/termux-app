## ADDED Requirements

### Requirement: 普通 Wine 按兼容架构消费预生成前缀

系统 SHALL 为本次覆盖的 PRoot 普通 Wine 与 Proton 选择已生成的兼容架构 prefix，复用同架构初始化骨架，不因 Wine 版本变化要求用户设备首次生成模板。

#### Scenario: 首个普通 Wine 配置
- **WHEN** 用户准备受支持普通 Wine 配置，组件及兼容模板齐备
- **THEN** 系统恢复独立可写 prefix，校验后发布就绪，不执行完整 wineboot 初始化。

#### Scenario: 同架构版本复用
- **WHEN** 另一受支持 Wine 版本使用同一兼容架构模板
- **THEN** 系统使用所选版本组件的系统 DLL 和运行身份，不误用 Hangover ARM64EC 文件。

#### Scenario: 第二配置
- **WHEN** 同一组合创建第二个游戏配置
- **THEN** 系统复用模板输入，但前缀文件保持独立可写。

### Requirement: 校验与失败保护保持有效

系统 SHALL 校验模板身份、资产摘要、所选组件及每文件来源；SHALL 拒绝路径越界、错误架构、损坏输入和非空目的目录覆盖。

#### Scenario: 模板缺失或不匹配
- **WHEN** 受支持预生成组合没有有效模板
- **THEN** 准备明确失败，不静默全量初始化，不切换 Wine。

#### Scenario: 更新准备脚本与模板后复用同一镜像
- **WHEN** APK的准备配方更新，但已安装基础镜像通过当前固定预构建镜像校验
- **THEN** 系统直接复用镜像，以独立配置准备身份恢复prefix，不要求重建或重新解压基础镜像，不改写原镜像凭据；损坏或镜像摘要不匹配明确失败，不能以重建绕过。

#### Scenario: 恢复后启动
- **WHEN** 使用成功恢复的前缀启动 PE32 或 PE64 程序
- **THEN** 使用所选 Wine 和翻译器，正确显示中文，不重复进入完整前缀安装阶段。

#### Scenario: Proton用户目录
- **WHEN** Proton从通用骨架恢复新前缀
- **THEN** 打包资产已提供steamuser的Desktop等目录和对应注册表路径，不因沿用root账号造成Shell桌面失败，也不在设备完整初始化修补。

#### Scenario: 离线参考遗留不存在的菜单集成程序
- **WHEN** 已知RunServices菜单启动项指向所选组件不提供的winemenubuilder.exe
- **THEN** 打包资产移除该精确启动项并记录来源，保留其他启动项；设备只恢复一致资产，不补装或完整初始化修补。

### Requirement: 会话终态前完成精确清理

系统 SHALL 在自然结束、取消、超时及异常退出时清理本次PRoot/Wine会话；SHALL 使用内部唯一会话标记核对进程归属，不能按名称全局结束其他会话。

#### Scenario: Wine守护进程脱离父进程
- **WHEN** 本次PRoot父进程已退出，但携带精确会话标记的Wine进程仍存活
- **THEN** 系统定向TERM并有界等待，必要时KILL并复查，完成后才发出终态；清理失败显式报错。

### Requirement: PRoot 直接 Windows syscall 保持 Windows 语义

本次候选 SHALL 以设备当前 Proton 精确源码提交为基线，在隔离目录构建 rootfs_proot 目标；普通 API 与直接 Windows syscall SHALL 返回一致的成功语义。所需 SUD 无法启用时 SHALL 明确终止，不回退旧 seccomp、Linux syscall 分发或其他运行后端。

#### Scenario: 独立语义验证
- **WHEN** 在既有预构建 prefix 的独立副本中运行普通 API→直接 NtQuerySystemTime syscall→普通 API 探针
- **THEN** 三次调用均成功，直接调用实际写入有效时间；记录源码、补丁、构建配置和产物摘要，不能以普通 API 成功替代直接调用结果。

#### Scenario: 编译目标不匹配
- **WHEN** 候选含 Termux glibc 固定路径、错误 Unix 架构、加载器或动态库依赖不满足 PRoot 加载链
- **THEN** 拒绝进入功能验证，不补加载器链接或借用另一后端库；启动前失败不计作 SUD 语义结果。

#### Scenario: 必需 SUD 启用失败
- **WHEN** libc 允许区域无法确认或启用 SUD 失败
- **THEN** 明确失败并退出；不继续执行游戏，不静默关闭 SUD，不自动切换 Wine 或翻译器。

### Requirement: 外置运行时的原生依赖纳入预构建镜像

构建工具 SHALL 将固定外置 Wine/Proton 经对应 Box64 包装加载的原生 ARM64 库及传递依赖纳入镜像闭包，记录来源、摘要、提供者架构和解析路径。

#### Scenario: 外置媒体库依赖
- **WHEN** 外置 Wine Unix 模块依赖由 Box64 包装加载的 GstGL
- **THEN** 镜像含正确 ARM64 GstGL、GUdev 及完整传递闭包和 SONAME 链，不能以树内 ELF 审计通过代替外置依赖审计。

#### Scenario: 缺失或错误后端提供者
- **WHEN** 原生提供者缺失、架构错误、只存在于 Native Termux glibc 路径或包装映射无法确认
- **THEN** 构建验证明确失败，停止后续运行验收，不临时补装、关闭媒体功能、借库或回退。

#### Scenario: 编译环境自动关闭原有能力
- **WHEN** 新候选缺少旧Unix模块、移除直接依赖或原有库加载名称，或者configure报告因开发依赖缺失关闭功能
- **THEN** 构建验收阻塞并记录逐项能力复核，不能靠少编译模块获得依赖闭包通过，也不能交付为等价替换。

### Requirement: 独立镜像只保留已选设备插件及必需库

构建工具 SHALL 按用户指定排除扫描仪后端、专用相机驱动及 Samba Python 绑定，SHALL 保留 Wine API 模块、通用视频采集、网络、图形、媒体及真实共享依赖。

#### Scenario: 可选插件的独占依赖
- **WHEN** 新增库仅被排除插件引用，且不被任何保留对象或明确动态加载根使用
- **THEN** 可在独立候选删除库及其真实软链接，记录来源、摘要、删除原因和依赖证明，并重新执行完整静态门禁。

#### Scenario: 排除目标仍被使用
- **WHEN** 剩余运行对象仍声明需要排除目标，或无法验证来源/摘要/路径
- **THEN** 明确阻塞，不删除调用者，不忽略依赖，不替换正式镜像或在线补装。

### Requirement: 手机候选不启动未暴露的实体设备后端

候选 SHALL 在构建/离线prefix阶段关闭Linux实体HID与SDL枚举，裁掉USB和蓝牙驱动启动及其独占模块；SHALL 保留Wine虚拟键鼠、PlugPlay、Eventlog和RPC核心服务。

#### Scenario: 所有主机HID输入均关闭
- **WHEN** 手机策略关闭hidraw、Linux input及SDL设备后端
- **THEN** 不创建UDEV/SDL/IOHID实体总线，虚拟mouse/keyboard创建代码仍执行；不得整删winebus。

#### Scenario: 核心服务启动仍失败
- **WHEN** 裁剪后PlugPlay/Eventlog/RPC仍出现实际启动失败
- **THEN** 立即停止验收并精确清理，不能把系统服务归为不存在的硬件而关闭或删除。

### Requirement: 用户无需理解运行时补库

系统 SHALL 将固定Proton/SUD组件和对应prefix资产作为统一受校验组合，所缺ARM64客体GstGL/GUdev SHALL 在构建端加入预构建镜像。用户流程 SHALL 自动获取固定资产，不要求手动补库、输入动态库路径或选择技术修复方案。

#### Scenario: 全新安装选择Proton
- **WHEN** 用户选择本次固定Proton并准备运行
- **THEN** 系统消费已包含GstGL/GUdev的预构建镜像、版本2Proton与对应预生成prefix，校验后启动，不执行完整初始化或设备apt补装。

#### Scenario: 不一致或损坏资产
- **WHEN** 镜像/组件/prefix固定来源摘要不匹配或损坏
- **THEN** 明确失败，不静默借用旧组件、不自动回退后端或完整初始化。

### Requirement: prefix 从固定资产仓库按需交付

系统 SHALL 为 Hangover 11.16、Wine 10.0、Proton 11.0 分别维护固定版本的内部 prefix 资源；APK SHALL 只携带定位与校验所需的小型描述，不携带三套 prefix 数据。

#### Scenario: 已有有效缓存
- **WHEN** 用户准备某一 Wine 且其固定版本 prefix 已安装或归档缓存校验通过
- **THEN** 自动离线复用所选资源，恢复预生成 prefix，不下载其他 Wine 的 prefix，不显示额外组件选择。

#### Scenario: 首次获取资源
- **WHEN** 没有所选资源的有效本地副本且网络可用
- **THEN** 通过现有下载/缓存机制获取精确 URL、大小与 SHA-256 对应资源，安全安装后恢复；保留任务进度和取消能力。

#### Scenario: 无可用资源或身份不匹配
- **WHEN** 无网络且无有效缓存，或资源损坏、来源/版本/Wine 摘要不一致，或任务取消
- **THEN** 明确失败且不发布半成品，不完整初始化、不借另一版本、不切换后端。

#### Scenario: 交付位置变化
- **WHEN** prefix 从 APK 内置改为固定网络/缓存依赖
- **THEN** 镜像身份继续独立，不能要求重建已有匹配镜像；准备与启动使用一致的来源身份，既有非空 prefix 不被覆盖。

## ADDED Requirements

### Requirement: Native 使用受版本约束的预生成前缀
Native 目录中的 Wine 8.18/9.0 staging、9.3 vanilla、10.0 与 Proton 11.0 新建前缀必须从独立固定资产恢复，不能执行完整 wineboot；预生成注册表骨架可来自已验证的同架构 PRoot 资产，系统 DLL 必须对应所选 Native 组件。

#### Scenario: 全新 Native 配置
- **WHEN** 用户通过 App 导入并准备 Native 配置
- **THEN** 自动从网络或缓存获取所选资产，校验并原子恢复独立可写前缀，显示准备结果，不要求用户操作技术组件。

#### Scenario: 来源损坏或不受支持
- **WHEN** 资产、组件、架构、用户名绑定、目标状态或准备收据校验失败，或自定义版本没有固定模板
- **THEN** 立即失败并保留原数据，不执行 wineboot、重建、后端回退或不受约束的迁移。

#### Scenario: Native 运行库隔离
- **WHEN** 从已初始化 PRoot 骨架生成 Native 资产
- **THEN** 重新绑定 Native Wine 的 DLL、用户及主机路径，使用 Native 加载器/glibc/Box64，不能复制 PRoot 的 Unix 运行库。

#### Scenario: Native 切换固定 Wine 版本
- **WHEN** 同一容器切换 Native Wine 版本或切回原版本
- **THEN** 自动选择容器内对应版本的独立 Prefix；新版本从受验证资产准备，切回时复用原版本数据，不运行 wineboot 迁移、不覆盖其他版本。

### Requirement: Native 准备必须先满足主机基础环境

App 保存准备 Native 配置时，MUST 首先检查 termux-glibc 主机能力；缺失时 MUST 使用既有官方包安装流程，并在成功后重新核验，再准备所选组件和恢复prefix。MUST NOT 把主机依赖当作目录归档下载或激活。

#### Scenario: 全新Native环境
- **WHEN** 主机加载器、glibc、grun、Python或必要图形库不齐全
- **THEN** 先执行既有基础安装流程，成功且探针通过后才能处理组件与prefix

#### Scenario: 基础依赖失败
- **WHEN** 安装失败、取消或任务成功后探针仍未通过
- **THEN** 准备立即失败，不执行后续下载/恢复，不自动重试或回退

#### Scenario: 基础环境已有
- **WHEN** 主机探针已确认基础能力齐全
- **THEN** 直接复用，不重复安装基础包

### Requirement: Native 基础准备包含归档解压工具

Native termux-glibc 基础依赖 MUST 包含官方7zip的7z可执行文件，脚本安装核验与主机就绪判断 MUST 一致。

#### Scenario: 旧环境缺解压工具
- **WHEN** glibc已安装但7z缺失或不可执行
- **THEN** 基础环境判为未就绪，经既有包安装链路补齐并核验后才继续；不重建已恢复prefix

#### Scenario: 工具安装不完整
- **WHEN** 包管理任务返回成功但7z仍不可执行
- **THEN** 安装验证失败并立即终止准备，不启动Wine或回退

#### Scenario: 就绪展示与必需工具一致
- **WHEN** glibc基础标记齐全但Python或7z不可执行
- **THEN** 展示基础环境不完整；仅工具存在但glibc标记全无时仍显示未安装

### Requirement: 中文locale在Native基础安装阶段完成

Native基础安装MUST生成并验证zh_CN UTF-8/GBK locale后才报告成功。host、环境展示及prefix就绪MUST检查完成标记与两种locale的非空LC_CTYPE。prefix准备和游戏启动MUST只检查并消费，不执行locale-gen/localedef。

#### Scenario: 首次安装或locale数据损坏
- **WHEN** 基础包已安装但任一必需locale数据缺失
- **THEN** 基础安装流程生成locale并核验，通过后才能准备prefix；命令或核验失败立即终止，不写成功标记

#### Scenario: 已有有效locale
- **WHEN** UTF-8/GBK数据及完成状态有效
- **THEN** 基础环境及游戏流程直接复用，不重新生成

#### Scenario: 游戏脚本发现locale不完整
- **WHEN** prefix准备或启动检查locale失败
- **THEN** 明确失败，不补装、不生成、不执行Wine或隐式回退


#### Scenario: 官方locale配置使用空白分隔
- **WHEN** 基础安装读取的中文locale配置包含行首、行尾空白或字段间制表符
- **THEN** 按精确locale名与字符集字段识别和启用，执行官方生成并核验；其他配置保留，缺项或错误字段仍立即失败

### Requirement: Native prefix数据身份独立于准备脚本

Native的prepared-identity MUST为受固定目录校验的bundle.json摘要，MUST NOT包含准备脚本摘要。准备脚本版本MUST独立记录并参与就绪判断。MUST NOT识别、迁移或改写历史数据身份；不匹配必须立即失败且保留目标。

#### Scenario: 仅准备脚本更新
- **WHEN** bundle、Wine及prefix数据身份相同，但准备脚本摘要改变
- **THEN** 原prefix数据身份保持不变，执行现有准备检查并在成功后原子更新独立脚本标记，不运行Wine或恢复/重建prefix

#### Scenario: 数据配方或运行时元数据改变
- **WHEN** bundle内容变化或目标仍带历史/错误数据身份
- **THEN** 拒绝复用并明确失败，不迁移标记、不自动重建

#### Scenario: 准备检查失败
- **WHEN** locale、字体、图形组件或目标身份检查失败
- **THEN** 立即终止，不发布本次脚本完成标记，不启动游戏

### Requirement: Native基础安装包含XRandR运行库
Native glibc基础安装必须通过官方libxrandr-glibc补齐XRandR，安装后、host能力探针和界面Ready判断均必须要求glibc/lib/libXrandr.so.2为非空普通文件。

#### Scenario: 安装成功且XRandR可用
- **WHEN** 基础安装所有包成功且必需文件、工具和中文locale均有效
- **THEN** 才报告成功和Ready；XRandR随基础环境自动提供，不要求用户手动补库。

#### Scenario: 包失败或XRandR产物无效
- **WHEN** libxrandr-glibc安装命令失败，或libXrandr.so.2缺失、为空或是目录
- **THEN** 立即终止且不发布成功；host探针不可用，部分环境界面为INCOMPLETE；不得通过wineboot、重建prefix或后端回退继续。

### Requirement: 基础安装预装常用压缩工具
Native glibc安装必须一并安装官方7zip、zip、unzip、tar、gzip、bzip2、xz-utils及zstd，并校验7z、zip、unzip、tar、gzip、bzip2、xz、zstd命令为非空普通且可执行文件。host和界面就绪必须包含这些工具。

#### Scenario: 工具预装成功
- **WHEN** 所有包命令成功且工具及原有基础依赖均有效
- **THEN** 才报告成功和Ready，用户无需另行补装。

#### Scenario: 工具缺失或不可执行
- **WHEN** 包命令失败，或成功后任一必需工具缺失或不可执行
- **THEN** 安装立即失败，host不可用，已有部分环境显示INCOMPLETE，不通过其他工具或后端兜底。
