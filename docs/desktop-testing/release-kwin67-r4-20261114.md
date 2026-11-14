# Native Plasma 延迟黑屏修复 r4

r3交付后用户报告App仍运行但桌面黑屏。现场确认KWin存活、plasmashell退出，两份SIGABRT堆栈都进入Qt5Compat GraphicalEffects触发的AHB初始化。私有EGL身份函数没有动态导出；真实r3包的符号门禁和运行时dlsym均失败。导出该函数后，真实窗口验证继续发现手写create_buffer参数缺少flags，导致Wayland断连。

r4显式导出身份函数，保留renderer/identity门禁，并让客户端从服务端同一份XML生成接口，发送flags=0，按v1绑定。指定Linux编译机原build-package.sh构建6.7.5-4成功；编译前后活动源码全量比较无差异，归档3326个文件与组件提交70a2e5c794dfc0e0c4f6d62106b267e200d4cc03一致。旧续编缓存和仅修复身份的候选均拒绝，未发布。

最终deb SHA256：cd29a2fbefcf81930ac740b1ac3fef71bed29620164d28ff41b9c937936aa4c4。包门禁包含实际动态符号、哈希、架构和缺件负向检查。实际System GLES Qt renderer匹配；三轮Qt5Compat真实AHB特效窗口正常退出。先force-stop核验仅僵尸残留，再从正常UI启动；壁纸、面板、显示设置及Dolphin窗口画面正常，同一个plasmashell持续超过216秒。客户端通过现有会话环境启动，未声明触控入口回归通过。第一次双客户端启动命令的ADB通道超时，但实际进程和后续截图确认存在；改为stdin=/dev/null的单独Dolphin启动正常返回。截图均通过设备/本地SHA256校验。

组件：https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.7-r4 。APK保持原文件名、原签名、testOnly=false和43.26MiB，组件大资产外置。App工作树未提交推送。

Kubuntu、真实硬件、输入、停止、回滚和完整回归未验收；status文件仍显示starting，未据此推导运行失败或成功。Harness strict脚本缺失，门禁Unavailable；产品文档一致性和用户验收Pending，未归档。详细构建、崩溃、实际Qt测试、截图和下载证据在 /home/user/workspace/artifacts/native-plasma-black-screen 。
