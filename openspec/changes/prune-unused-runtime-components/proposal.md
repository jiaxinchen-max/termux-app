# 清理无用运行组件与旧安装配方

## 目标
按实际引用清理运行组件库，保留支持当前游戏功能所需的资产，避免把构建素材、内部依赖与可安装组件混在一起。审计证据见.harness/runs/prune-unused-runtime-components/evidence/audit.md。

## 规划范围
1. 从APK及组件仓库的运行目录移除独立rootfs_hangover_11.16下载项，保留自编译glibc_box64_0.4.4；Hangover作为预装Wine选择继续存在，构建归档独立记录、不删除。
2. 删除APK内不可执行的v10/v11 RootFS配方、分支与专属测试，当前只支持v12，不新增兼容或回退。
3. 保留Debian+Hangover预装基础镜像、fonts、scripts、prefix-apps、可选Wine/Proton、图形层、所需Box64和全部隐藏prefix依赖。字体和prefix-apps均有真实逐文件引用。
4. 统一APK与组件仓库catalog-v1.json、repository/index-v1.json为29项当前目录，补齐漏掉的镜像/prefix并使用当前Proton v2引用。
5. 用户复核确认Box64 0.4.4属于自编译可选版本，撤销对应远端资产删除计划。保留镜像构建源包、符号、证据以及桌面资产。

## 非目标与文档
不裁剪已使用的组件内部文件，不破坏已验证prefix，不重建Wine或镜像，不清理设备用户数据。api_docs=N/A：调整组件目录内容，不改变HTTP契约；product_docs=Required，目标docs/product/runtime-components.md，验收后一致性核对，当前Pending。

## 用户补充与执行授权
用户明确要求清除过时资产，并将自动完成安装初始化的内部依赖从安装条目移除，强调开箱即用。按此授权实施原清理方案，并将fonts、scripts、prefix-apps、默认Native Box64及PRoot基础镜像标为内部自动依赖；保留Wine、图形和自定义Box64管理分类。Native网络构建只负责主机基础环境，保存游戏时自动准备运行组件与prefix；PRoot网络构建恢复基础镜像，游戏保存按选择补齐外置Wine/图形/prefix。隐藏不改变依赖解析。

## Box64用户纠正
用户明确要求保留自编译新版及逐游戏选择能力。已从清理范围排除0.4.4并恢复目录、探测和原测试。复核发现当前自定义Box64逐游戏选择链路存在，但内置目录版本的独立安装与选择尚未接完整；记录为功能缺口，不以删除代替修复。本清理变更不擅自扩展该版本选择行为。
