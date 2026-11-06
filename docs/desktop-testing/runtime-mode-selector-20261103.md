# Native / Proot 双段选择器（2026-11-03）

Wine 与 Desktop 工具栏共用 RuntimeModeSelector，直接点击 Native / Proot 切换原有列表过滤与创建模式。沿用每页独立的模式记忆；删除原 Spinner 和下拉列表项布局。

控件使用芯片 / 容器图标、选中细边框及短光条，180ms 滑动指示器只重绘，不调整布局尺寸。触摸区域 48dp 高，常规字体宽度 188dp，大字体按标签所需宽度测量。RadioButton 保留单选、焦点和无障碍选中状态。颜色沿用现有浅深主题。

验证：

- 本地 assembleDebug 成功，APK 安装到 emulator-5554。
- 正式 UI Mode → Wine，点击 Native 显示 Exes · 0，点击 Proot 显示 Exes · 1（原有 wfm）。
- 切换前后控件边界均为 [42,146][536,272]；两个按钮边界分别为 [50,146][289,272]、[289,146][528,272]，checked 状态互斥，工具栏未移位。
- Desktop 独立点击 Proot 显示 Containers · 0，Native 显示 Distributions · 0；Wine 仍保留 Proot。未创建、启动或删除配置。
- 从 UI 设置切换 Dark，检查 Wine / Desktop 控件深色显示；验证后恢复原来的 Auto 主题，Desktop Native、Wine Proot。

构建日志、正式 UI 操作日志、布局 XML 和截图位于 `/tmp/terminal-area-20261103/runtime-selector-*`。

后续 UI 调整：本双段选择器已替换为无文字重叠图标，见 [最新验证记录](runtime-mode-overlap-icon-20261103.md)。
