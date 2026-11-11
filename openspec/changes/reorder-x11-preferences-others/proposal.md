# 调整 X11 偏好设置菜单顺序

用户明确要求将 Others 移到 Version 前面。当前 XML 已相邻，但终端宿主把 Version 排到100，并在90、91插入界面切换和数据入口，导致 Others 与 Version 分离。

本次仅把 Others 固定为倒数第二项，Version 为最后一项。用户的直接指令是本次确定顺序的实施授权，其他菜单保持相对顺序和原功能。

不涉及 HTTP 契约，api_docs 为 N/A。影响用户可见菜单顺序，product_docs 为 Required，目标 docs/product/terminal-x11.md，验收前一致性保持 Pending。仓库缺少 Harness 引导和严格脚本、OpenSpec CLI，不声称严格门禁通过。
