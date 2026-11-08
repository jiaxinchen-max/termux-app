# Proposal

## Why

用户要求基于已安装自编译 Hangover 的 PRoot 环境尽量缩小体积。设备只读盘点显示当前基础 RootFS 约1.90 GiB（du含目录），常规文件实体分配约1.87 GiB；除Wine运行文件外仍有开发资料、多语言资源和不用于本应用的系统设施，需通过可审计裁剪减少安装及分发体积。

## What Changes

- 从当前 Hangover `11.16~trixie+idap.pdb2` 安装完成的环境生成独立精简候选；输出逐路径裁剪清单、保留理由、输入/输出摘要及实际空间测量，不重新编译或修改 Wine PE/ELF 运行字节。
- 删除确认无运行用途的头文件、静态开发归档、文档/手册（保留版权许可）、gettext 翻译和安装缓存；保留简体中文、GBK、C.UTF-8及字符转换运行数据。
- 保留预初始化前缀清单引用的全部公共来源、动态库闭包、X11/输入/音频/图形能力。对 Mesa 非目标驱动、字体、系统服务、打印扫描相机设施单独核验后裁剪；不得仅凭未在一次smoke加载就删除动态依赖。
- 先在独立候选目录验证，再将同一裁剪配方接入基础构建的发布前阶段；失败不发布、不覆盖已验证基础环境或用户游戏C盘。
- **BREAKING**：精简RootFS定位为游戏运行环境，不保留容器内开发工具及通用系统管理设施；重建采用新配方身份，不支持旧测试数据迁移。是否删除apt/dpkg/Perl须先审计运行及修复入口，未证明可删除前保留最小包管理能力。

## Capabilities

### New Capabilities

- `proot-runtime-footprint`：可审计的已安装运行环境裁剪、功能边界、候选验证和配方失效。

### Modified Capabilities

无已恢复的正式baseline；与活动 `optimize-proot-runtime-storage`、`prebuild-wine-prefix-archives` 中的独立prefix、字体及locale完整性约束协同，不重写其历史证据。

## Impact

候选实现范围为 `distribution/termux-app-packages/` 下盘点/裁剪工具、`termux-ui/src/main/assets/local-games/runtime-rootfs-v11/setup-container.sh`、`setup_rootfs_runtime.sh` 及必要的资产安装/验证测试。精简策略身份必须参与配方SHA；若基础setup输入改变，须重新绑定小模板的基础配方摘要并逐文件证明来源字节不变，不伪造旧身份。

HTTP API契约不变，api_docs=N/A；产品运行环境和兼容边界改变，product_docs=Required，目标为 `docs/product/runtime-components.md`、`docs/product/wine-library.md`，验收前consistency=Pending。目前这些正式文档及权威Harness文件缺失，记录缺失，不捏造基线或strict-run通过。

目标是满足现有运行能力的最小可验证环境；不承诺未测得的最终压缩大小，不删除Native/桌面容器、组件源包或游戏prefix，不进行Hangover新一轮功能裁编。保留原运行包与候选隔离以便回滚；新APK保持原文件名、普通安装且由用户手动安装。外部Release、Git提交推送、部署和归档需各自授权。
