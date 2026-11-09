# 设计

LocalGamesActivity 仅保留游戏库和设置两类页面，删除组件目录渲染、轮询与专属布局。保留组件任务服务和运行时安装控制台，初始化运行时任务仓库不再依赖组件目录读取。

LocalGames 提供 createCoreComponentsIntent 携带明确后端；Activity 创建和 onNewIntent 都打开对应 Native/PRoot 设置页。GameLaunchActivity 的现有修复按钮使用新入口，不自动执行构建。

保留环境页 Build from network、状态和 PRoot 文件恢复/备份。版本选择与手动导入仍由游戏编辑页负责。

风险为删除目录界面误伤共享安装控制台或返回栈；通过两后端实际菜单点击、重建 Activity、修复跳转及编辑器版本选择回归验证。回滚仅恢复本次变动前的文件快照，不能回退已有脏工作树。源码改动不涉及新权限或生产部署；用户本轮确认授权范围内实施。
