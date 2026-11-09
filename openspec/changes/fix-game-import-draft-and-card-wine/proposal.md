# 修复导入草稿与卡片 Wine 信息

## 问题及源码证据
选择 EXE 后，QuickGameImport.createConfiguration 已调用 profiles.save 和 games.save；PRoot 还提前写入独立容器配置。GameRuntimeOptionsView.navigateBack 的 Discard 仅关闭编辑界面。因此用户未 Save，正式游戏库已有记录。这是保存边界错误。
LocalGamesActivity.renderGameRow 当前仅展示后端及可执行文件，没有展示 RuntimeProfile 的实际 Wine 选择。

## 拟议行为（待确认）
新导入先保存为独立草稿，不加入正式游戏库；首次显式 Save 才发布游戏及运行配置并启动准备任务。Discard 或直接退出未保存导入应丢弃草稿，不产生游戏卡片。已保存游戏的 Discard 保留原游戏及原配置。
游戏卡片和列表新增当前已保存 Wine 的名称及版本，包括自定义组件；切换并保存后更新。Native 和 PRoot 使用相同规则。

## 范围与约束
覆盖文件导入、编辑页和运行配置页、返回/Discard/Save、横竖屏及组件选择往返、卡片和列表布局。不迁移或自动清理既有游戏。不删除外部 EXE、共享运行时、共享 Native prefix 或下载缓存。prefix 仅恢复预构建模板，不运行 wineboot，不加入兼容或回退逻辑。设备验证由用户手动进行。

## 文档与状态
无 HTTP 契约变化，api_docs=N/A。用户可见导入流程及卡片信息变化，product_docs=Required，拟更新 docs/product/local-games.md，接受前一致性保持 Pending。本次仅完成分析与规划，等待用户确认后修改业务代码。
