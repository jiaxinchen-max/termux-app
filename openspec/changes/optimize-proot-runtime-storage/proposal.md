# Proposal

## Why

Pixel Fold 实测共享 PRoot RootFS 为 4.84 GiB，其中 Hangover Wine 库 2291 MiB、Proton 11 为 771 MiB、Wine 10 为 656 MiB，合计约 75%。组件源与客体目录重复保存 Wine/Proton，额外占约 1.39 GiB；三套压缩前缀模板占约 790 MiB。Hangover 已解析调试节内容累计约 1.04 GiB，具备优先验证发布包精简的价值。

测量依据：`/home/user/workspace/artifacts/wine-rootfs-size-analysis/README.md`；目录分配空间、压缩文件大小、PE 节内容大小分别计量，不将调试节大小直接承诺为最终节省量。

## What Changes

- 第一阶段：在用户指定编译机从固定 Hangover 11.16 源码交叉编译，优先使用 Wine 原生 PDB 链接期符号分离，生成独立候选 deb/组件包和符号包；固定源码、构建环境、工具链与复用组件摘要，验证 ARM64X 元数据、Wine builtin 标记、32/64 位与手柄兼容；验证不通过则保留原包。
- 第二阶段：保留 Debian 依赖、中文字体/GBK 和精简后的 Hangover 作为默认基础环境；Wine 10、Proton 11、独立 Box64、DXVK 改为按所选配置准备，通过冻结版本目录绑定进入 guest，消除整树复制。Hangover 的 deb 输入和 guest 安装属于不同格式，暂不承诺对其做直接 bind。
- 第三阶段：基础环境安装不再预热三套模板；首次准备某套 Wine 时只生成该身份的模板，之后复用。长任务显示进度、支持取消和恢复，正常启动不承担下载、模板生成或环境重建。
- 全新安装：用户于 2026-11-06 明确“不用考虑老数据，当个全新安装对待”。本轮不提供旧 v10 布局、旧任务、旧模板、旧备份和旧自定义实体的升级迁移；允许先删除测试设备旧 PRoot 环境再构建，不保留该旧环境的 previous。新布局仍须校验成功后发布，失败不能标记就绪。新格式备份必须包含外置依赖闭包，缺失模板延后至实际准备。
- 本次授权清理旧 PRoot 基础环境、模板和失效准备状态；旧测试 PRoot 数据不要求迁移保留。组件源可直接复用则保留，宿主 Termux、Native 和外部文件不属于此次清理范围。新布局投入使用后的常规清理仍须保护有效引用。头文件/文档等列为后续独立评估，不在本轮盲目裁剪字体、语言与图形库。

### 用户可见行为与兼容性

用户首次选择未准备的运行库时，在现有持久准备任务中看到下载与模板进度；失败可重试且保留原配置与 C 盘。Native/PRoot 分组、编辑页只读后端、Wine 选择和图形选择语义不变，不新增后端切换入口，不自动换运行库或渲染器。

### 非目标

不重写 Wine/Hangover 功能，不删除 i386 兼容，不移除 LLVM/Mesa、中文字体或 GBK，不合并各游戏可写 C 盘，不改桌面运行时，不修改 X11/输入底层，不执行 Git 提交/推送、Release 发布或设备部署。

## Capabilities

### New Capabilities

- `wine-runtime-packaging`：运行库制品可验证精简、符号分离和来源追踪。
- `proot-runtime-storage`：基础环境与可选组件分离、版本冻结、绑定、全新安装及离线备份恢复。
- `wine-prefix-templates`：按需模板、完整身份键、并发排他、损坏保护与安全清理。

### Modified Capabilities

无。当前 `openspec list --specs --json` 返回空集合；已有 change 内的双后端、组件分发和恢复规范仅作为历史约束参考，不伪装为正式基线。本变更新建持续能力边界，并保持禁止后端回退、immutable 组件和独立前缀的约束。

## Impact

- 代码范围：`termux-ui` 的 RootfsSetupRecipe、RootfsProotBackend、组件准备、安装互斥、启动/设置/备份 spec 编解码及 Service；`local-games/` 下 RootFS 构建、准备、启动、公共辅助与备份恢复脚本；组件版本引用和清理保护；分发目录下本地候选包工具与校验。
- 条件范围：组件索引只在精简制品得到独立身份、可访问的最终 URL、size/SHA-256 且发布另获授权后接入；不得把本地文件路径或未发布 URL 写成正式来源。现有发布资产保留。
- API 文档：`N/A`，不改变 HTTP 契约；组件现有 HTTPS 下载仍复用现有协议。
- 产品文档：`Required`，验收后创建/完善能力文档 `docs/product/wine-library.md` 与 `docs/product/runtime-components.md`，记录首次准备、存储、全新安装边界和备份行为；本阶段一致性 `Pending`。
- 风险与回滚：ARM64X 去符号工具可能改变加载元数据；bind 不是安全只读挂载；运行库外置后备份、并发更新和引用清理必须同步。采用独立候选制品、不可变版本路径、已验证的写入边界、校验后发布；本次旧测试环境清理不可回滚，失败后重新准备，不发布半成品。
- 流程限制：当前缺少权威 `.harness` agents/workflow/rules、repo-local codex-owner skill 和 strict verifier，缺少正式产品文档基线；可完成本地规划/校验，但不得声称 Harness strict 通过。规划确认及高风险路径确认见本变更运行台账。


## 2026-11-06 自编译版本接入与推送授权

用户在 pdb2 实际上机验证后要求直接使用自编译版本、不做旧数据兼容，并明确授权使用 PAT 推送制品和代码。本轮在该测试阶段授权下发布独立 `rootfs_hangover_11.16-v2` 组件，接入 App 和组件仓库目录，提交并推送本会话已批准实现至其现有 GitHub 来源分支；不推送内部包装仓库。更新后保持同一组件 ID，版本升至 2，以新 URL、真实大小和 SHA 触发下载及配方身份变化。APK 仍使用原名并由用户手动安装。

此授权覆盖上述索引与分发保护路径，覆盖本轮 Git stage/commit/push 与 GitHub Release 发布；不覆盖归档、产品最终验收或未请求的设备重置。发布说明必须保留完整 ABI/混合导出/异常展开、物理手柄及备份恢复未完成的边界，不将测试阶段发布冒充全部任务验收。

## 预生成支持组合的后续边界

`prebuild-wine-prefix-archives` 已获用户 APK 试用授权：当前 PRoot/aarch64/WoW64 自编译 Hangover pdb2 采用 APK 内小模板＋精确公共文件清单，准备时只校验、独立复制并原子发布，不在设备构建模板或全量 wineboot。其他组合仍按原按需生成路径。对应规范已同步；旧验证证据保留，新的 App 中文/图形验收及正式组件发布仍未完成。

## 全新验证缺陷修复（用户已确认）

2026-11-07 用户在收到全新验证报告后明确要求“修复这些问题”，批准报告中的 RootFS 搬迁断链、中文文件名显示和 Termux dbus 缺依赖修复；沿用共享 RootFS 准备/发布高风险范围及原名 APK 本地交付，不新增迁移、Git 或外部发布。发布前规范化 PRoot 模拟硬链接，发布后验证中文 locale；准备时显式升级并验证 dbus/libexpat 依赖，启动不下载。用户可见能力不扩展，api_docs N/A，product_docs Required 且一致性 Pending。
