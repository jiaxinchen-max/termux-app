# 设计

当前动态 UI Mode 顺序为 90，Data 为 91，Other 为 99；将 UI Mode 设为 98，使 Data 保留在其前面。两个语言资源中的桥接标题改为 Render bridge，弹窗复用同一资源。

仅修改 LoriePreferences 和两个字符串资源，不改变 Activity/Service、任务状态、shell、X11 后端、输入、存储或下载流程。无注册保护路径。保留内部 install_x11_server_bridge 键及安装回调；界面文案不影响兼容性。验证资源编译、实际排序算法、安装流程现有回归及原名 APK 校验，不新增仅复述实现的测试。

api_docs 为 N/A；product_docs 为 Required，目标 docs/product/x11-preferences.md，一致性验收前 Pending。缺失 Harness 工具按 Unavailable 记录。
