# Wine / Desktop 集合模式与卡片验证

环境：Pixel_7_API_35，`emulator-5554`。APK 本地 `:app:assembleDebug --offline` 构建，覆盖安装；通过正式 App 菜单进入 UI Mode 验证。

## 行为

- Wine 和 Desktop 共用工具栏 Native / Proot 下拉框；两页分别保存选择。
- Desktop 移除原内容区 RadioGroup，新增桌面沿用当前选定模式，导入桌面仅显示在 Proot。
- Wine 仅显示当前模式的配置，计数、网格、列表和导入入口一致；导入通过 Intent 显式传递运行模式。
- 从普通 Files 入口导入仍由用户选择运行模式；收到新 Intent 时清除前一次 Wine 导入的模式参数。
- Wine 使用 Desktop 的 160dp 最小卡片高度，导入卡片同步；去掉单独的横屏卡片布局。网格卡片省略最后运行时间，说明固定两行；列表缩略图和操作区宽度与 Desktop 对齐。

## 实际界面验证

- 同一 `CollectionModeCheck.exe` 分别从 Native / Proot 集合导入，产生两个不同配置 ID；只读核对 profiles，一个 `glibc_termux_box`、一个 `rootfs_proot`，原配置未被第二次导入覆盖。
- 两种模式均只显示对应的一张卡片，计数为 1；Native、Proot 切换与网格 / 列表切换通过。
- Wine 选择 Native、Desktop 选择 Proot，来回切换标签后各自保留选择。
- Desktop 的 Proot 显示新增与导入卡，Native 仅显示新增卡；工具栏内没有原 RadioButton。
- 竖屏 Wine 配置卡、导入卡与 Desktop 新增卡均为 238 × 420px；横屏两页均为 300 × 420px。横屏仍显示工具栏模式选择及操作图标。
- 最终短文案为 `Details` / `详情`，避免窄网格卡片截断 `View details`。
- 系统旋转设置在检查后恢复，测试配置通过卡片菜单删除，测试 EXE 单独清理；不启动游戏，不删除原桌面容器。

本次为 UI 和配置筛选调整，使用实际界面操作、截图、节点尺寸断言和持久化配置核对验证；`git diff --check` 通过。

## 下拉框调整

工具栏的 SwitchMaterial 与两侧标签已替换为 AppCompatSpinner，下拉选项仅有 Native 和 Proot。两页沿用原独立选择状态、筛选与显式导入参数。初始化回调不触发模式变更，重复选择当前项不重绘集合。

APK 重新本地构建、覆盖安装后，实际操作两页下拉菜单选择 Native / Proot，验证标签切换后选择独立保留、Desktop 导入卡片随模式显示；横竖屏均通过。测试结束恢复原旋转设置和 Native 选择，未创建额外配置。

紧凑调整：控件宽度 120 → 96dp，下拉菜单宽度 144 → 96dp，选项行高 48 → 36dp，水平内边距 12 → 8dp。触发控件仍保留 48dp 高度，菜单改用不透明的日夜背景色，避免计数标题透出。

收起状态文字截断修复：Spinner 改为 `wrap_content`，最小宽度 96dp，并显式设置左右内边距，避免默认箭头区域挤占选中文字。展开菜单继续使用 96dp 宽度和 36dp 行高。截图检查 Native / Proot 的实际可见文字，不以无障碍节点返回的完整字符串代替显示验证。
