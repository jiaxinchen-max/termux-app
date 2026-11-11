# 设计

## 已确认的代码原因

`SettingsPage` 已定义 ROOT → CORE_COMPONENTS → NATIVE / PROOT 的父子关系。`openSettingsPage()` 和横竖屏刷新都会调用 `updateChrome()`，但该方法对全部设置页调用 `setNavigationIcon(null)`。已有工具栏点击事件通过 `navigateBackOrConfirmExit()` 走逐级返回逻辑，因此只需根据当前层级恢复图标。

`GameFileManagerActivity` 的 `showHome()` 和 `showLocal()` 已集中更新目录状态；`navigateUp()` 已按根目录边界返回。共享 `CollectionToolbar` 用于多个根级页面，构造时清除导航图标，应由文件浏览器在构造完成后设置自身点击行为，并随目录状态更新箭头。

## 实施决策

1. 设置工具栏仅在非根设置页显示现有 `local_games_ic_back`，使用现有主题前景色及 AndroidX 的上一级无障碍描述。保持现有设置页点击处理和父子模型。
2. 文件浏览器在工具栏初始化后绑定 `navigateUp()`，在目录状态更新处统一维护图标显示。`localCurrent != null` 表示存在上一级；根目录的上一级为三个入口列表。共享工具栏不全局添加箭头。
3. 导航仅复用现有目录边界，不增加路径迁移、版本兼容、下载、初始化或回退。复制、粘贴、新建、隐藏文件等操作继续使用原有状态控制。
4. 保持已存在的独立组件版本页及会话抽屉子页返回逻辑，避免误把上一级按钮和悬浮菜单入口混为一谈。

## 范围与风险

预计业务修改限于 `termux-ui/.../activity/LocalGamesActivity.java` 与 `GameFileManagerActivity.java`，测试使用 `app/src/test` 的现有 Robolectric 设施。无需依赖、Manifest、原生代码或运行时资产修改。工作区已有 PRoot 和 X11 清理修改必须完整保留。

没有仓库保护路径策略文件可供识别额外保护范围；本次不涉及签名、发布、运行时协议及权限策略。若实施发现需要扩大到这些区域，先记录影响与新增审批需求。

## 验证

- 实际点击设置工具栏按钮，覆盖 Native / PRoot → Core components → 设置根页，并检查根页隐藏箭头。
- 实际点击文件浏览器工具栏按钮，覆盖子目录 → 父目录 → 所属根目录 → 三入口列表，验证不越界。
- 覆盖页面重建或配置变化及横竖屏、深浅色下的图标状态与可访问描述。
- 执行相关已有 UI 回归及完整 arm64 APK 构建；保留 `termux-app_apt-android-7-debug_arm64-v8a.apk` 命名、原签名及非 testOnly 属性。
- 仓库没有 `.harness/scripts/verify.sh` 和 OpenSpec CLI，记录工具缺失，不宣称严格门禁通过。

## 回滚

实施前保存本次目标文件的内容快照；如需回滚，仅还原本次新增的导航和测试改动，保留此前所有工作区变更。该变更不改持久化数据，无数据回滚步骤。
