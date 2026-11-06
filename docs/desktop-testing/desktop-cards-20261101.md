# Desktop 入口整理（2026-11-01）

- 删除列表顶部独立导入按钮及归档状态、进度条、详情入口。
- Container 列表中新增“导入桌面”卡片，与“添加桌面”统一样式及最小高度；空列表同样保留操作卡片，Native 仅显示添加卡片。
- 当前归档任务的“查看进度和日志”入口按 desktopId 放入所属桌面的 ⋯ 菜单。尚未生成桌面的导入任务，入口放入导入卡片的 ⋯ 菜单。
- 重新进入列表不自动打开旧归档日志；用户发起操作时仍显示进度，后台运行后可由所属菜单重新打开。

APK 在本地 macOS 使用 JDK 17 / Gradle 8.7 构建，未调用远程编译机构建 APK。`:termux-ui:testDebugUnitTest` 521 项全部通过，`:app:assembleDebug` 成功。

最终 APK SHA-256：e51f7f71ac827b4caa3e9b7d9abc136c1a28777892e59687f2104c9878696910。

模拟器 emulator-5554 的 App 界面验证：

1. Native/Container 均无顶部归档详情；Container 显示同规格的添加、导入卡片，Native 不显示导入卡片。
2. 点击导入卡片打开正式共享存储选择器，取消后正常返回；未重复导入大型容器。
3. 通过 XFCE4 #1 菜单实际导出到新的 Download 文件，后台运行时首页没有全局进度区。
4. Plasma 菜单无该任务的归档进度入口；XFCE 菜单有入口，可打开正在执行的导出进度。对应 UIAutomator XML 已断言。
5. 真实导出任务 22c69d5a-3be7-4b8e-b7ec-ea4709e9d4d6 完成，状态 SUCCEEDED，desktopId=desktop-f9bebc08。重新进入 Desktop 无自动日志弹窗，仍可由 XFCE 菜单打开完成日志。
6. 删除本轮新生成的 526 MiB 测试归档；未删除已有归档或桌面。

本地 CMake 文件原有的误输入已备份至 /tmp/desktop-cards-20261101/CMakeLists.before.txt，并仅恢复 endfunction() 语法。

APK：/tmp/desktop-cards-20261101/termux-desktop-cards.apk。

截图、XML、归档任务记录、构建日志及源码哈希：
/Users/chenjiaxin/.codex/visualizations/2026/10/29/01a18875-aca1-7223-a7a3-d6450b838d79/desktop-fixes-20261030/desktop-cards-20261101/

后续菜单顺序调整：Logs 移到最后一项；在 Native 与包含归档进度入口的 Container 菜单中均通过 App 打开并核对，保存 XML 断言及截图。本地 :app:assembleDebug 成功；APK 位于 /tmp/desktop-menu-logs-last-20261101/termux-desktop-logs-last.apk。
