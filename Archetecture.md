# 项目架构回顾

本文基于当前代码树梳理架构，文件名按现有要求保留为 `Achetecture.md`。

## 1. 项目定位

当前工程不是单纯的上游 Termux App，而是把 Termux 终端、Termux:X11、Wine/MoBox 启动脚本、悬浮球菜单和一个实验性的渲染桥接层合并到同一个 Android 工程里。

核心能力分三层：

1. Android 壳层：Activity、Service、权限、通知、文件分享、设置页。
2. Termux 运行时：bootstrap、`$PREFIX`、shell 环境、PTY 终端会话、外部 RUN_COMMAND 接口。
3. 图形/桌面层：Termux:X11 native X server、Lorie Surface 渲染、触控/键盘输入桥接、Wine/MoBox 启动工具。

## 2. Gradle 模块拓扑

`settings.gradle` 声明了这些模块：

| 模块 | 类型 | 职责 |
| --- | --- | --- |
| `:app` | Android application | 主包 `com.termux`，集成终端 UI、服务、bootstrap、文件接收、备份恢复、X11 显示容器、MoBox 脚本入口。 |
| `:terminal-emulator` | Android library + ndk-build | 终端核心：PTY 进程、VT/xterm escape 解析、屏幕缓冲区、颜色、键盘编码。 |
| `:terminal-view` | Android library | `TerminalView` 自定义 View，把 `TerminalEmulator` 渲染到 Canvas，并处理触摸、缩放、选择、IME。 |
| `:termux-shared` | Android library + ndk-build | 公共能力：常量、文件/权限/日志/通知、properties/preferences、shell 环境、插件结果、local socket、`TermuxSession`/`AppShell`。 |
| `:termux-x11` | Android library + CMake | Termux:X11：Java UI、Lorie Surface、AIDL、native Xorg/Xlorie、输入/剪贴板/渲染桥、Wine/controller 相关 UI。 |
| `:termux-wayland` | Android library + CMake | `termux-render` native 渲染桥和 sample，可通过 unix socket 交换共享 buffer。 |
| `:shell-loader` | Android application | CLI loader APK，从已安装目标包加载 `com.termux.x11.CmdEntryPoint`。 |
| `:shell-loader:stub` | Android library | hidden API 编译桩，用于 `ActivityThread`、`IPackageManager`、`IActivityManager` 等。 |
| `:float-ball` | Android library | 悬浮球和径向菜单，支持应用内或 overlay window。 |

主要依赖方向：

![Gradle 模块依赖拓扑](docs/Archetecture/gradle-module-dependencies.svg)

`app/build.gradle` 里 `:termux-x11` 是直接作为 library 集成到主 APK 的，`app/src/main/AndroidManifest.xml` 也显式注册了 X11 的 Activity、AccessibilityService 和 preference receiver。

## 3. 主进程启动链路

入口是 `TermuxApplication`：

1. 设置 crash handler 和日志。
2. 根据 `BuildConfig.TERMUX_PACKAGE_VARIANT` 初始化 bootstrap variant。
3. 初始化 `TermuxAppSharedProperties`。
4. 初始化全局 `TermuxShellManager`。
5. 初始化主题。
6. 检查 `/data/data/com.termux/files` 和 apps 目录。
7. 启动 `TermuxAmSocketServer`。
8. 初始化并写出 shell 环境文件。

主界面是 `TermuxActivity`，但它继承自 `com.termux.x11.MainActivity`，这是当前架构里最强的耦合点：

```java
public class TermuxActivity extends com.termux.x11.MainActivity implements ServiceConnection
```

实际启动过程：

1. `TermuxActivity.onCreate()` 先加载 Termux properties 和主题。
2. 调用 `super.onCreate()`，由 X11 `MainActivity` inflate `termux-x11` 的 `main_activity.xml`，创建 `LorieView`、输入控制、WinHandler。
3. `TermuxActivity` 再 `setContentView(R.layout.activity_termux_main)`。
4. 从 X11 layout 中取出 `lorieContentView`，挂到 `app/src/main/res/layout/display_window.xml` 对应的中间显示面板。
5. 初始化终端 `TerminalView`、terminal toolbar、session list、工具箱、备份恢复、浮球。
6. `startService()` + `bindService()` 到 `TermuxService`。
7. service 连接后，如果没有 session，则先跑 `TermuxInstaller.setupBootstrapIfNeeded()`，再创建默认 session。

主 UI 是三栏滑动容器：

![主 UI 三栏滑动容器](docs/Archetecture/main-ui-sliding-window.svg)

`DisplaySlidingWindow` 负责在终端、X11 显示、X11 设置之间滑动，并决定触摸事件是交给 terminal 还是交给 X11 input handler。

## 4. Shell 与终端会话模型

核心状态集中在 `TermuxShellManager`：

![TermuxShellManager 状态模型](docs/Archetecture/shell-manager-state.svg)

所有命令统一用 `ExecutionCommand` 描述。关键字段包括：

- `runner`: `terminal-session` 或 `app-shell`
- `executable` / `arguments` / `stdin` / `workingDirectory`
- `shellName` / `shellCreateMode`
- `resultConfig` / `resultData`
- `isPluginExecutionCommand`

执行路径分两类：

![Shell 执行路径](docs/Archetecture/shell-execution-paths.svg)

`TermuxService` 是进程保活和会话管理中心：

- 前台服务通知防止后台被杀。
- 管理 wake lock / wifi lock。
- 接收 `ACTION_SERVICE_EXECUTE`。
- 维护 `TermuxSession` 和 `AppShell` 生命周期。
- 在 activity 解绑时把 session client 降级为 service client，避免持有 Activity 引用。

`RunCommandService` 是外部调用入口。它校验 action、runner、可执行文件、工作目录、`allow-external-apps` 策略，然后转发为 `TermuxService.ACTION_SERVICE_EXECUTE`。

## 5. 终端渲染链路

终端核心分层清晰：

![终端渲染分层](docs/Archetecture/terminal-rendering-layers.svg)

数据流：

![终端数据流](docs/Archetecture/terminal-data-flow.svg)

`terminal-emulator/src/main/jni/termux.c` 负责：

- 打开 `/dev/ptmx`
- `grantpt` / `unlockpt`
- 设置 UTF-8 和窗口大小
- `fork`
- child 侧 `setsid`、dup slave pty 到 stdin/stdout/stderr
- `clearenv` 后写入 Termux 环境
- `execvp`

这部分是 Termux 终端体验的底座，UI 层不直接碰进程 fd，只通过 `TerminalSession` 和 client callback 交互。

## 6. Bootstrap 与文件系统

bootstrap 流程由 `TermuxInstaller` 负责：

1. 检查 primary user、files dir、`$PREFIX`。
2. `app/build.gradle` 的 `downloadBootstraps` 任务下载各 ABI bootstrap zip。
3. `app/src/main/cpp/termux-bootstrap-zip.S` 把 zip 编进 `libtermux-bootstrap.so`。
4. 运行时 `TermuxInstaller.getZip()` 从 JNI 取出 zip bytes。
5. 解压到 staging prefix。
6. 处理 `SYMLINKS.txt`。
7. 原子 rename 到正式 `$PREFIX`。
8. 重新写出 `termux.env`。

固定路径大量依赖 `TermuxConstants`，默认包名为 `com.termux`，因此 package name、bootstrap、脚本、native X11/Wayland 代码之间有硬绑定。

## 7. X11 架构

`termux-x11` 分为 Java 控制面和 native X server 两部分。

Java 侧核心：

- `MainActivity`: X11 UI、广播接收、输入控制、偏好设置、WinHandler 生命周期。
- `LorieView`: `SurfaceView`，承载 X11 输出，暴露 native input/render/clipboard 方法。
- `CmdEntryPoint`: 命令行入口，创建 Context，广播 binder 给 app 侧，启动 native X server。
- `LoriePreferences`: preferences 协议和 receiver。
- `controller/*`: 输入控制、触控板、外接手柄、Wine/container/task manager 等。

Native 侧核心：

- `lorie/cmdentrypoint.c`: `CmdEntryPoint.start()`，转换 argv，设置环境，启动 `dix_main()`。
- `lorie/InitOutput.c`: Xorg DDX 输出层、root pixmap、DRI3/Present/EXA、共享 server state。
- `lorie/InitInput.c`: X11 mouse/touch/keyboard/stylus 设备。
- `lorie/activity.c`: Java UI 进程侧 native 桥，接收 X server 事件、fd 和 buffer。
- `lorie/renderer.c`: EGL/GLES 渲染到 Android Surface。
- `lorie/buffer.c`: `AHardwareBuffer` / fd-backed buffer 的封装和 unix socket fd 传递。

X11 启动链路：

![X11 启动链路](docs/Archetecture/x11-startup-flow.svg)

X11 输入链路：

![X11 输入链路](docs/Archetecture/x11-input-flow.svg)

X11 输出链路：

![X11 输出链路](docs/Archetecture/x11-output-flow.svg)

屏幕尺寸变化由 `LorieView.SurfaceHolder.Callback` 触发，最后调用 `LorieView.sendWindowChange()` 通知 X server。

## 8. Wayland / termux-render

`termux-wayland` 当前更像独立 native 渲染客户端库，不是主 Activity UI：

- 构建产物是 `libtermux-render.so` 和 `termux-render-sample`。
- `render.c` 连接 `/data/data/com.termux/files/home/.wayland/unix_socket`。
- 协议复用了 `lorieEvent`、`LorieBuffer`、`lorie_shared_server_state`。
- sample `main.c` 加载 assets 下的 PNG 帧，写入共享 buffer，驱动渲染。

这层和 `termux-x11` 的 buffer/event 协议强相关，但代码位于独立模块。后续要避免协议结构在多个模块漂移。

## 9. 悬浮球与桌面辅助

`float-ball` 是独立 overlay 库，`app` 通过 `FloatBallMenuClient` 绑定业务动作：

- 切换终端面板。
- 停止桌面，即调用 `stopserver`。
- 打开输入控制配置。
- 锁定/释放滑动布局。
- 显示软件键盘。
- 打开 task manager。
- 打开 X11 设置面板。

如果开启全局悬浮球，会走 `SYSTEM_ALERT_WINDOW` 权限和 application context 的 window。

## 10. 资产与脚本

运行时依赖的脚本和包分布在多个位置：

- `app/src/main/assets/install`
- `app/src/main/assets/setMoBoxEnv`
- `app/src/main/assets/recover`
- `app/src/main/assets/termux-x11-nightly-1.03.10-0-all.deb`
- `app/src/main/assets/xkeyboard-config_2.45_all.deb`
- `termux-x11/src/main/assets/start-tfm`
- `termux-x11/src/main/assets/wine.tar.gz`
- `termux-x11/src/main/assets/winhandler.exe`
- `termux-x11/src/main/assets/wfm.exe`
- `termux-x11/src/main/assets/inputcontrols/*`

`CommandUtils` 会把 `$PREFIX/usr/bin/<cmd>` 或 `$HOME/<cmd>` 包装为 `ACTION_SERVICE_EXECUTE`，通过 `TermuxService` 后台执行。

## 11. 外部接口与安全边界

主要外部入口：

- `com.termux.permission.RUN_COMMAND`: 保护 `RunCommandService`。
- `RunCommandService.ACTION_RUN_COMMAND`: 第三方/插件执行命令。
- `TermuxOpenReceiver.ContentProvider`: 对外分享文件，限制路径在 Termux files dir 或 external storage。
- `FileReceiverActivity`: 接收 `SEND` / `VIEW` content/file uri，保存到 `~/downloads` 或调用用户脚本。
- `TermuxAmSocketServer`: `$PREFIX/bin/termux-am` 的本地 socket server。
- `LoriePreferences$Receiver`: X11 preference 改动广播。
- `KeyInterceptor`: X11 辅助功能服务。

安全策略主要靠：

- manifest permission。
- `allow-external-apps` property。
- ContentProvider 路径限制。
- shell-loader 的目标 APK 签名校验。
- local socket peer credential 校验。

## 12. 构建系统

当前关键版本：

- AGP: `8.6.1`
- compileSdk: `34`
- NDK: `25.1.8937393`
- app minSdk: `21`
- app targetSdk: `28`
- termux-x11 minSdk/targetSdk: `26/34`

Native 构建：

- `terminal-emulator`: ndk-build，输出 `libtermux.so`。
- `app`: ndk-build，输出 `libtermux-bootstrap.so`。
- `termux-shared`: ndk-build，输出 `liblocal-socket.so`。
- `termux-x11`: CMake，拉入 Xorg、pixman、libx11、libxkbfile、libxtrans、libepoxy 等 submodule，并应用 patches。
- `termux-wayland`: CMake，输出 `libtermux-render.so` 和 sample executable。

`termux-x11/build.gradle` 会从 `preferences.xml` 生成 `Prefs.java`，所以 preference schema 是编译期输入。

## 13. 当前架构风险

1. `TermuxActivity extends MainActivity` 导致 Termux 主 Activity 和 X11 Activity 生命周期合并；两个模块互相引用资源 id、View 和 callback，后续改 UI 很容易引入生命周期问题。
2. app targetSdk 仍是 28，而 X11/Wayland 模块 target/compile 到 34；权限、前台服务、存储策略会在升级 targetSdk 时集中暴露。
3. native/脚本里有大量 `/data/data/com.termux/...` 硬编码，包名变体和多包并存支持成本高。
4. X11、Wayland/render 共用事件和 buffer 概念，但协议定义分散，缺单一 source of truth。
5. app assets 内置 deb/pkg、Wine 相关二进制和脚本，版本升级链路不透明，容易出现 Java 逻辑、脚本、native 包版本不一致。
6. 当前 git status 显示多个 `termux-x11/src/main/cpp/*` submodule 为 modified，需要在构建/发布前确认这些是预期 patch 状态还是本地脏状态。

## 14. 后续演进建议

优先级从高到低：

1. 把 `TermuxActivity` 和 `termux-x11.MainActivity` 从继承改成组合：X11 作为可嵌入 controller/view，降低主 Activity 生命周期复杂度。
2. 明确 Wayland/render 与 X11 的模块边界。
3. 抽出 native render/event protocol 头文件和 Java 绑定的单一来源，X11 与 Wayland 共用。
4. 给 assets 脚本和二进制包建立版本清单，至少记录来源、版本、部署路径、触发入口。
5. 收敛 `com.termux` 硬编码路径，统一通过 `TermuxConstants` 或运行时 env 注入。
6. 在升级 targetSdk 前先列权限矩阵，重点验证 foreground service、overlay、external storage、post notification、accessibility。
