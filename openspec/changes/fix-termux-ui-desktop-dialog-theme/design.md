# 设计

## 实现

DesktopCreateActivity.bindSelect 与 DesktopSettingsDialog.bind 复用 com.termux.ui.activity.FrostedDialogBuilder，删除创建页不再需要的 MaterialAlertDialogBuilder 导入。统一封装负责主题解析、窗口背景、边线、背景 inset、模糊及深浅色遮罩；两个页面继续负责原有候选列表、初始选中项、更新回调和关闭弹窗。

初次实际交互探针在 editChoicesLight 中复现父编辑面板仍有黑色 dim：仅替换选择框不足以完整修复该入口。DesktopSettingsDialog 在显示 BottomSheetDialog 之前调用 FrostedWindow.applyScrim，使父面板也使用同一深浅色规则，关闭时自动清理暖色遮罩。不调整底部面板几何布局、滑动和背景形状。

不改动 FrostedDialogBuilder、FrostedWindow 或颜色资源。模块内其他弹窗已经采用统一封装，继续保持原行为。浅色错误色的轻微对比度建议不属于本次已确认的漏适配修复。

## 风险和回滚

影响限于两个选择弹窗的外观，注意避免清理遮罩时误关闭桌面编辑面板，或者选择项回调不再更新。回滚仅恢复两处 builder 类型及导入。保留工作树中已有修改；不修改构建配置、Manifest、运行时、资产及 termux-x11。保护路径策略文件缺失，不能声称已通过其策略门禁；此变更不涉及已知高风险文件。

## 验证

先通过实际页面入口在浅色和深色下打开创建页及编辑面板的分辨率选择框，验证窗口处理符合统一规则，选择后更新当前显示值，取消和关闭后清理遮罩且编辑面板保留。使用临时 Robolectric 探针保留复现证据，复用现有 FrostedWindow、ConsoleDialogTheme 和主题偏好测试，不增加仅断言 builder 类型的测试。

验证通过后构建正常 arm64 APK，文件名保持 termux-app_apt-android-7-debug_arm64-v8a.apk，检查签名、架构和无 testOnly。用户手动安装并验收，不通过 ADB 传输或安装。产品文档和归档待最终验收。
