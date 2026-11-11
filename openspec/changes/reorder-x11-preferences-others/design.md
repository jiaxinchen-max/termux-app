# 菜单排序设计

在 preferences.xml 中为 Others 设置 order=99，为 Version 设置 order=100。终端宿主动态添加的界面入口90和数据入口91自然排在两者前面；非终端宿主仍保持 Others 紧挨 Version。移除 Java 中重复设置 Version 顺序的一行，使顺序由资源统一定义。

不变更偏好键、值、导航目标、权限或运行逻辑。直接核对源码与编译后的 XML 菜单顺序，执行正常 debug 构建，核对原文件名、原签名和非 testOnly。低影响布局调整不新增测试。仅撤销本变更的两处源码即可回滚，不处理用户数据。仓库 protected-path 策略缺失，实际范围仅 X11 偏好设置 XML 和 LoriePreferences 的冗余排序语句。
