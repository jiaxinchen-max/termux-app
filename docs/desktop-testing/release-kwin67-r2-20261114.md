# Native Plasma 全新安装修复：KWin 6.7-r2

用户“修复”授权范围内补齐原构建遗漏，保留r1历史资产。组件发布于 <https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.7-r2>；源码提交 `d2ca85ce75b24670e1bf6294a34bc60dcf3930ff`。仅推送组件源码tag，App工作树未提交或推送。

原编译机复用 `/workspace/termux-packages/build-package.sh -I -f -c -a aarch64 -j8`，构建 `kwin_6.7.5-2_aarch64.deb`，SHA256 `63e66827069d896c802e4acbe04cb4597abb2b49ab0aaac756102079cbf4632c`。根CMake纳入Qt AHB插件、System EGL包装库和Qt renderer probe，显式声明libandroid-stub运行依赖；未拷入旧辅助二进制，保持固定定制Qt。

App同步r2 tag、真实包hash及libkwin.so.6.7.5，所有KWin必需文件hash取自最终deb，增加libei/libeis库清单，保留缺件失败检查。真实deb门禁及错误旧库路径/缺失三类辅助文件负向验证通过；66个ELF原链符号检查通过；初始化23项、非交互安装10项、Hyprland安装6项回归通过。21个release资产均完成无认证完整GET及SHA256对照。其他已有回归失败见r1报告，不宣称全套通过。

APK文件名 `termux-app_apt-android-7-debug_arm64-v8a.apk`，45,358,587字节（43.26MiB），SHA256 `bba31be6f560ea7bac8341607e41826eea6526ddd448df3994806340222af905`。testOnly=false，原签名一致，arm64单架构、16KiB对齐校验通过，七个运行脚本与源码逐字节一致。桌面deb、主题及API资产继续外置。

设备原配置、包列表和旧安装器已备份到 `~/native-plasma-fix-backup-20261114`。已force-stop并确认用户UID无桌面残留。设备缺默认路由，GitHub域名解析失败，ADB/转发下载反复停滞，设备安装及正常UI功能验收尚未完成，不能宣称Native Plasma或Kubuntu桌面启动通过。未清数据、删容器或改默认UI模式。Harness strict脚本缺失，严格门禁Unavailable；产品文档仍Pending，未归档。

构建、源码逐文件对应、ELF、真实包门禁、审核、公开下载和APK证据位于 `/home/user/workspace/artifacts/native-plasma-install-fix`。
