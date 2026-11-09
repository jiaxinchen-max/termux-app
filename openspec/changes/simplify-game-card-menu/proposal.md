# 精简游戏卡片菜单

用户已明确要求卡片右下角菜单只保留 Edit 和 Remove，其他操作已包含在编辑流程中。仅精简卡片文本弹出菜单，不删除编辑器功能，也不变更启动弹层的其他入口。

横竖屏、Native/PRoot 及不同文件授权状态均只显示两项，顺序为 Edit、Remove。保持原有编辑导航、删除确认与强杀行为。

无HTTP契约变更，api_docs为N/A。影响用户流程，product_docs为Required，拟在验收后维护docs/product/game-library.md，目前Pending。
