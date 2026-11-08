# Design

## Context

以用户已安装环境的自编译 `11.16~trixie+idap.pdb2` 为输入。源码基线为 `791209c`，固定运行组件SHA为 `ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1`；设备配方身份须读取实物核对，不能由旧报告推定。只读盘点保存于 `artifacts/proot-minimization/installed-inventory.json`。正式baseline、docs/product、权威Harness文件未恢复，strict-run为Unavailable。

现有setup在临时目录安装Debian包、生成locale、安装Hangover，随后迁址验证并发布；预生成prefix在宿主Python中根据APK清单复制1713个公共来源文件。基础支持virgl/llvmpipe和PulseAudio；LLVM117.54MiB被Gallium依赖，不属于可随意删除的开发工具。Hangover Depends含多媒体、扫描、相机支持，直接apt purge会牵连Wine；本方案采用已安装实体的显式文件级裁剪，不对活动rootfs执行apt autoremove。

## Goals / Non-Goals

**Goals:** 在相同Hangover运行字节和已声明运行能力内尽量降低常驻及压缩体积；每一步有原因与可验证收益，失败可回退。非必要内容按依赖事实删除，不能用单次启动的加载采样当全部动态功能证明。

**Non-Goals:** 本轮不做Wine功能重编译、PE/ELF strip、prefix硬链接去重、系统镜像/Native/桌面清理；不将约824MiB每游戏独立C盘开销混入基础镜像收益。

## Decisions

### 1. 离线分析、独立候选、确定性策略

输入必须是完成安装且无人写入的RootFS。先记录树摘要、版本与清单，结合前缀公共来源及loader/翻译器保护清单生成dry-run。所有删除目标相对根目录解析，拒绝根目录/空路径/逃逸、跟随符号链接以及进入宿主bind；`.l2s`按实体与链接闭包处理，不能全删，不能因统计将同一实体重复计费。删除计划只应用于任务私有候选；禁止直接裁剪在线共享树。候选复制/归档须保留模式、相对链接及必要实体并提前核对磁盘余量。输入现有游戏prefix或缺版本凭证必须失败。

### 2. 递进裁剪，仍以最小运行集为交付目标

第一组为Wine头文件/静态归档、gettext消息、man/info和无运行用途的文档、安装下载缓存；版权与许可证保留，挂起包事务必须拒绝。完整字体中Serif可删，NotoSansCJK-Regular/Bold为现有前缀引用，字节与文件名保持不变；fontconfig配置/缓存须重新核验。保留locale-archive、C.UTF-8、zh_CN.UTF-8/GBK及gconv闭包，locale源/生成工具只能在构建结束后按显式策略裁剪。

第二组审计包管理与配置工具、Perl、系统服务/hwdb、扫描/相机插件和非目标Mesa驱动。扫描相机动态库、视频音频编解码链、libudev/libsystemd等被Wine/依赖引用的实体不得因名称删除。保留virgl/llvmpipe所需Gallium、LLVM/Z3和加载器；仅当前不支持的GPU ICD/驱动在证明无依赖后进入删除清单，不能误删libvulkan loader。通用包管理工具若确无运行/修复/维护入口依赖则在安装最后阶段删除；后续更改通过重新生成基础环境完成，不用运行时联网恢复已删设施。每组都保存路径清单和保留理由，未完成验证的第二组不打包冒充成功。

### 3. 配方及现有前缀绑定

新裁剪策略和实际工具必须参与setup recipe SHA。若改动 `setup-container.sh`，当前bundle的baseRecipeSha256同时需要重算；保留运行组件身份、全部runtimeFiles和1713个公共来源的摘要，对清单逐文件实际校验，只有字节不变才能重新绑定基础配方。不得改写已安装旧环境receipt或模板身份绕过重建。新策略在构建末尾、迁址/locale/功能验收及发布前运行；取消/失败仅清理私有候选，原共享RootFS与旧receipt保留。是否需要新资产目录版本依据现有recipe版本契约统一修改，禁止仅重命名目录导致消费者不一致。

### 4. 模块与边界

Activity不新增裁剪菜单，不允许用户误删运行文件；Service继续拥有持久任务、串行admission和取消状态。shell在安装完成后处理候选，不能阻断管道子进程或以超时假报成功。X11、输入、PulseAudio及游戏加载入口维持当前契约；存储统计分开显示逻辑大小、分配块、压缩包与临时峰值。组件URL和已发布Hangover v2资产不变，APK小模板保留，组件源缓存与游戏C盘不是本轮清理目标。新增RootFS分发资产若需要发布，另行确认不可变版本及来源，不能覆盖v2。

### 5. 验证、回滚与高风险路径

候选先经过路径/保护清单测试、ELF DT_NEEDED与解释器检查、符号链接闭包和实际PRoot locale/fontconfig验证，再验证prefix无wineboot恢复、中文文件CreateFileW、Winfile图形和32/64smoke。新增OpenGL探针验证llvmpipe/virgl，音频探针验证实际库/连接；没有物理外设不得宣称完整手柄音频硬件通过。旧功能开放问题仍独立跟踪。

权威policy id不可获得，记Unavailable；历史仅 `runtime-script-critical`，不冒充现行规则。候选工具及setup/asset installer/recipe/template metadata均按高风险处理。影响为基础环境完整性与后续prefix准备；验证为上述闭包、故障回滚及真实App；回滚为丢弃未发布候选或恢复原APK/原配方重建，保留原共享树至成功发布。用户本轮已要求开始精简，但具体规划与高风险修改范围仍须按workspace AGENTS.md确认后实施。本轮规划/盘点不执行删除、发布、Git操作或部署。

## Risks / Trade-offs

- 后装动态功能需要被删资源 → 保护源码/清单已知入口、原生依赖和dlopen模块；未知动态链保留并说明不能进一步安全缩小的原因。
- locale与`.l2s`再次断链 → 不删闭包实体，发布后实际PRoot UTF-8/GBK双检查。
- 删包管理后不可原地修补 → 只在确认无消费入口后删除，配方重建作为维护路径；不声称仍是通用Debian开发容器。
- 已安装环境混入配置或测试数据 → 只允许列出的系统子树，禁止携带游戏prefix/运行日志/凭据，逐成员审核。
- 静态资料高度可压缩 → 不把磁盘节省等同下载节省，生成真实归档并单独测量。

## Migration Plan

先完成只读盘点与本规划确认，随后实现dry-run/裁剪及定向测试；独立候选通过真实设备检查后接入新配方，输出原名普通安装APK供用户安装。最终真实App验收后更新产品文档；不承担旧测试环境原地迁移、不自动发布或归档。

## 实施期事实补充

设备Debian Mesa实际ICD文件为freedreno_icd.json/lvp_icd.json，旧启动分支引用的freedreno_icd.aarch64.json不存在。为保留既有Turnip分支的选库语义，本轮将路径对齐当前固定Debian包，并增加真实shell的存在/缺失分支测试；不新增Turnip到v11能力清单，不声明已验证硬件。lvp软件Vulkan独立探针已在未裁剪Vulkan驱动的候选实际创建实例、枚举llvmpipe CPU设备并创建逻辑设备。第一次探针旧文件名失败保留为无效输入证据。

字体批次必须同时隔离旧fontconfig缓存，在候选外生成新缓存，持久记录生成摘要后逐文件移入。失败只移除匹配本轮记录的新缓存，再还原旧缓存/字体；未知变更保留并阻断回滚，未完成的外部生成stage保留供调查。生产安装将已逐批验证的规则合并应用一次，保持逐路径批次原因并核对与逐批应用的树完全一致，避免多次全树扫描造成额外安装等待。

经审计的后续实验分为两个独立批次：build-tools仅安装末尾删除i18n生成源、locale-gen/localedef、APT工具/私有库、dpkg工具及info元数据、APT和debconf缓存；保留dpkg status/updates作来源与挂起事务检查，不再承诺容器内包管理。安装/修复采用全新配方重建，含过期策略或配方的已裁剪scratch必须在空间预算前丢弃重建，不能复用后执行apt。Desktop和Native使用不同根，工具不受影响。gpu-drivers只删broadcom/nouveau/panfrost/radeon四对Vulkan库及ICD；保留freedreno/Turnip、lvp、gfxstream、virtio、loader与LLVM/Gallium。ELF反向边与源码消费入口见facility-dependency-review.json和management-consumers.txt；每批仍须新前缀完整回归。Perl经SNMP动态嵌入、libudev/hwdb与设备元数据、扫描相机/媒体动态插件缺少穷尽证明，保持保留。
