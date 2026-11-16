# 原生 Ubuntu GNOME 50 外观组件

本目录从真正的 Ubuntu Resolute `Architecture: all` 数据 DEB 生成只读主题、四个扩展和壁纸，不携带 Ubuntu ELF，不进入 APK。来源 URL 和 SHA256 固定在 `build-profile.py`：Yaru 26.04.5.1ubuntu、gnome-shell-ubuntu-extensions 50.26.04.8ubuntu、ubuntu-wallpapers 26.04.2。扩展为 Ubuntu Dock、AppIndicator、DING 与 Tiling Assistant，元数据必须支持 Shell 50。

在指定 Linux 编译机使用 Python 3.11+、dpkg-deb 与 glib-compile-schemas：

```sh
python3 termux-ui/scripts/native-ubuntu/build-profile.py \
  --work /workspace/artifacts/native-ubuntu50/work \
  --output /workspace/artifacts/native-ubuntu50/native-ubuntu-gnome-50-r1.bin
```

输出为确定性 USTAR/gzip，`.bin` 后缀避免 Android 资源打包器自动解压。内部 hardlink 仅复用此前出现的普通文件；安装助手复制为独立文件，拒绝外部路径、符号链接及管理目录外文件。扩展私有 schema 使用 strict 编译，许可、构建来源和逐文件哈希随包保留。

DING 的 GJS/kill 路径适配 Bionic，并去除全局进程扫描。该 Native 组件没有容器专用的 30 秒启动看门狗改动。实际产物为 26,178,566 字节，SHA256 `5d043a773b503c472743c1902d8347ea8ba6c8f7f88c79868e5f661b26288e0e`；原编译机及本地以此产物运行 12 项组件测试通过，模拟器已直接下载并验证摘要。原编译机第二次完整构建的大小与 SHA256 均相同。

已发布至资产仓库 [native-ubuntu-gnome-50-r1](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/native-ubuntu-gnome-50-r1)，作为公开集成验证版，源码、来源锁与校验清单一同提供。公开原始 URL 的大小及 SHA256 回读通过。安装助手、安装器和 Java 安装状态同步使用新文件名及摘要。下载成功且校验后原子写缓存；有效缓存可离线复用。运行组件测试使用 `NATIVE_UBUNTU_BUNDLE=/path/to/native-ubuntu-gnome-50-r1.bin`。

保存／安装时验证资源、应用依赖和版本，生成私有会话、默认设置及执行描述；启动只执行保存结果，失败退出，不执行静态预检、初始化或修复。新 Native 完整 GUI 及其独立应用组合仍待验证，整体不是可发布状态。

## 原生设置中心
`packages/gnome-control-center` 固定GNOME Control Center 48.5原始源码和SHA256，
`android-session.patch` 仅构建会话可管理的七个上游面板：外观、键盘、多任务、通知、
搜索、声音、辅助功能。网络、电源、登录账号、打印机等需要Android系统权限或Linux
系统服务的面板不构建、不注册。保留真实上游Gtk/libadwaita界面和GSettings/PulseAudio
实现，不用假设置程序替代。`packages/tecla` 固定键盘布局预览器48.0.2。

在Termux包构建树的`x11-packages/`中加入这两个配方，先构建tecla再构建设置中心，
依赖从标准Termux仓库获取。构建机已有未提交配方时不能覆盖同名目录。原生deb作为
外置组件发布；APK不内置这些二进制。固定发行版为 `native-gnome-apps-48-r2`，安装脚本在交给 apt 前验证三个 deb 的 SHA256。
组件与完整上游源码、补丁、构建配方发布在组件仓库同一发行版。
48 系列曾验证真实包安装、版本和面板列表；这些独立 GTK 应用与新 Native Shell 50 的完整组合仍待实机验证。

原生时钟固定51.0-1并在ICU79.1 sysroot重编，显式声明libicu依赖。标准仓库51.0
在本轮设备上仍链接libicui18n.so.78，不能直接作为可用组件交付。保存／安装时执行
四个原生应用的--version探针；启动直接执行保存的会话，不重复静态探针，链接失败或超时不得视为安装/会话就绪；安装探针
失败先撤销外观安装marker，不落入仅凭二进制存在判断成功的路径。
