# 设置的基础组件入口

设置首页将 Native Environment / Proot Environment 合并为一个 `Core components`（基础组件）入口，摘要为 Native / Proot。进入后按 Native、Proot 展示独立安装状态；再进入对应模式，使用已有状态、网络安装 / 重建和 Proot 备份 / 恢复操作。

返回路径改为 Native / Proot → 基础组件 → 设置首页。各层继续只在工具栏显示问号，子页标题放在内容区域。

本地构建 APK，通过 `SettingsPageTest` 4 项和 `LocalGamesBackNavigationTest` 15 项测试。正式界面验证使用 Pixel_7_API_35（emulator-5554），覆盖首页单入口、Native / Proot 子页、逐级系统返回及横竖屏；未触发安装、重建、备份或恢复操作，验证后恢复原旋转设置。
