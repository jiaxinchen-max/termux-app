# 设计

## 原因
RuntimeComponentVersions.accepts 对 PRoot BOX64 返回 false，且 rootfs_box64_0.4.5 具有 hidden 属性。Activity 还查询共享 RootFS 的旧复制路径，无法表示当前组件库外置安装状态。

## 实现
为专用版本选择器放行准确的 PRoot 预编译组件 ID，要求后端匹配且翻译器为 Box64 或未锁定；保持其全局 hidden 属性。统一选择页与编辑页的空 Box64 选择展示，PRoot 对应 rootfs_box64_0.4.5，Native 保留现有默认。删除旧路径合成的空 ID 选项，使用已有组件 active 记录和版本目录判断安装状态。

## 边界
Activity/UI 策略和编辑视图调整；既有 ComponentTasks 下载安装、组件存储、Service、shell、X11 和输入分发不改。ProotDependencyResolver 已支持明确选择此 ID，ProotBindingResolver 将组件内容绑定到 /opt/box64/rootfs_box64_0.4.5，启动脚本消费绑定，不引入另一套加载方式。无新增受保护路径；Harness 规则文件缺失，无法完成其规则门禁。

## 验证
单元测试验证候选范围、hidden 例外及默认选择；Robolectric 使用真实页面验证未安装时可下载、有效安装记录显示并返回准确 ID、缺失目录不显示已安装、编辑保存与重建后保留选择。运行既有依赖解析和绑定测试及启动脚本集成回归。构建固定命名 arm64 普通 debug APK，校验 testOnly=false 和资产保持一致。设备由用户手动下载安装验证。

## 回滚
只撤销本变更快照对应差异；不覆盖既有未提交工作，不执行 Git 或远程发布操作。
