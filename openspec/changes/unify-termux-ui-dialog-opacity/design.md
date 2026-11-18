# 设计

## 证据与范围

- `values/colors.xml` 的 `local_games_dialog_surface=#8CFCF7F0`，暗色为 `#94201813`；普通 `FrostedDialogBuilder`、部分自绘Dialog及共用背景的浮动菜单从这里取色。
- 上轮文件管理器新增的 `browser_panel_surface` 为独立90%底色，`showBrowserDialog` 覆盖共享窗口背景。
- `DesktopUsbStorageDialog.solidBuilder` 使用独立的 `MaterialAlertDialogBuilder` 及 `desktop_usb_dialog_surface` 全不透明色。
- `ConsoleDialogTheme.applyWindow` 使用不透明 `local_games_console_background`；`applySetup` 又给TerminalView默认背景填色，`ComponentTaskConsoleDialog` 的日志输出区域也有单独底色。
- 实施复查补充：Desktop设置 `BottomSheetDialog` 的默认Material面板与XML根视图各有独立底色。属于已批准的全部弹窗范围；只给实际底部面板使用公共背景，去掉XML根底色，保留全窗口宿主透明，避免覆盖面板外区域。
- 模块Manifest中的透明权限转接Activity用于调用系统权限，不属于本模块绘制的对话框。Android系统应用选择器及授权弹窗由系统绘制。

覆盖普通选择／确认／错误／帮助等Dialog、文件管理器弹窗、USB存储Dialog、运行环境／prefix／组件日志Dialog、Desktop设置底部弹窗。共用弹层背景的浮动菜单及文件条目菜单一并取统一背景。全屏终端、会话抽屉和普通卡片不因本轮改写背景。

## 单一背景来源

把共享对话框填色改为亮色 `#D9FCF7F0`、暗色 `#D9201813`。文件管理器背景色改为公共色别名或直接引用，不再单独保存百分比；USB构建器复用 `FrostedDialogBuilder`，清除全不透明的特殊构建链。保留局部布局所需的padding、圆角和边框。

控制台窗口也由共享对话框填色负责；日志视图默认不再绘制不透明底层。对于嵌入的TerminalView，仅调整该对话框视图的默认背景覆盖，不修改共享TerminalSession／模拟器调色板，不影响全屏终端。保留程序显式ANSI背景、文字、选区、现有输出以及会话连接。避免在窗口和整块内容上分别叠加85%底色，造成实际视觉接近98%不透明。

只调整背景填充，不设置整个窗口或内容View的alpha，不修改遮罩透明度或模糊强度来冒充背景统一。主题切换、窗口重建、日志刷新之后仍从共同资源获取当前颜色。系统无模糊时也遵循相同填充规则。

## 与当前工作树的关系

起点为 `c161f915` 加上已完成且未提交的 `refine-file-manager-menu-and-details` 实现。本轮新用户指令明确替代其“文件管理器90%、其他页面不受影响”的视觉范围；实施时同步旧提案／设计／规格并标记替代关系，不能留下两份相互矛盾的现行规划。此前84项通过及APK证据保留为历史，背景变化后重新记录受影响验证。七个native子模块现存构建修改不纳入本轮。

## 验证与回滚

更新文件详情现有测试中“局部背景不同于公共背景”和90%阈值的旧断言；验证普通Dialog、文件PopupWindow、USB和自绘控制台的实际窗口背景一致，覆盖亮暗主题及API28／31、保留文字alpha。复用控制台主题测试验证切换主题时不丢日志、不重启会话、不叠加遮罩；检查默认内容背景不叠加填色，而非仅对颜色资源字符串写机械测试。构建原名APK并核验原签名、普通debug、无testOnly、单arm64及HTTP下载哈希。

设备未安装时只记录本地UI模拟及构建证据，不声称真实系统模糊和视觉验收完成。OpenSpec CLI及Harness严格脚本缺失必须按实际错误记录。本轮不涉及持久化格式，回滚本轮共享资源／窗口背景接入即可；不得撤销已确认的文件信息、PE识别和批处理功能。无已注册保护路径policy id，审批范围为本轮新规划。
