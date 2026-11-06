# UI 根页面共享导航（2026-11-02）

- Wine、Desktop、Terminal、Files、Settings 统一使用 `UiNavigation` 和 `view_ui_navigation.xml`。竖屏为底部导航，横屏为顶部图标标签，当前页面同步高亮，Terminal 位于中央。
- Wine、Desktop、Files 的顶部仅显示操作工具栏，无重复页面标题和返回键；Settings 保留分级标题。
- 根页面通过 reorder-to-front 复用实例，切换不清除文件浏览路径、Desktop 模式或终端内容。
- UI Terminal 入口继承现有 Termux 会话界面，不启用集成 X11；独立于官方终端模式的 singleTask 入口，防止切换终端时清掉其他 UI 页面。

验证设备：Pixel_7_API_35，`emulator-5554`。

- 本地 APK 构建成功并安装；termux-ui 577、app 30 项测试通过。
- 经 App 界面逐一切换五个竖屏及横屏标签，检查导航存在、当前项高亮及 Wine/Desktop 标题栏移除。
- Files 浏览到 `/storage/emulated/0`，跨页面和旋转后路径保留；Desktop 的 Container 单选保持。
- 终端经界面输入并执行 `echo UI_NAV_TERMINAL_OK`，返回输出；切换及旋转后内容保留。
- 活动栈确认各根页面只有一个实例，AndroidRuntime 未记录崩溃。
- 模拟器系统旋转设置已恢复；未改动游戏、桌面环境或运行组件数据。

截图、活动栈及构建日志：`/tmp/component-versions-20261102/nav-*`、`root-navigation-build.log`。

## 标签切换转场修复

- 根标签 Intent 禁用 Activity 窗口动画，源页及恢复页同步取消默认窗口转场；根页面返回也取消窗口位移。
- 仅 `ui_navigation_content` 执行 140ms 淡入。标签栏位于动画容器之外，保持固定；其他子页面的转场不变。
- 本地重新构建并安装；系统三项动画倍率保持 1×，通过实际点击录制五个标签、同 Activity 的 Wine/Settings 切换及返回操作。
- 对录屏 158 帧按原分辨率检测五个图标中心位置，各图标横向、纵向坐标变化均为 0px。正常保留选中和按下颜色变化。
- 录屏及分析：`nav-content-transition.mp4`、`nav-position-analysis.json`；构建日志：`nav-content-transition-build.log`，位于上述临时目录。

## 统一集合工具栏与列表

- 三页共用 `CollectionToolbar`，提供刷新、网格/列表切换、帮助，图标颜色随主题变化。
- Wine、Desktop 使用紧凑横向列表，复用原绑定逻辑和配置、运行、菜单入口。导入及添加操作在列表模式下显示为横向条目。
- 三页分别保存布局偏好；Files 沿用原偏好键。工具栏取消返回按钮后，目录仍通过系统返回键逐级返回。
- 本地构建、577 项 termux-ui 原有测试通过；最终 APK 已安装。
- 经 App 正式入口创建临时 notepad 配置及桌面记录，验证非空网格/列表、刷新、配置入口与菜单；重新安装后确认 Wine 网格、Desktop 列表分别恢复。
- 横屏检查三页工具栏、列表及五个标签顺序，确认 Terminal 第三项。系统方向设置已恢复。
- 通过界面移除自建测试记录，删除容器数据选项保持未勾选；未安装或启动桌面环境。清理自建 EXE 文件并恢复验证前的网格选择。

本轮截图：`collection-*.png`；构建日志：`collection-toolbar-build.log`、`collection-toolbar-final-build.log`，均在上述临时目录。
