# 普通 Wine 复用预生成前缀

## 原因

普通 Wine/Proton 当前仍在设备首次准备时运行完整 wineboot，再缓存模板，等待过长。用户明确要求与 Hangover 一样优化，并确认同架构 prefix 兼容性已经验证。本变更沿用该兼容结论，不要求每个 Wine 版本重新初始化模板。

## 变更范围

- 将预生成 prefix 恢复机制扩展到 PRoot 目录中的普通 Wine 和 Proton，按实际 Windows prefix 架构匹配兼容模板。
- 兼容模板的注册表与目录可复用；Wine DLL 必须解析自所选 Wine 的固定版本组件，不能把 Hangover 的 ARM64EC 系统 DLL 当成普通 x86/x64 Wine DLL。
- 首次准备与后续新配置均只恢复、校验并发布；支持组合不再设备生成模板或执行完整 wineboot。
- 保留每游戏独立可写 C 盘、组件外置挂载、精确运行身份和非空目录保护。
- 本轮不改 Native 后端，不裁剪镜像，不自动发布、安装或提交推送。

## 文档与限制

无 HTTP API 契约变化，api_docs=N/A。改变用户准备流程，product_docs=Required，验收后维护 docs/product/runtime-components.md 与 docs/product/wine-library.md；一致性 Pending。

本阶段为待确认增量规划；现有 prebuild-wine-prefix-archives 明确排除了普通 Wine，须在确认后同步该边界。权威 Harness bootstrap 文件和 openspec CLI 缺失，严格工作流验证记 Unavailable，不伪造已执行。

## 已批准增量：直接 syscall 缺陷

2026-11-08 用户批准：先以编译机现有 Wine 11.19（含 SUD）进行独立对照，再以设备当前 Proton 精确提交准备 SUD 修复与构建。继续只消费预构建镜像和 prefix；功能失败立即终止，不修补注册表、不完整初始化、不自动切换后端或降级；不替换正式组件、不发布或执行 Git 提交推送。无 HTTP API 变化，api_docs=N/A；运行兼容性影响 product_docs=Required，最终验收前一致性保持 Pending。

## 已批准增量：预构建镜像外置运行时依赖闭包

2026-11-08 用户批准执行依赖审计后的修复方案：在构建端将固定 Wine/Proton Unix ELF 及对应 Box64 原生包装映射纳入镜像依赖输入，保留 ARM64 直接及传递依赖；用可追溯 Debian 软件包和摘要生成独立候选镜像、清单和身份。设备只解压固定产物，不在线安装或跨后端借库。静态门禁通过后继续隔离运行验证，任一实际验收失败立即停止。正式镜像引用替换、发布和 Git 操作仍不在本次范围内。HTTP API 无变化，产品文档一致性继续 Pending。

2026-11-08 用户“补齐后继续”明确批准补齐隔离SDK开发依赖并重编同一Proton/SUD基线，同时修复受支持旧Wine/Proton的正确ABI运行库闭包，门禁通过后继续隔离设备验证。禁止自动关闭原能力、伪造SONAME、在线补装或跨后端借库；仍不含正式引用替换、发布和Git操作。

## 用户指定的硬件插件裁剪范围

用户进一步要求只打包必需运行库，并明确扫描仪、专用相机和 Samba Python 不属于手机游戏环境的需要。本增量在独立候选中移除扫描仪后端、libgphoto 专用设备驱动及 Samba Python 绑定，递归删除仅由这些插件引入的新增库；保留 Wine Unix API 模块、核心库、共享网络依赖、视频采集与图形/媒体链路。旧 Wine 使用的 OSMesa/LLVM 不在本次删除范围，运行时分层另行规划。此处将上一轮“保留全部包插件”的边界收窄到用户明确排除的插件；不豁免任何剩余依赖缺失。不改变 HTTP 契约，产品兼容性文档仍待最终验收。

## 已批准增量：不暴露的主机设备不启动

用户明确“没有的设备不启动…裁掉”，授权在独立Proton候选及离线预生成prefix中裁掉无法向PRoot提供的实体HID/SDL枚举、USB和蓝牙驱动启动与对应独占模块。保留Wine虚拟鼠标键盘、图形/音频、PlugPlay、Eventlog及RPC。此授权只覆盖上述设备路径；不能以硬件裁剪掩盖核心服务1053。仍复用既有RootFS；不发布、不替换正式组件、不执行完整wineboot。API文档N/A，产品文档Required且Pending。

2026-11-08 用户纠正后继续：暂停第7组硬件裁剪，不实施该源码/注册表策略。仅重做同一精确Proton/SUD普通API→直接syscall→普通API探针，以实际返回值和输出时间判定功能，不以设备初始化日志代替探针失败；保留全部日志与未覆盖限制。复用现有预构建RootFS和全新独立预生成prefix，自然等待探针结束，不切换后端、不完整初始化、不引入新库或LLVM。

## 已批准：修复候选接入 APK 与发布资产准备

用户明确要求“开始修复apk和资产文件，缺的库文件打进构建镜像”，批准把已实测通过的精确Proton/SUD接入固定组件目录及离线prefix资产，将GstGL/GUdev预装到原精简镜像的独立v2构建。用户侧只选择Wine并自动下载/解压受校验运行组件，不暴露补库、ABI和技术排错选项。本轮产出原名非testOnly APK及可复核发布资产，不代表授权Git操作、外部发布或远程安装；不引入LLVM、不裁设备、不完整初始化。API文档N/A；产品文档Required并保持Pending。

## 待确认增量：prefix 资源独立交付

用户要求将三套预生成 prefix 放入资产仓库，通过网络/缓存恢复。三套分别对应 Hangover 11.16、Wine 10.0、Proton 11.0。APK 只保留小型固定描述与摘要，不再内置三套 manifest/delta 数据。准备时只获取所选 Wine 的 prefix，优先复用通过校验的已安装资源或归档缓存，缺少有效缓存时使用现有组件下载流程。资源作为内部依赖，不增加用户技术选项。此项调整替代第8组“prefix 内置 APK”的交付方式，不改变 SUD 运行时和最小补库镜像。

API 文档 N/A：复用已有静态资产下载接口，不改变 HTTP 契约。产品文档 Required/Pending，目标仍为 docs/product/runtime-components.md 与 docs/product/wine-library.md；当前 checkout 缺少这些产品文件，最终接受前不能冒称已同步。本增量业务修改等待规划确认；独立归档准备不改变产品行为。
