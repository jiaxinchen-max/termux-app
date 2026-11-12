# 设计

## 交互
沿用游戏FrostedDialogBuilder风格：标题“退出桌面？”，正文提示将停止桌面且未保存内容可能丢失，操作为“取消”“退出桌面”。横向/紧凑抽屉退出、X11退出请求及启动等待界面退出共用此入口，不提供仅关闭视图操作。取消和关闭确认框不停止会话，不finish。

确认后防止重复提交，关闭抽屉，显示停止中，后台调用DesktopTasks.stop(distro)。停止成功并确认会话不再活跃后返回桌面列表；失败明确提示并允许重试，不吞异常或伪报成功。复用既有停止流程及单会话清理，不改桌面启动脚本。

删除activity_desktop_session.xml的desktop_session_menu以及Activity里点击、显隐、禁用引用。保持Android返回键开关抽屉、控制中心请求和进程管理入口。没有菜单按钮时，关闭抽屉、旋转及重建均不得恢复按钮。

## 影响与回滚
涉及DesktopSessionActivity.java、activity_desktop_session.xml和中英文桌面文案及聚焦UI测试；属于会话生命周期路径，用户已确认详细方案并批准实施。回滚仅还原本次差异，保留其他桌面修改。无数据变更，无HTTP API。产品知识待用户验收后维护。

## 验证
复用游戏确认交互，验证取消不停止，确认仅停止一次且成功后返回；停止失败可见且可重试。测试抽屉各退出入口、等待界面、旋转及按钮不存在；确保返回键仍能打开关闭抽屉。构建正常assembleDebug，产物文件名termux-app_apt-android-7-debug_arm64-v8a.apk，最终Manifest不得testOnly，覆盖安装不用-t。现有模拟器验证原生/容器退出后状态及重开。Harness工具缺失记录Unavailable，不冒充严格门禁通过。

## 创建后立即安装
当前DesktopCreateActivity.create()只调用tasks.save()后finish，且忽略IOException；DesktopActivity只有手动confirmInstall()才调用tasks.install()并打开PrefixSetupConsoleDialog。

修改创建结果链路：保存前禁用重复提交；只有保存成功才向桌面列表返回新桌面ID。列表消费本次创建结果，解析已保存记录，直接复用安装执行和PrefixSetupConsoleDialog，不再弹第二个安装确认，不依赖用户点击卡片。取消创建或保存失败不提交安装，保存失败显示错误并保留创建页输入。安装启动失败保留新卡片、显示错误并允许手动重试，不删除用户记录；安装完成沿用现有成功/失败marker判断。

创建结果只能触发一次安装：不在onResume或刷新中触发，不因旋转、列表重建、旧结果重放再次安装。覆盖原生与容器模式；安装完成不自动打开桌面。手动安装入口保留供失败重试和未安装记录使用。

影响增加DesktopCreateActivity.java、DesktopActivity.java、必要文案和创建结果/安装触发聚焦测试。验证保存成功单次提交并展示控制台、保存失败/取消零提交、安装失败可重试、旋转/重复点击不重复提交。此项同属本次已确认规划，无HTTP契约，产品一致性Pending。
