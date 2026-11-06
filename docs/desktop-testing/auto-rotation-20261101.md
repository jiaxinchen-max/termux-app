# 模拟器自动旋转排查（2026-11-01）

原因：App 初始设为 Horizontal，同时 Android 系统自动旋转关闭。Auto 映射为 FULL_USER，遵循系统方向锁。

- App 切换 Auto 后，系统锁定状态下真实旋转模拟器仍保持原布局。
- 窗口管理器显示传感器已提出横屏方向 mProposedRotation=3，但 mUserRotationMode=USER_ROTATION_LOCKED，窗口保持竖屏。
- 通过 Android 快捷设置开启 Auto-rotate，App 保持 Auto。使用 adb emu rotate 连续旋转，竖屏底栏与横屏顶部导航自动切换。
- Container 桌面页同样随设备旋转重排；竖屏添加/导入卡片位于不同行，横屏处于同一行。
- 最终系统 accelerometer_rotation=1，device_state_rotation_lock=0:2:1:2:2:0，窗口为 USER_ROTATION_FREE；App 存储 orientation=auto；设备恢复正常竖屏，重力 acceleration=0:9.81:0。

本轮仅修正模拟器和 App 的设置，没有修改应用方向策略或重新构建 APK。

证据目录：/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/auto-rotation-20261101
