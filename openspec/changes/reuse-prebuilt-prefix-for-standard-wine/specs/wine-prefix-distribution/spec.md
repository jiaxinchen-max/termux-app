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
