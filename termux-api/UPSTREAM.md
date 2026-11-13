# Termux:API 内置模块

来源：https://github.com/termux/termux-api
固定提交：fc26ce17e3badf85d4df191af364fcf7798047f1（0.53.0）。
上游 README 声明 GPLv3，保留各源文件版权声明。本地差异：内置主 APK 生命周期、主包路由、同 UID 校验与有界连接；不创建独立插件 Application 或启动器。

## 本地适配

主 APK 注册私有 Receiver/Activity/Service，以 Android 应用 UID 和 Unix socket peer credentials 隔离调用；保留通知监听器的系统绑定权限和 Share URI 授权。API 初始化仅在主进程运行，不更改宿主日志或崩溃处理器。

CLI 优先连接 com.termux://api-v1，握手最多 3 秒，可回退主包显式广播；独立的同 UID 确认通道使不可达宿主在约 10 秒内失败；宿主确认后的普通请求等待结果连接最多 10 秒，交互请求的结果连接最多等待 5 分钟，已连接的有效流不受该握手期限截断。stdin 使用取消管道回收转发线程。USB 使用既有 EmbeddedUsbApi broker，不持有第二套 USB 连接和授权状态。

修正上游传感器断线检测、录音释放、定位请求失败时线程释放、按需权限 Activity 生命周期、通知回复路由及无硬件/系统拒绝操作的错误结果。文本输入限 1 MiB，命令参数限 32 KiB。

CLI 源码与 MIT 许可证在 tools/embedded-termux-api/upstream-cli；NDK 构建与 Termux deb 打包脚本在 tools/embedded-termux-api/build.py。安装初始化部署配套包，不在桌面启动时修复或检查依赖。UPower 的异步电池刷新补丁与独立主循环/超时测试位于 tools/embedded-termux-api/upower 和 tests。
