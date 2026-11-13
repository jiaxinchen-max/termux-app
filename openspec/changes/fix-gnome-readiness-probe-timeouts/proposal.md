# 修复 GNOME 就绪探测单次超时中断重试

在已批准的鼠标修复回归中，原生 Ubuntu GNOME 两次重启因 GetExtensionInfo 单次查询超过 5 秒而退出，分别涉及 Dock 和 AppIndicator。既有代码配置了 30 秒总就绪期限，但 subprocess.TimeoutExpired 未捕获，导致总期限内不再重试。

目标：在既有总期限内处理探测超时并重试；进程退出、扩展明确报错或总期限耗尽仍失败。适用于共享启动器的原生与 PRoot GNOME。不修改总等待期限、不跳过就绪检查、不调整渲染模式，不迁移旧数据。

影响本地桌面启动流程，api_docs 为 N/A（无 HTTP 契约），product_docs 为 Required，目标 docs/product/desktops.md，一致性 Pending。需要修改 APK 携带的启动脚本，实施时生成名称固定为 termux-app_apt-android-7-debug_arm64-v8a.apk 的普通 debug APK，无 testOnly，安装不使用 -t。风险为重试逻辑吞掉真实故障；通过明确错误、死亡进程和总期限测试验证。回滚启动脚本及 APK。当前待用户确认，不修改业务代码，不提交或推送。
