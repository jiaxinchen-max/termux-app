# 修复原生 GNOME 鼠标指针不可见

用户报告原生 Ubuntu GNOME 没有鼠标指针，而 PRoot 有。已确认原生 Mutter Android 后端的 cursor_update 始终返回 FALSE。Mutter 48.1 将该值赋给 needs_overlay，并直接控制软件指针图层可见性；该后端没有另行显示硬件指针。

目标：原生 Ubuntu GNOME 在鼠标/触控板操作时显示正确主题指针，并保持正确的触摸隐藏行为。适用共享 Android 后端的原生 GNOME。PRoot 路径保持原有实现。不改变默认触摸模式，不增加迁移。

计划修正回调语义并重建、发布修复组件，按需要更新组件锁定信息及普通调试 APK。风险为共享原生后端的指针重绘或位置异常；通过移动、点击、指针形状与触摸切换验证。回滚源文件及组件锁定版本，使用上一版组件/APK。

没有 HTTP 契约，api_docs 为 N/A。影响用户可见桌面能力，product_docs 为 Required，目标 docs/product/desktops.md，用户接受前一致性 Pending。当前仅完成调查和规划，等待用户确认实施范围；未获本次提交、推送或发布授权。
