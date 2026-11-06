# Wine / Desktop 模式数量标题（2026-11-06）

两页统一使用 CollectionToolbar.getRuntimeCountText，格式为 Native · 数量 / Proot · 数量，按当前模式统计独立配置，不计增加/导入卡片。去掉旧 Exes / Distributions / Containers 计数文案，以及 Wine 横屏隐藏标题的逻辑；两页标题顶部间距统一。

模式菜单项复用 view_collection_runtime_mode：保留前后重叠图标，在同一点击区内显示当前模式 Native / Proot。触控区域固定 64×48dp，模式切换不改变菜单项宽度；无障碍说明和长按提示仍包含当前/下一模式。颜色沿用主题资源。Files 的同名共用工具栏不启用模式项。

本地 assembleDebug 成功，最终 ARM64 APK SHA256 cdf83f2dd86822ace304fc2e5b51f1157cd1e096144c7c41893b7fdbcfdbfedd，已覆盖安装至 Pixel Fold API35（emulator-5556）。通过正式 App UI 验证 Wine Native · 1 / Proot · 1、Desktop Native · 3 / Proot · 1，标题、模式文字、前景图标及过滤列表同步；Activity 切换和主题重建后各页模式保留。Auto 浅色与 Dark 主题下文字可读；Vertical 窄窗口与 Auto 横屏均显示完整模式文字、计数和点击入口。最终恢复 Auto 主题、Auto 方向以及两页原 Native 模式，停留 Wine。没有创建、启动或删除任何配置。

截图、UI 操作脚本和布局证据保留于 /tmp/collection-mode-20261106，构建日志为 /tmp/collection-mode-20261106-build.log。
