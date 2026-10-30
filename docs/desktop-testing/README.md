# 桌面（XFCE4 / KWin Plasma）进度、测试工具与 TODO

## 1. 组件与版本

| 组件 | 仓库 / 分支 | 当前版本 |
|---|---|---|
| App | jiaxinchen-max/termux-app `master-x11-submodule-tmp` | 桌面修复与 r27/r7 固定安装清单，基于 `280cbe79` |
| X server（Xlorie） | jiaxinchen-max/xserver `termuxdc` | release `termux-xlorie-21.1.16-r7`，xorg-server 21.1.16-7（需 hold） |
| KWin | jiaxinchen-max/kwin `Plasma/6.6` | release `termux-hwaccel-6.6-r27`（kwin 6.6.0-27） |
| 容器 Mesa | lfdevs/mesa-for-android-container（`mesa-*` tag） | 完整 Mesa 覆盖层，提供 Turnip ICD |

`DesktopLaunchSpec.RELEASE_TAG` 与 `desktop_install.sh` 里的 deb 文件名/SHA256 必须同步更新。

## 2. 已完成

- 桌面会话页改为游戏式多级抽屉（操作/性能/设置/键盘/日志、退出），触控模式与方向为下拉；每桌面覆盖，未设置跟随全局（`DesktopSessionOverrides`）。
- 旋转/开关菜单后黑屏：焦点 nudge（`nudgeDisplay` / `nudgePointer`）。
- KWin Android 后端（`src/backends/android/android_backend.cpp`）：鼠标键位修正（2 中键、3 右键）、滚轮（detail 4，v120）、触摸（XI2 18/19/20）。
- 安装脚本：失败不再误报成功、`curl --retry-all-errors`、XFCE 容器走 apt helper、Mesa 完整覆盖、`.X11-unix` 软链处理、切换桌面前停止其他桌面。
- Discover fix4 继承显式桌面渲染模式，并在 PackageKit 成功刷新后更新同一 AppStream 池；四个包已部署两设备，模拟器 App v6/fix4 的 forky / Software 启动、目录/搜索、安装、实际 2+3=5、卸载及 App Stop 至 Ready 通过；启动自动刷新 503，手工 Refresh 未记通过。小米同一 v6 的 Zink / forky / fix4 主路径及手工 Refresh 通过，同 Pool 重载 2471、fetching 0；两设备已 Stop 至 Ready。见 [本轮记录](validation-20261030.md)。
- 已在真机验证：原生 XFCE4、原生 Plasma（直接触控滚动）、容器 Plasma（双指右键/滚动/直接触控）、容器 XFCE4（trixie）安装与显示。

## 3. 测试工具

### 3.1 可重复 App UI 验证

使用 [手势验证工具](gesture-validation.md)：指定设备、ADB server、InputManager API、屏幕方向及坐标基准，支持 UI 点击、多指、截图和显式基线差异报告。Java 工具只在指定编译机构建，不在本机编译。ADB 动作成功不代表业务验收通过。

### 3.2 触控模式
1 触控板；2 模拟触屏（双指点按右键、双指滑动滚轮）；3 直接触控。偏好 `games_x11_session_preferences`，每桌面覆盖在 `desktop_session_overrides`（键 `desktop-<id>.touchMode`）。

### 3.3 KWin 输入调试
`~/.termux/kwin-input-debug` 存在时，KWin Android 后端把输入事件 trace 到 stderr。注意 Android 后端直接读输入 fd，libinput 假桥接（`termux-input-bridge.c`）不在此路径上。

### 3.4 构建与分发
- KWin/X server 在 aarch64 构建机的 termux-packages 树交叉构建（build.sh 替换 SHA256/REVISION，本地 http.server 提供源码包）。
- 发布到 GitHub release，app 内固定 tag + SHA256。
- 手机访问 GitHub 需代理；大文件 `adb push` 不稳定，需分块并校验大小/哈希。
- 不要把任何 PAT 写进仓库或脚本，只在推送命令里临时使用，并及时吊销。

## 4. 运行模式契约

- 必须显式选择 System EGL、Zink 或 Software；不提供 Auto，旧 Auto 配置须在 App 设置中重新选择。
- 请求模式与实际 provider/driver 一一对应。缺少驱动、初始化失败或实际 renderer 不匹配时退出并显示错误，禁止换其他模式继续。
- 显示服务必须发布 socket 且保持存活，才启动桌面客户端；失败保留错误码及原始日志。
- 原生组件已发布 r27/r7，App 两个安装入口同步固定新 tag、包版本与 SHA256。完整 Qt 修复由明确的依赖包提供；Discover fix4 另附 forky 容器专用 deb，当前新建容器不会自动安装这些附件。见 [发布记录](release-20261030.md)、[Xserver](xserver-fixes-20261030.md)、[KWin / Qt](patches/kwin-qt612-native-fixes.md)。正式验收必须从 App 设置参数并启动、操作桌面；诊断程序和单元测试单独记录。

## 5. TODO

### P0
- [x] 原生 Plasma 右键让 plasmashell 崩溃：`layershellqt: Cannot attach popup of unknown type` → `xdg_wm_base error 3: no xdg_popup parent surface`（Qt 6.12.0 / layer-shell-qt 6.7.5，容器 Plasma 不受影响）。已修复 Android libc++ 跨插件 RTTI 比较；新完整 KWin/Qt 包在模拟器正式 UI 连续右键通过；小米重复右键通过，Qt 客户端现通过 Android AHB 使用实际 Adreno 660，About 页面已核验。
- [x] 容器 XFCE4 右键菜单渲染花屏（条纹）：修复 GLES readback 与 Present 唤醒/发布；System EGL 保持合成，模拟器 App UI 验证通过；小米另发现 A8 picture 上传错误，v8 修复后正式菜单/地址栏/任务按钮均通过。
- [x] 容器 XFCE4 双指滚动/直接触控，以及 forky 容器 Plasma 右键、两种滚动、方向抽屉下拉均经模拟器正式 App UI 通过；小米相应手势、滚动和方向正式UI也通过。

### P1
- 容器支持范围固定：XFCE4 使用 Debian trixie；Plasma 使用 Debian forky。forky XFCE4 和 trixie Plasma 已确认不兼容，不作为待修复或待验组合。
- [x] 安装失败时保留 apt 原命令、退出码及 `E:` / `Depends` / broken 诊断；契约以依赖损坏和退出 100 验证，不再统一误报 `x11-repo`。
- [x] 滚轮单次滚动量偏小：viewport 映射后按 120/15 转 v120；模拟器及小米容器 XFCE 双指滚动通过。
- [x] 容器 Discover 正式主路径：两设备同一 v6/fix4，模拟器 Software、小米 Zink / forky 均通过稳定 bus 启动、Home 目录、KCalc 搜索 1 条、安装/Launch 真实窗口、2+3=5、卸载 Install、App Stop 至 Ready。模拟器自动刷新 503，手工 Refresh 未记通过；小米手工 Refresh 成功后同 Pool 重载 2471、fetching 0，仍列原有 3 条更新，未点 Update All。两设备真实 fwupd/LVFS 错误均保留、加载结束；小米无 HTTP503，迟到错误详情均 LVFS。
- [ ] 小米旧 Discover 实例 PackageKit 无应用结果：旧实例初始 raw 输入缺失，不能精确重放；当前固定输入 fix2/fix3 ABBA 均 2471 组件。独立真实 Backend 已复现初始空池在成功 RefreshCache 后仍过期，fix4 机制、最终 ELF 回归及两设备新版本正式 UI 已通过；旧 fix2 原实例 raw 缺失的精确根因边界仍保留，不能声称完整重放，不以重启恢复关闭历史项。见 [历史对照](discover-fix2-fix3-controlled-baseline.md)、[fix4 机制](discover-packagekit-refresh-20261030.md)。
- [x] session bus ENOENT 的路径机制与修复：原 PRoot 真实 binding 指向已丢失的 `$PREFIX/tmp/proot-*` 短路径，daemon 仍活；最小修改将 session socket 固定到现有 runtime bind 并单次限时 Ping，独立机制/脚本契约通过。App v6 远端构建/契约及两设备正式 UI 已通过，实际地址 `/run/wl/container-dbus/session.sock`；相对 v5 仅 session 脚本变化，其余 63 assets / 5 native / 17 DEX 一致。小米同 v6 的 Zink / forky 正式启动及 Discover 通过，Stop 后最新 inspect 两卡片 Ready；stayon 恢复 0，正式 env/maps 无 statx preload 泄漏。生产删除时刻未捕获。见 [取证记录](dbus-session-lifecycle-20261030.md)。
- [x] 两设备 v6/fix4 最终核验：已安装 APK、Xlorie、libkwin、start-plasma、Discover SHA 逐项一致；四包 installed、dpkg audit 空、KCalc absent、无剩余桌面服务，stayon 为 0。原件为两设备目录的 `final-v6-fix4-device-check.txt`。
- [ ] 并发显示 owner 冲突：外部 GNOME 烟测的 XCB 缓冲区覆盖仍活着的 KWin native owner，日志/进程时间线已本地保全；烟测已暂停，未修改冲突门禁。干净会话 UI 通过不代表并发冲突已修复。

### P2
- [ ] 清理 kwin 仓库旧 tag r19–r23。
- [x] 将 MultiTouch 手势验证做成可重复脚本（含安装、坐标换算、截图比对）；远端编译、5 项脚本测试及模拟器原生 Plasma 正式右键计划通过，业务截图已人工检查。
