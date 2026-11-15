# 简化桌面删除确认

用户已确认方案：取消“同时删除容器资料”勾选项，PRoot 删除统一表示删除桌面及其容器内全部数据，一次明确确认，并说明已导出的备份保留。

修改限于 DesktopActivity 删除确认与中英文提示；复用已有 deleteAndRemoveContainer，不修改容器移除脚本、归档、共享存储或 native 软件包。native 继续只删除桌面配置及会话状态，提示准确区分。

用户于本轮“是的，就这么整”确认上述范围，作为实施授权。沿用用户先前任务结束推送代码保存进度的授权。未授权在真实设备删除用户容器。

不涉及 HTTP API，api_docs=N/A。产品流程改变，product_docs=Required，目标 docs/product/desktop-management.md，consistency.status=Pending，用户验收后再完成产品文档，不在本轮归档。
