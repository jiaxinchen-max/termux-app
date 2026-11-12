# 原生 Ubuntu GNOME 外观组件构建

此目录生成 GNOME Shell 48 使用的只读主题、扩展和壁纸。源码地址与 SHA256
固定在 `build-profile.py`；运行时依赖使用 Termux 原生软件包，不包含 Ubuntu ELF。

构建环境需要 Python 3.11+、Meson、Ninja、sassc、gettext、libxml2-utils、
GLib 资源与 schema 工具、make、dpkg-deb。示例：

```sh
python3 termux-ui/scripts/native-ubuntu/build-profile.py \
  --work /home/user/workspace/artifacts/native-ubuntu-gnome/profile-build \
  --output /home/user/workspace/artifacts/native-ubuntu-gnome/candidate-profile.bin
```

输出为 gzip/tar，使用 `.bin` 后缀，避免 Android 的资源打包器自动解压 `.gz`
并移除后缀。包内以 hardlink 复用重复图标；安装器只允许指向包内之前出现的普通文件，
并复制其内容为独立文件，以支持缺少 os.link 的 Android Python（安装后占用更多磁盘），
拒绝外部路径、符号链接和管理目录之外的文件。manifest 记录逐文件摘要、来源及修改。

版本包含 Yaru 25.04.2、Ubuntu Dock 109、锁定提交的 AppIndicator、DING 85
和 Ubuntu 25.04 壁纸。DING 适配 gjs 与 kill 的绝对路径，并由所属会话负责子进程清理，移除全局 /proc 扫描；源码头声明 GPLv3+，
发行包补充完整 GPLv3 文本。Yaru、Dock、AppIndicator 和壁纸许可随包保留。

更新组件必须同时更新 `desktop_native_ubuntu.py` 的 `BUNDLE_SHA256`、
`DesktopEnvironment.nativeUbuntuInstalled()` 的摘要及相应文件系统测试，
再运行 native profile、renderer、GNOME 进程夹具及 Java 桌面测试，并核对 APK
不包含组件包、helper与源码逐字节一致。源码/fixture验证不能替代 Android 桌面和 GPU 验证。

组件发布到 `jiaxinchen-max/termux-app-packages` 的固定版本
`native-ubuntu-gnome-48-r2`，APK仅保留安装helper。创建原生Ubuntu桌面时下载
`native-ubuntu-gnome-48-r2.bin`，固定SHA256校验后原子写入缓存并安装。
有效缓存复用，损坏缓存重下；网络或摘要失败不安装。组件测试指定
`NATIVE_UBUNTU_BUNDLE=/path/to/native-ubuntu-gnome-48-r2.bin`。

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
设备已验证真实包安装、版本和面板列表；图形窗口及完整桌面回归以运行台账为准。

原生时钟固定51.0-1并在ICU79.1 sysroot重编，显式声明libicu依赖。标准仓库51.0
在本轮设备上仍链接libicui18n.so.78，不能直接作为可用组件交付。安装与启动会执行
四个原生应用的--version探针，链接失败或超时不得视为安装/会话就绪；安装探针
失败先撤销外观安装marker，不落入仅凭二进制存在判断成功的路径。
