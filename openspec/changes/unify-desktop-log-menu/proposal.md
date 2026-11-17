# 统一桌面日志菜单

用户指出 Plasma 卡片独有 View progress and log 并明确要求统一，本轮按该具体修复请求记录规划及实施授权。根因为最近一次非空闲归档快照匹配桌面 ID 时，在共用更多菜单中额外添加进度日志项；并非 Plasma 专用功能。

所有桌面卡片更多菜单统一保留 Logs/日志作为日志入口，不再随历史或正在运行的归档追加独立 View progress and log。查看桌面日志时，若有本桌面归档记录，日志窗口可打开既有实时归档进度与日志窗口，保留关闭后恢复查看能力。无归档或其他桌面记录不可错误跳转。导入卡片自身的进度入口保留。

无 HTTP 合约变化，api_docs N/A。用户可见流程变化，product_docs Required，目标 docs/product/desktops.md，一致性及用户验收 Pending。原名 APK 交付；不部署、归档或执行 Git 发布。
