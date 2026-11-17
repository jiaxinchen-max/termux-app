# 桥接安装回归纠正交付

用户截图的 loader.apk.dpkg-new: Permission denied 已用普通 UID 真实 dpkg 复现。上次把包内 loader 改为 0444 是回归原因：dpkg 在安全同步阶段要以写方式重新打开暂存文件。此前模拟 APT 与单独执行 postinst 的验证未覆盖该过程。

已撤回包内权限变化，恢复原 0755；安装完成后和 Games 加载前只读保护保留。未关闭安全同步，未改原 deb 包名、版本、依赖、APK文件名、应用包名或应用标签。问题APK已先撤回，再完成本轮纠正交付。

新增真实普通用户 dpkg 首次解包及只读旧 loader 重装测试，先红后绿。8项Python测试通过；模拟器120.24.191.156:8057的应用私有隔离根目录内，真实Termux dpkg首次解包、只读旧loader重装均退出0；安装钩子后loader444，直接Android动态加载帮助退出0。Pacman包和安装钩子也已验证。

原名APK termux-app_apt-android-7-debug_arm64-v8a.apk：45,484,302字节，比此前正常交付版少2字节；SHA256 78cbb91812ece05dd966ca09f140d8a4d259b3ad997afdfae745795004b0f1af。arm64、v1/v2签名、77项资产及内嵌deb一致性、非testOnly、应用名称对比、HTTP完整下载和sha256侧文件均通过。

新APK未安装到设备，完整Wine会话仍Pending。Harness脚本缺失，真实执行exit127，不能宣称门禁通过。产品一致性和最终用户验收Pending；不归档、不进行Git写操作。
