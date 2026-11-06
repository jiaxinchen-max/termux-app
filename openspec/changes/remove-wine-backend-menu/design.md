# Wine 配置页后端菜单清理设计

## Context

动机见 `proposal.md`。当前 `GameFileManagerActivity.selectedImportBackend()` 读取显式导入作用域或 Wine 分组，`QuickGameImport` 按该类型创建独立配置。`GameRuntimeOptionsView.renderRoot()` 仍添加后端菜单，`renderContainer()` 展示只读类型、RootFS 包、容器 ID 和组件入口；现有保存逻辑保留 profile 后端，主代码没有调用 `RuntimeProfile.withRuntimeBackend()` 的转换入口。

## Goals / Non-Goals

目标是移除导入后和编辑时共用的后端页面，并保持当前配置的类型及组件管理能力。非目标见提案；不把删除 UI 解释为需要删去底层后端类型或转换数据。

## Decisions

1. 在共用 `GameRuntimeOptionsView` 移除根菜单、`Page.CONTAINER` 分支、对应 renderer；保留后端 label helper 用于只读副标题。嵌入页与独立 Activity 使用同一 View，无需分别处理；保留已保存 profile 的后端值，不能退回默认 Native。
2. 原组件行移到配置根页，继续通过 `LocalGames.createComponentsIntent(context, gameId)` 打开游戏作用域页面。保留现有组件回传时的 backend mismatch 检查，避免“组件管理”成为间接切换类型的入口。
3. 只删除确认无引用的中英文页面字符串。不改导入的模式按钮文案，Native/PRoot 分组切换仍用于浏览两组配置，不代表转换某个配置。
4. 标题下方增加只读后端副标题；运行配置页直接读取当前 profile，游戏编辑页在既有 IO executor 中读取已保存 profile，横竖屏标题使用相同绑定。隐藏内部 toolbar 的嵌入页仍在内容标题下显示后端；旋转保留当前编辑状态。缺失或不可读 profile 显示信息不可用，不能误标为 Native，也不改变原编辑页的可用性。
5. 产品知识目标为 `docs/product/wine-library.md`，本阶段仅在 manifest 标记 Required/Pending。待用户验收后按产品知识一致性流程确认并写入，不提前声称新 UI 已实现。

### 工程边界

| 边界 | 处理 |
| --- | --- |
| Activity/View | 仅共用 View 的菜单和页面路由变化；保留返回、编辑、保存及组件入口 |
| Service/任务状态 | 不变；保存后环境准备仍沿用原有后端和任务流程 |
| shell/X11/输入 | 不变，不触碰启动器、渲染或输入配置 |
| 存储 | 不变；不重建 profile、container、C 盘或 runtime，不修改已有配置类型 |
| 组件下载 | 保留既有管理页；本次不触发下载/安装，不新增依赖 |
| HTTP API | 无变更，api_docs 为 N/A |

## Risks / Trade-offs

- 删除页面同时丢掉组件入口 → 将组件行独立保留在根页，不改变 gameId 作用域。
- 清理时引入默认后端覆盖 → 审查 profile 构造、保存和组件回传链路，复用现有双模式导入/配置测试。
- host JVM 测试不能证明 Android 菜单显示 → 检查实际 render/page 路由和编译，真机可用时分别验证两种模式及两个页面宿主；设备不可用如实记录，不以静态搜索替代 UI 验收。
- 权威 Harness 规则/verifier 当前缺失 → 仅保留局部事实台账，不能宣称完整 strict gate 通过；不新建替代 policy 或 verifier。

## Protected Paths

不计划修改 Manifest、build/CI、TermuxService、生产 assets 或 gitlink。当前权威 Harness policy 缺失，不能提供真实 policy id。若后续确需触及这些路径，先补影响、回滚和验证，再确认扩大范围。

## Migration Plan

规划确认后实施 UI 清理，运行可用编译及既有针对性测试，直接评审后更新证据。没有数据迁移；回滚仅撤销本 change 的 View/字符串改动。提交、推送、部署和归档不属于本次授权。
