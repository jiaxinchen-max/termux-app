# Proposal

## Why

扩容后的全新 Hangover 前缀仍出现 Wine loader 隐式 `--init` 超过固定五分钟事件等待，随后 App 的 `wineboot -u` 重复并重叠安装；设备首次准备可能失败或耗时约二十分钟。现有压缩模板是在设备首次初始化后生成，无法消除此成本。准备完成时另有对已脱离窗口的对话框重复 dismiss 导致的主线程崩溃。

## What Changes

- 将已完成 wineboot 的干净 prefix 作为运行组件的预生成制品；以宿主 ABI、Wine/翻译器精确身份、后端、prefix 架构及初始化配方区分，按需校验并解压到每个游戏的独立 C 盘。
- 首期交付当前自编译 Hangover pdb2 的 PRoot/aarch64/WoW64 完整模板，包含 system32 和 syswow64，以同一前缀支持 32/64 位程序。格式可表示其他受支持组合，未生成及验证的组合不伪装为可用。
- **BREAKING**：正式接入该预生成路径的运行组件必须提供匹配模板。缺失、损坏或不匹配时明确失败，不静默在设备执行全量 wineboot，也不切换运行库。自定义/其他运行库原行为不在本期被替换，但其模板不得用于该路径。
- 模板由独立构建/维护入口生成，只进行一次有完成凭据的初始化，等待子安装与注册自然结束、wineserver 安全收尾后打包；不把 loader 固定等待超时当作成功，不通过放大 App 超时冒充修复。
- 修复 RuntimeSetupConsoleDialog 的生命周期和完成回调，使任务结束及页面重建时安全关闭界面；取消/关闭控制台不误取消已经成功的任务。
- 下载、解压、校验、发布和取消使用现有持久准备任务；正常启动仅消费就绪状态。

### 目标、非目标与兼容性

目标是移除首期 Hangover 新前缀在设备上的全量初始化，并修复准备完成界面崩溃。遵循用户全新安装边界，不提供旧数据迁移；不共享可写 C 盘，不合并 Native/PRoot，不新增配置级后端切换，不修改 X11、输入、Wine 游戏 ABI 或物理手柄逻辑。未声称消除全部 Wine 服务启动成本、全部兼容矩阵或历史 ANR；无焦点 ANR 单独回归。

## Capabilities

### New Capabilities

- `wine-prefix-distribution`：预生成前缀的构建、身份、分发、安全解压和新游戏准备。
- `runtime-setup-console-lifecycle`：持久准备任务的界面生命周期、幂等完成和安全退出。

### Modified Capabilities

无已归档正式能力可修改：当前 openspec/specs 缺失，CLI 能力列表为空。关联活动变更 `optimize-proot-runtime-storage` 的按需模板要求在本期首期预生成组合上改为按需获取制品；验收时须同步其规范，不能同时声称设备首次构建和设备只解压。本期不归档任一变更。

## Impact

影响 distribution/termux-app-packages 的构建打包校验，组件内容及其版本/摘要引用，RootFS 前缀准备/身份/空间预算、相关 shell/Java 协议和 RuntimeSetupConsoleDialog。新增静态组件内部模板文件及元数据，不修改组件下载 HTTP 接口或外部 API 契约；api_docs 为 N/A。

产品文档 Required：验收后维护 docs/product/wine-library.md、docs/product/runtime-components.md，当前 consistency 为 Pending；这些文档在恢复 checkout 尚不存在，不伪装为已读基线。

风险包括跨环境注册表/路径、构建机架构与执行能力、归档链接越界、版本失配和解压空间。先产出本地候选及可校验 manifest，独立目标上下文验收后再请求发布/安装。回滚恢复完整旧组件选择和配方；不覆盖已发布 v2 字节、不修改已有游戏 C 盘。APK 始终命名 termux-app_apt-android-7-debug_arm64-v8a.apk，用户手动下载安装。

恢复 checkout 缺少权威 Harness agents/workflow/rules、repo-local codex-owner 和 strict verifier，strict-run 记 Unavailable。恢复初期仅写规划与证据；本轮用户已按末尾记录批准 APK 试用实施，Git 操作和部署仍另需明确授权。

## 当前 APK 试用接入授权与范围

2026-11-07 用户明确要求“接入apk试试”，批准本轮实现及上述共享 RootFS 准备脚本、身份和分发输入范围。采用已独立还原验证的小模板＋公共文件 SHA256 清单：APK 内嵌约 3 MiB 本地试用资产，以当前自编译 Hangover pdb2 的精确 loader、翻译器及公共文件摘要绑定。清单和归档参与配方身份；每游戏复制独立文件，先暂存校验再发布，不在设备重新 wineboot。其他运行组合不替换。容器裁剪延期。

本次是本地 APK 候选，不发布/覆盖现有 v2 组件，不新增虚构 URL；未来正式组件发布及编译机可复现生成仍保留未完成任务。资产来自独立实验前缀清洁化，删除 App 运行标记，记录来源与未完成图形验收；不是从用户游戏前缀导出。APK 安装后经真实 App 显示链路继续中文/图形验收，不以控制台通过替代图形通过。每游戏展开仍约824 MiB。Git、Release发布和设备安装不包含在本轮操作内；用户自行安装。


## 普通 Wine/Proton 后续扩展（2026-11-08）

用户已批准 `reuse-prebuilt-prefix-for-standard-wine`：复用同架构初始化骨架，各目录 Wine 使用自己的系统 DLL 与精确组件身份。普通 Wine 10.0/Proton 11.0 新配置改为只恢复模板；本文件先前“其他运行组合不替换”等表述限于首期历史阶段，自定义 Wine 仍保持原流程。实施及验收由新变更追踪，未提前归档或宣称完整兼容验收。
