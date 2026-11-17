# 修复 PRoot Wine 的 X server 启动失败

用户反馈 PRoot Wine 无法启动。模拟器连续两次任务均在 STARTING_DISPLAY 报 x_server_not_ready，真实崩溃栈确认 Android 15 拒绝可写的 usr/libexec/termux-x11/loader.apk，app_process 在运行 X server 前中止。原桥接包生成器和 postinst 将加载器设为 0755 是直接根因。

修复打包数据及 APT/Pacman 安装钩子的加载器权限，并让 Games 已安装桥接路径在每次启动准备时移除旧加载器写权限，保留文件内容及终端启动器执行权限。复现设备仅作可逆权限修复及现有应用验证；新 APK 本地交付，不执行安装。

不切换 X server 后端，不禁用 Android 安全校验，不重建 Wine prefix 或容器。无 HTTP 合约，api_docs N/A；产品文档 docs/product/wine-runtime.md Required，一致性验收前 Pending。

## 用户反馈后的纠正

截图证实新桥接包在 dpkg 解包 loader.apk.dpkg-new 时 Permission denied，普通用户、开启安全同步的真实 dpkg 已复现。此前将包内 loader 改为 0444 会让 dpkg 在最终同步时无法 O_WRONLY 打开，必须撤回该打包权限变化。原文件名、包名、版本、依赖及安装入口保持原样；保留安装完成后及加载前只读保障。用户本轮要求恢复既有可安装行为，属于本 change 的纠正授权；不安装新 APK。
