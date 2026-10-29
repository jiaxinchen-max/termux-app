# 桌面（XFCE4 / KWin Plasma）进度、测试工具与 TODO

## 1. 组件与版本

| 组件 | 仓库 / 分支 | 当前版本 |
|---|---|---|
| App | jiaxinchen-max/termux-app `master-x11-submodule-tmp` | 2d940f4 |
| X server（Xlorie） | jiaxinchen-max/xserver | release `termux-xlorie-21.1.16-r6`，xorg-server 21.1.16-6（需 hold） |
| KWin | jiaxinchen-max/kwin `Plasma/6.6` | release `termux-hwaccel-6.6-r26`（kwin 6.6.0-26） |
| 容器 Mesa | lfdevs/mesa-for-android-container（`mesa-*` tag） | 完整 Mesa 覆盖层，提供 Turnip ICD |

`DesktopLaunchSpec.RELEASE_TAG` 与 `desktop_install.sh` 里的 deb 文件名/SHA256 必须同步更新。

## 2. 已完成

- 桌面会话页改为游戏式多级抽屉（操作/性能/设置/键盘/日志、退出），触控模式与方向为下拉；每桌面覆盖，未设置跟随全局（`DesktopSessionOverrides`）。
- 旋转/开关菜单后黑屏：焦点 nudge（`nudgeDisplay` / `nudgePointer`）。
- KWin Android 后端（`src/backends/android/android_backend.cpp`）：鼠标键位修正（2 中键、3 右键）、滚轮（detail 4，v120）、触摸（XI2 18/19/20）。
- 安装脚本：失败不再误报成功、`curl --retry-all-errors`、XFCE 容器走 apt helper、Mesa 完整覆盖、`.X11-unix` 软链处理、切换桌面前停止其他桌面。
- Discover 在容器 Plasma 中可打开（`QT_QUICK_BACKEND=software`）。
- 已在真机验证：原生 XFCE4、原生 Plasma（直接触控滚动）、容器 Plasma（双指右键/滚动/直接触控）、容器 XFCE4（trixie）安装与显示。

## 3. 测试工具

### 3.1 app_process 多指注入（无需 root）
`MultiTouch.java` 通过 InputManager 注入多指 MotionEvent（和 `input` 命令、scrcpy 同原理）。`sendevent` 会被 SELinux 拦截，不可用。

```sh
# 在构建机编译（需要 android.jar 与 d8），产出 mt.jar 后推到手机
javac -cp $ANDROID_JAR MultiTouch.java && d8 --output . MultiTouch*.class && jar cf mt.jar classes.dex
adb push mt.jar /data/local/tmp/

# 以 shell 用户运行
CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch tap2   x1 y1 x2 y2 [holdMs]      # 双指点按 = 右键
CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch tap3   x1 y1 x2 y2 x3 y3 [holdMs]
CLASSPATH=/data/local/tmp/mt.jar app_process / MultiTouch swipe2 x1 y1 x2 y2 dx dy [ms]    # 双指滑动 = 滚轮
```
注意：横竖屏坐标不同；应先在抽屉里把该桌面触控模式设为「模拟触屏」(2) 或「直接触控」(3)；验证前用 `svc power stayon true` 防息屏。

### 3.2 触控模式
1 触控板；2 模拟触屏（双指点按右键、双指滑动滚轮）；3 直接触控。偏好 `games_x11_session_preferences`，每桌面覆盖在 `desktop_session_overrides`（键 `desktop-<id>.touchMode`）。

### 3.3 KWin 输入调试
`~/.termux/kwin-input-debug` 存在时，KWin Android 后端把输入事件 trace 到 stderr。注意 Android 后端直接读输入 fd，libinput 假桥接（`termux-input-bridge.c`）不在此路径上。

### 3.4 构建与分发
- KWin/X server 在 aarch64 构建机的 termux-packages 树交叉构建（build.sh 替换 SHA256/REVISION，本地 http.server 提供源码包）。
- 发布到 GitHub release，app 内固定 tag + SHA256。
- 手机访问 GitHub 需代理；大文件 `adb push` 不稳定，需分块并校验大小/哈希。
- 不要把任何 PAT 写进仓库或脚本，只在推送命令里临时使用，并及时吊销。

## 4. TODO

### P0
- [ ] 原生 Plasma 右键让 plasmashell 崩溃：`layershellqt: Cannot attach popup of unknown type` → `xdg_wm_base error 3: no xdg_popup parent surface`（Qt 6.12.0 / layer-shell-qt 6.7.5，容器 Plasma 不受影响）。排查 layer-shell-qt 与 Qt 版本匹配。
- [ ] 容器 XFCE4 右键菜单渲染花屏（条纹）：检查 Xlorie 的 damage/重绘（合成与 GLX）。
- [ ] 补测容器 XFCE4 的双指滚动与直接触控；安装并完整验证容器 KWin Plasma（forky）：右键、滚动、直接触控、抽屉下拉。

### P1
- [ ] forky 容器 XFCE4 SIGABRT（GLX numVisuals=0），目前 XFCE 容器默认 trixie。
- [ ] trixie 容器 Plasma 与 KWin 6.6 不兼容（`kde_output_device_mode_v2 has no event 4`）。
- [ ] 安装失败时 apt 依赖损坏只提示 `x11-repo`，应给出更明确的原因。
- [ ] 滚轮单次滚动量偏小（像素 delta 被当作 v120）。
- [ ] 容器 Discover：PackageKit 离线。

### P2
- [ ] 清理 kwin 仓库旧 tag r19–r23。
- [ ] 将 MultiTouch 手势验证做成可重复脚本（含安装、坐标换算、截图比对）。
