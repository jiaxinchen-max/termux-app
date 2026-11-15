# 修复与验证记录（设备验收待完成）

## 已实现

- 新增 Ubuntu 官方源 HTTPS 配置 helper，在 APT update 前执行，覆盖传统 list/Deb822，保持 suites、组件、Signed-By、第三方源与非 Ubuntu 源；首次修改保留 `.desktop-http.bak`，重复执行不覆盖备份。
- LaunchScriptInstaller 以及所有容器安装 helper 分发入口（含 Hyprland/USB 分支）已同步；测试夹具同步辅助文件清单。
- Proot Plasma 私有系统总线仅发布真实 PackageKit/PolicyKit 激活文件，不再自动拉起无硬件能力的系统服务；会话总线和原生桌面不变。这项 App 代码已打入本次 APK，尚未部署到设备。
- 既有状态解析已正确将“启动 PID 消失、残留 starting”解析为 FAILED，本轮不增加磁盘状态补写竞态。

## 当前模拟器已执行

- `settings_enable_monitor_phantom_procs` 原值 null，已改为 false 并读回 false；不声称 App 可自行更改系统设置，也不将读回配置当作稳定性通过。
- `ubuntu-kwin-plasma-1` 的官方 archive/security 源已改为 HTTPS，helper 返回 0，原文件备份成功；保留原发行版、组件和签名。
- 功能测试前已 force-stop App，检查无原桌面进程，从 `.UiLauncher` 默认 UI 冷启动；未改变用户默认模式或渲染配置。
- 冷启动等待 90 秒仍未完成，设备 /proc/loadavg 先后为 34、51；可用内存约 3.99 GB，不据此断言 OOM。
- 后台仅发起 APT 元数据刷新（PID 25145），未执行包安装或 dpkg；观察期尚停留 proot-distro 进程，无 APT 完成结果。
- 尝试 ADB reboot 返回超时，未取得新的 boot_id；不能声称重启成功。设备需从模拟器宿主恢复/重启，再检查两项配置保留与实际 Discover/持续运行。

## 通过的针对性验证

31 项相关测试通过：软件源文件系统 3、真实 D-Bus 激活范围 1、Ubuntu Plasma 安装 4、APT helper 合约 7、Hyprland 安装 6、Plasma 启动监督 10；另有独立 APT 失败脚本完整性合约、Java 实际状态解析探针、shell 语法检查与 git diff --check 通过。

执行入口：
- `python3 -B termux-ui/scripts/test-desktop-apt-sources.py`
- `python3 -B termux-ui/scripts/test-desktop-plasma-system-bus.py`
- `python3 termux-ui/scripts/test-desktop-ubuntu-plasma.py`
- `python3 -B docs/desktop-testing/test-gnome-usb-install.py ContainerAptContract`
- `python3 -B termux-ui/scripts/test-desktop-hyprland-install.py`
- `python3 docs/desktop-testing/test-container-plasma-startup.py`

## 未通过/不可执行的验证

- 完整 test-desktop-scripts.sh 在最初原生启动夹具超时；夹具仍生成 bin/start-plasma，当前生产启动器使用 bash + libexec/termux-desktop/start-plasma，未进入本次修改的安装/系统总线路径。相关 APT 段独立执行通过，不宣称整套通过。
- Ubuntu GNOME 完整套件 15 项中有 2 failures/1 error；GNOME USB 完整套件 39 项有 33 failures。用隔离文件树将本轮生产脚本替换为 git HEAD 提交基线，再运行同样套件，结果一致。未扩展本轮范围去重写已有旧版 renderer/API 资产夹具。
- ubuntu-gnome：当前与提交基线的失败测试名称及顺序一致=True，失败项数=3。
- gnome-usb：当前与提交基线的失败测试名称及顺序一致=True，失败项数=33。
- 缺少 openspec CLI、Harness bootstrap 与 strict-run 脚本，严格门禁 Unavailable。
- 设备真实 Discover 刷新、实际可见桌面以及超过原退出窗口的持续运行，均未验收。

## 回滚与交付边界

源回滚：将 ubuntu.sources.desktop-http.bak 恢复到 ubuntu.sources。系统设置回滚：`adb -s 120.24.191.156:8057 shell settings delete global settings_enable_monitor_phantom_procs`（原值 null）。新 helper 与会话策略可逐文件回退，不删除容器或用户配置。用户随后明确授权“推送代码编译出apk给我”，允许本次提交、推送及 APK 构建交付；构建和交付证据另记于构建日志。产品文档与归档保持 Pending，等待设备验收和用户最终接受。

原始证据目录：`/home/user/workspace/artifacts/proot-network-check/`。

## APK 构建交付

- 修复代码提交 `22f88944` 已推送至 `origin/master-x11-submodule-tmp`，远端查询确认一致。
- Linux 上使用项目 Gradle 8.7、JDK 17、SDK 34、NDK 25.1.8937393 与 CMake 3.22.1，补齐宿主 Bison/Flex；`:app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false` 构建成功。
- APK 保持文件名 `termux-app_apt-android-7-debug_arm64-v8a.apk`，约 45.36 MB（43.26 MiB）；v1/v2 签名验证通过，无 testOnly 标记，仅包含 arm64-v8a 原生库。
- 四个修复脚本与源码逐字节一致；无主题压缩包和 termux-api.deb，API 配套包可复现校验通过。
- 构建日志与最终下载文件、SHA-256、签名检查证据位于 workspace/artifacts，设备验收仍保持 Pending。
