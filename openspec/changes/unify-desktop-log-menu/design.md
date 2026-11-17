# 设计

从 DesktopActivity.showMore 删除 MENU_ARCHIVE_PROGRESS 常量、条件追加和点击分支。Logs 菜单维持所有桌面统一存在及原有桌面日志内容。在 showLogs 的 UI 回调创建日志窗口时，按最新 DesktopArchiveFlow.hasProgressFor(id) 决定是否提供窗口内的归档进度按钮，调用既有 showProgressFor(id)，复用原有实时更新和作用域保护。归档快照仅影响日志窗口内部可用内容，不影响卡片菜单结构。

不改归档数据、任务、状态机、安装及重装逻辑，不清理历史记录，不恢复旧 Install 项。保留运行时日志、导入卡片进度恢复以及四个 Native 固定入口。单一 Java 文件为本轮业务修改；测试验证两个模式和所有受支持桌面在无记录、运行中、成功及失败归档时菜单一致，且归档归属校验和实时窗口可恢复。

未发现保护路径定义，仅普通 UI 文件与测试。回滚恢复 source-before 中的单一 Java 文件及交付目录备份 APK。Harness bootstrap/strict gate、OpenSpec CLI 和产品基线缺失，记录 Unavailable，不伪造通过。已安装模拟器可只读核对；新版设备安装另需具体授权。

构建前隔离 packageDebug 增量状态及 APK 输出，完整重打包避免 ZIP 空洞。签名、ABI、源码资源、与上一 APK 的条目差异及完整 HTTP 下载校验后，以 termux-app_apt-android-7-debug_arm64-v8a.apk 原名交付。
