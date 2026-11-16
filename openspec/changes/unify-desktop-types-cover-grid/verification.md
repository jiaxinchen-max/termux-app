# 实施与验证记录

用户已确认“这个ui设计的不错，就照这个来”。当前实现覆盖 Native／PRoot 统一四类型、紧凑自适应封面卡片、名称叠放、主题颜色、安装扫描动画和进入前二次确认。仅在既有保存／安装路径初始化；启动新增的废弃类型拒绝只是枚举判断，没有下载、扫描、修复或重试。

## 自动化证据

- `./gradlew :termux-ui:testDebugUnitTest :app:testDebugUnitTest --tests 'com.termux.ui.desktop.*' --offline --console=plain`：模块 803／803 通过；App 桌面 24／25 通过。失败为 `DesktopSessionControlsTest.confirmedExitSubmitsOnceWaitsAndFailureCanBeRetried`，等待最终退出时超时，未宣称整个命令成功。
- 本轮定向的 App 五组测试（DesktopMutationTest、DesktopCreateRenderingTest、DesktopGalleryRefreshTest、DesktopInstallProgressViewTest、DesktopCreateInstallTest）覆盖保存／安装／启动拒绝与旧记录删除、两种创建模式、Native 部分／全部已创建、PRoot 重复、名称编辑和页面重建、两种模式及网格／列表的浅深主题确认、长名称和大字体布局、动画启停以及保存后安装流程。最终定向命令与结果见本目录 `verification-results.json`。
- `python3 -B termux-ui/scripts/test-desktop-rendering.py`：5 项通过，其中枚举 Java 对应的完整 shell 渲染组合，并验证两种模式的独立 GNOME 在安装／启动外部命令前以 64 拒绝。
- `python3 -B termux-ui/scripts/test-desktop-save.py`：6 项通过，包含保存阶段准备与启动无探针、无修复、无重试的约束。
- `python3 -B termux-ui/scripts/test-desktop-ubuntu-gnome.py`：18 项通过。
- 指定现有正式 `native-ubuntu-gnome-50-r1.bin` 的 `NATIVE_UBUNTU_BUNDLE` 后运行 `test-desktop-native-ubuntu.py`：12 项通过。首次漏传该环境变量未运行用例，已补齐后验证；没有下载或打包新的 Native 资产。
- Java／Android 资源及测试编译通过；`sh -n` 检查本轮两份 shell 文件；`git diff --check` 通过。新增加的 10 份封面／示意 XML 共 5,378 字节，不引入大型资产、权限或依赖。

## 布局和交互范围

`DesktopCardLayoutTest` 检查不同可用宽度、字体倍率及不伸展填满。Robolectric 实际测量 240／360／840dp、1／2 倍字号、长名称下的卡片，检查标题不盖角标、主按钮与更多按钮不重叠、按钮文字未被固定高度截断。动画用例验证可见、隐藏、宿主暂停、祖先隐藏、移出可视区域、移除和禁用系统动画；窗口可见状态由 Robolectric 夹具显式模拟。确认交互覆盖两种模式、两种布局、浅深主题，取消不产生 Intent，重复确认只进入一次；未调用真实桌面。

四类封面使用小型本地渐变和矢量桌面示意，名称叠放在底部渐变上。颜色使用现有浅深主题令牌。Wine 列数和程序卡片实现没有修改。七个原有脏 CMake 子模块保持原状，不纳入本轮提交。

## 未通过项与交付限制

扩展回归中的会话退出用例在 `await(a::isFinishing)` 超时，相关会话实现和原测试无本轮修改；仍保留失败记录，不声称全量桌面回归通过。

`bash termux-ui/scripts/check-module-boundary.sh` 未通过：脚本匹配到 `LocalGamesActivity.java` 既有 `com.termux.app.request_storage_permissions`／`reload_style` 广播 action 字符串；已从基线 HEAD 源码核对相同内容，文件及边界脚本均未改。首次错误地使用 sh 调用该 bash 脚本的输出不算有效证据；以上结论来自正确的 bash 调用。

仓库缺少 `.harness/agents/application-owner.md`、`.harness/workflows/HARNESS_WORKFLOW.md`、`.harness/rules/agent-rules.md`、`.codex/skills/codex-owner/SKILL.md`、`.harness/scripts/verify.sh`，PATH 无 OpenSpec CLI，因此不能执行规定的 Harness strict 门禁。未伪造文件或宣称通过。

本轮没有构建、安装、传输或发布 APK，没有实际设备上的动画视觉验收。独立设计预览仍位于 `/home/user/workspace/artifacts/desktop-install-animation/index.html`，不冒充实际 APK 截图。规范产品文档未作完成性更新，产品一致性保持 Pending，变更不归档。

测试日志与最终 JUnit XML 副本保存在 `/home/user/workspace/artifacts/desktop-install-animation/`；完整回归统计与日志哈希保存在本目录 `verification-results.json`。Git 提交／推送结果记录在本地运行账本及最终交付消息。
