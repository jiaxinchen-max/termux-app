# 设计

仅修正 desktop_hyprland_theme.py 和 desktop_hyprland_shell.py 的 CLI 工作目录白名单。宿主必须位于 HOME/.termux，只允许固定入口 hyprland-native-hyprland 或原有 hyprland-desktop-<ID>；容器仍必须位于 HOME/.local/share/termux-hyprland，名称仍为 desktop-<ID>。两处使用完整名称匹配，不将任意 native-* 或其他父目录纳入白名单。

通过真实 Python CLI 测试：主题 serve 使用替身 EWW 前台进程，验证固定目录能到达面板服务且正常退出；桌面操作使用 battery-status 验证入口可达。覆盖旧记录、容器与非法目录负向用例，并运行现有 Hyprland 测试。

不改安装流程、合成器、主题资产或用户配置。撤销本次两处校验变更即可回滚，无数据迁移。风险是目录白名单过宽或容器路径误放行，使用负向测试约束。无已注册保护路径；源码、已有未提交修改保留。APK 完整重新打包，保持 termux-app_apt-android-7-debug_arm64-v8a.apk 与原证书，核验大小及源码资源。

仓库缺失 Harness 引导文件、严格验证脚本、OpenSpec CLI 及产品基线，如实记录 Unavailable，不把本地检查称为严格门禁通过。设备候选部署待明确授权，本地交付不依赖覆盖安装。

## 升级兼容补充

DesktopTasks.start 在现有归档、安装、停止中保护之后、写 starting 状态和提交启动之前，通过 LaunchScriptInstaller.installDesktopScripts 原子刷新应用受管脚本。此动作只复制本地 APK 资源，不调用 deploy(distro)、prepareLaunchRuntime、writeSpec 或包管理器，保留已准备好的规格与用户设置。复制失败抛 desktop_prepare_failed，不提交旧脚本，不把状态误标 starting。现有安装和归档冲突仍先拒绝，避免覆盖正在安装使用的脚本。

Robolectric 覆盖实际旧脚本被替换后提交启动、规格及用户配置不变、无安装/准备调用、复制失败不提交、安装及归档冲突不覆盖。代码审查和测试之后重新完整打包；明确设备命令授权前不覆盖安装 APK。回滚只需撤销 start() 新增复制，无数据迁移。

## D-Bus 激活环境

在 Wayland 输出、IPC、socket 就绪后，启动任何宿主桌面服务之前，用已安装 dbus 工具的 UpdateActivationEnvironment 同步当前会话环境。使用宿主客户端环境而非合成器私有库环境，明确清空 D-Bus 继承的 LD_LIBRARY_PATH、LD_PRELOAD，并传入最终 WAYLAND_DISPLAY、XDG_RUNTIME_DIR、桌面 XDG 目录、会话总线及 GDK_BACKEND。同步失败时启动明确失败，不发布 ready。保留容器自身 D-Bus 管理。

回归使用真实私有 dbus-daemon 与 D-Bus 激活的测试服务，观测实际子进程的显示、目录及库环境；另验证更新失败不进入 ready。设备仅执行当前会话中的启动诊断，不覆盖脚本或部署 APK。回滚撤销环境同步，无用户数据变更。
