# 设计

## 变更前代码依据
- termux-ui/src/main/java/com/termux/ui/desktop/domain/DesktopDe.java：GNOME 的 creatable 为 true，supportsNative 直接返回 creatable，supportsProot 额外排除 GNOME。
- runtime/DesktopCreationCatalog.java：基于枚举顺序产生列表，nativeOptions 隐藏已创建项；目前顺序为 XFCE、Plasma、GNOME、Hyprland、Ubuntu GNOME。
- activity/DesktopCreateActivity.java 与 res/layout/activity_desktop_create.xml、item_desktop_option.xml：纵向 LinearLayout 配合 Emoji、名称和描述。
- runtime/DesktopRendererPolicy.java 与 assets/local-games/desktop_rendering.sh：Java 和 shell 均仍允许 Native 独立 GNOME；desktop_install.sh 对独立 GNOME 仅拒绝 PRoot。
- app/src/test/java/com/termux/ui/desktop/activity/DesktopCreateRenderingTest.java：仍有 Native 五张卡及位置索引预期，需要随新行为修正。

## 支持矩阵
统一有序目录为 XFCE4、KWIN_PLASMA、UBUNTU_GNOME、HYPRLAND，不通过枚举声明顺序推导 UI 顺序。Native 与 PRoot 使用相同目录；类型支持判断、渲染策略及正式 shell 入口必须一致拒绝独立 GNOME。保留 GNOME 的可读身份仅用于旧记录展示与显式删除，不进行兼容迁移或自动映射。

不删除 Ubuntu GNOME 共用的 isGnomeSession、Mutter、Shell、扩展、主题或安装依赖。已有四类型的底包、宿主／容器渲染组合、普通用户和密码保持原有约束。

## 紧凑卡片与自适应排列
用户最新反馈取代固定两行两列要求。创建页 desktop_create_des 与桌面网格均根据实际可用宽度自动排列；四种桌面仍按 XFCE、Plasma、Ubuntu GNOME、Hyprland 的阅读顺序出现，窄屏可少列，宽屏可多列，不为凑两列强制放大卡片。

已核对 Wine 的 res/layout/item_local_game.xml：封面 56dp、正文左右内边距 6dp、标题 12sp、辅助文字 9sp、按钮可见高度 28dp。LibraryGridColumns 以可用宽度计算列数，但竖屏至少四列、最小卡宽 70dp 的硬下限不宜原样套到桌面卡片；桌面还包含明确启动按钮和更多菜单。复用其按宽度排布的思路，桌面使用独立、可容纳实际内容的参考最小宽度，不能改动共享 Wine 列数参数而影响 Wine 程序页。

普通字号下桌面参考宽度约 128dp（约为上一版常见手机卡宽的 80%）、封面基准高度约 52dp、卡片间距约 6dp、正文边距约 6dp、标题 12sp、辅助文字 9sp。宽屏增加列数而不是拉宽卡片；剩余不足一张的空间留在行尾，最后一行不伸展卡片。窄屏、分屏或大字体时以实际内容可读性为准降低列数或增高，不能水平溢出、重叠或裁掉主操作。

每张卡片包含紧凑横幅封面和完整名称，封面不按卡宽比例撑高。封面使用四份有区别的本地轻量矢量桌面示意图，不冒充实机截图，不引入网络请求或大型资产。创建页选择卡片整体可点选，选中态有边框与勾选，不能只靠颜色区分；名称支持换行，触控目标不少于 48dp。

用户进一步要求文字放到封面：桌面名称叠放在封面左下角，以浅色文字配合底部渐变衬底保证两种主题和四种封面上的可读性。正文删除重复名称，只保留参数、状态和底部操作。模式角标位于右上，扫描光带位于最底边，与名称留出间距；长名称或系统大字体时封面可增高，不裁切或盖住名称。此规则同时适用于创建页封面卡与桌面网格卡；列表仍按原文字区布局。

Native 已创建卡片保留，显示“已创建”，禁止重复创建。可选目录和已创建状态分开表达，默认选中第一项可创建类型；全部创建后保留网格、禁用按钮并说明原因。PRoot 仍允许同类型多个实例。同步中英文文案，移除“已创建项隐藏”的过时提示。横竖屏均按实际可用宽度自动排布，页面可纵向滚动；大字体下名称不覆盖封面和状态。

卡片选择后继续更新现有渲染选项、Ubuntu GNOME 提示、Hyprland 主题和自动名称，不因位置变化误选环境。网格刷新及页面重建应维持合法选择，保存进行中防止重复提交。

## 拟修改路径与风险
业务路径包括 DesktopDe、DesktopCreationCatalog、DesktopRendererPolicy、DesktopCreateActivity、必要的 DesktopEnvironment／DesktopTasks 类型拒绝逻辑、创建页和卡片布局、本地 drawable、中英文 strings，以及 desktop_rendering.sh 和 desktop_install.sh 的类型白名单。desktop_start.sh 复用既有参数解析与类型拒绝，不新增文件扫描、依赖探针、重试、下载或初始化。

安装／启动类型白名单属于高风险路径；影响为独立 GNOME 两种模式均不可执行，Ubuntu GNOME 必须继续可用。本规划的实施确认同时覆盖上述精确类型约束变更。回滚只撤销本变更新增差异，不 git reset、不删除用户目录、不改七个既有脏 CMake 子模块、不覆盖历史组件发布。设备无自动部署。

## 追加：安装动画
用户截图中的圆环来自 res/layout/item_desktop.xml 的 desktop_install_progress，DesktopActivity.bindCard 仅在 INSTALLING 状态调用 show，其他状态调用 hide。它不是日志弹窗内已有的横向进度条。

将此处改为封面下方的细长扫描光带：约 1.2 秒完成一次从左至右的扫动，前端高亮、尾部渐隐，出界后从左侧重新进入，不做往返弹跳。保留中间桌面图标、封面和模式角标，光带不遮挡名称和状态。浅／深色使用现有主题强调色与适当低对比轨道，不固定蓝色或白色。

此效果表示安装仍在运行，不伪造百分比。动画仅在安装状态且卡片可见时运行；安装成功、失败、取消、卡片移除或页面不可见时停止，返回仍在安装的页面时恢复。尊重系统关闭动画的设置，改为静态状态提示。只使用轻量本地绘制，不新增视频、Lottie 或大型图片依赖。安装日志、状态更新及后台任务语义保持现状。

额外拟改路径为 item_desktop.xml、DesktopActivity 的动画可见性控制和必要的轻量绘制组件／资源。实施前可通过 artifacts/desktop-install-animation/index.html 查看基于用户截图的动效示意；它是设计预览，不是已实现 APK。验收覆盖运动方向、亮部拖尾、浅深色、页面进出与安装结束后停止；不为纯绘制细节编写镜像实现的测试。

## 追加：深浅主题与启动防误触
用户明确要求深色／浅色适配，并在卡片左下角增加明确的启动按钮及二次确认。当前 item_desktop.xml 和 item_desktop_list.xml 的 desktop_primary 是 MaterialButton，但使用 TextButton 样式、无填充；DesktopActivity.bindCard 还把整个卡片点击绑定到了同一个 primaryAction。primaryAction 在判定已安装后直接 open，而 open 打开会话页，会话页能够自动启动桌面，所以确认必须发生在进入会话页之前。

为四种桌面的两种模式统一使用主题颜色令牌，覆盖封面上的图标／角标／选中态、扫描亮部与轨道、文本、按钮、禁用态以及确认弹窗。浅色避免白光不可辨，深色避免低对比灰字和刺眼底色；跟随系统或应用的现有主题选择，主题切换不重置实际任务、用户选择或制造新的启动请求。

网格卡片左下角为有填充背景与明确边界的紧凑实体主按钮，右侧保留更多菜单。按用户反馈，普通字号下按钮可见高度约 28dp、字号约 10sp，通过透明内缩保留至少 48dp 的触控高度；实际 Android 实现可使用 inset 或 TouchDelegate，不为小按钮增加大块实色背景。系统大字体时允许按钮增高，不裁切文字。已安装未运行显示“启动”，已运行显示“进入”，启动中／停止中禁用重复操作。列表布局采用一致的实体按钮及确认规则。不得仅靠文本颜色表达按钮可点击性。

点击“启动”或“进入”后使用现有 FrostedDialogBuilder 显示确认框：标题为“启动桌面”／“进入桌面”，正文显示实际桌面名称和 Native／PRoot 运行模式；“取消”或返回键不产生会话 Intent，不执行桌面启动；点击确认才进入会话页。连点只能产生一个确认框和一个进入请求，确认前不进行持久初始化、依赖检查或预启动。卡片封面和正文不再复用启动回调；更多菜单、安装确认和安装日志入口各自保持原语义。

额外实现范围为 item_desktop.xml、item_desktop_list.xml、DesktopActivity 的点击和确认逻辑及中英文文案／必要的主题资源。不新增系统权限或依赖。交互回归验证：点击正文不启动、取消不进入、确认恰好进入一次、连点防重、安装中只查看日志，以及 Native／PRoot、网格／列表一致性。独立预览补充深浅主题切换、实体按钮和可操作确认框；预览确认不会操作真实桌面。

## 验证计划
验证四类型在两种模式下的完整矩阵、固定顺序、Native 已创建禁用和全满状态、PRoot 可重复创建；通过 Robolectric 检查四类候选、不同可用宽度下的自适应列数与溢出边界、封面和名称、选择状态及参数联动，优先按类型定位而非依赖旧枚举索引。使用隔离 shell 测试确认 Native／PRoot 独立 GNOME 在调用 apt 或会话程序前拒绝，Ubuntu GNOME 正向路径通过现有定向回归；不重做无关桌面安装。

实现后检查窄屏、大字体和横屏布局以及资源增量；仅实际执行并通过的检查记为通过。当前缺少 OpenSpec CLI、仓库要求的四份 Harness 引导文件与 strict verify 脚本，不能宣称已通过 Harness 严格门禁。最终用户接受后才更新规范产品文档与一致性结论。
