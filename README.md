# Termux App

Android Termux 终端与桌面 UI，集成 Wine EXE、Native / PRoot Linux 桌面、文件浏览、X11 显示及内置 USB 授权模块。

当前开发分支：`master-x11-submodule-tmp`。项目结构、功能完成情况、验收边界和待办见 [工程状态](docs/project-status.md)；桌面专项记录见 [桌面测试索引](docs/desktop-testing/README.md)。

## 工程模块

| 模块 | 职责 |
| --- | --- |
| `app` | APK、Termux 会话、终端/UI 模式路由、Launcher、宿主能力桥接 |
| `termux-ui` | Wine / Desktop / Terminal / Files / Settings 页面、组件与运行任务 |
| `termux-x11` | 内置 Xserver、X11SessionView、输入、显示与偏好 |
| `termux-render` | GLES / AHardwareBuffer 跨进程显示桥接 |
| `termux-usb` | 主 APK 内的 USB 枚举、Android 授权与 FD 传递 |
| `termux-shared` | Termux 公共能力 |
| `terminal-emulator` / `terminal-view` | 终端模拟与 Android 显示/输入 |
| `shell-loader` / `shell-loader:stub` | Shell 装载及接口 |
| `float-ball` | 悬浮入口 |

## 构建与检查

APK 在本机构建，deb 在指定远程编译机构建。需要 JDK 17、Android SDK/NDK，以及完整的原生子模块；macOS 原生构建还需要 bison / flex。SDK 路径通过未跟踪的 `local.properties` 配置。

```sh
git clone -b master-x11-submodule-tmp https://github.com/jiaxinchen-max/termux-app.git
cd termux-app
git submodule update --init --recursive
./gradlew syncDebugLibJars
./gradlew :app:assembleDebug
```

```sh
./gradlew :app:testDebugUnitTest :termux-ui:testDebugUnitTest :termux-x11:testDebugUnitTest
bash termux-ui/scripts/check-module-boundary.sh
python3 termux-ui/scripts/test-rootfs-staging.py
python3 termux-ui/scripts/test-rootfs-prefix-recovery.py
```

构建不会代替正式 App 界面验收；各桌面、驱动、设备的覆盖范围以测试记录为准。C/C++ 子模块在构建时应用仓库内补丁，工作树出现对应 dirty 状态不需要更新子模块指针。

组件下载索引位于 `termux-ui/src/main/assets/termux-app-packages/index-v1.json`。自有通用组件由 [termux-app-packages](https://github.com/jiaxinchen-max/termux-app-packages) 分发；KWin、Xserver 和第三方组件保持各自来源。
