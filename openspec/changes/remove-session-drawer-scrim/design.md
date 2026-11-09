# 设计

## 来源
activity_game_session.xml 中 game_session_drawer_layer 为 match_parent 全屏 FrameLayout，背景引用 local_games_scrim。浅色颜色为 #99F3ECE3，深色颜色为 #B317110D，均包含透明度。GameSessionActivity 没有为这层另设背景或透明度；开关抽屉通过显示或隐藏该容器完成。

## 方案
仅将该层的 android:background 改为 @android:color/transparent，保留 clickable、focusable、可见性和点击关闭监听。抽屉面板继续使用自身背景；共享颜色和桌面会话不变。

## 边界与风险
只涉及游戏会话 XML。Activity、Service、任务状态、shell、X11、输入分发、存储和组件下载不修改；透明层仍拦截抽屉外点击。无新增受保护路径；仓库 Harness 规则源文件缺失，不能声称完成其策略校验。

## 验证和回滚
已有返回策略测试应通过。构建 arm64 普通 debug APK，核对 testOnly=false 和固定文件名 termux-app_apt-android-7-debug_arm64-v8a.apk，检查包内背景透明且触摸拦截属性保留。用户手动测试两后端、横竖屏及深浅色下的画面、点击外部关闭、返回开关抽屉。保留修改前快照，仅回滚该属性。
