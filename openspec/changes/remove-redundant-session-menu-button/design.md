# 设计

## 当前实现证据
activity_game_session.xml 中 game_session_menu 是底部中央按钮。GameSessionActivity 的点击绑定调用 showControlDrawer；打开抽屉时隐藏按钮，finishHidingDrawer 再显示按钮。handleSessionBack 已经通过 GameSessionBackPolicy 打开抽屉、返回控制器上级或关闭抽屉，系统返回回调与返回键均接入此入口。

## 修改方案
删除按钮布局及三个 gameSessionMenu 引用，检查并移除仅此按钮使用的 local_game_session_menu 中英文文案。保留输入警告及其现有布局，避免扩大调整范围。公共游戏会话界面同时覆盖 Native 与 PRoot，横竖屏使用同一布局。

## 系统边界
仅修改游戏会话 Activity 的冗余视图绑定和 XML/文案资源。Service、任务状态、shell、X11 渲染、输入分发、存储与组件下载逻辑无需修改。控制抽屉的宿主请求入口继续存在。桌面会话不在范围内。

## 验证
执行已有 GameSessionBackPolicyTest，确认打开、逐级返回、关闭、重复事件和退出确认策略；执行游戏退出流程回归及受影响模块编译。构建 arm64 普通 debug APK，文件名保持 termux-app_apt-android-7-debug_arm64-v8a.apk，核对 testOnly=false。用户手动安装验证横竖屏返回手势、返回键与抽屉开关，Native/PRoot 均无悬浮按钮。

## 风险、保护路径和回滚
未识别新增受保护路径；仓库 Harness 规则文件缺失，无法完成其策略核验，不声称 strict gate 通过。修改前保存涉及文件快照，回滚只恢复本次差异。返回策略源码不修改。
