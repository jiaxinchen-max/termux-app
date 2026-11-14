# 容器 Plasma 启动优化验证

变更：`optimize-container-plasma-startup`。用户已确认四份规划并明确批准实施。源码基准：`497bd9dab0a4545588afa439b1aae8d5255df69b`。本轮只做本地实现、构建和只读查询；未安装 APK、替换设备脚本、初始化设备容器或重启桌面。

## 实施结果

- `desktop_initialize.py` 在容器 Plasma 初始化中执行同 root 登录用户、相同 XDG 搜索目录的 KDE 全量缓存构建，成功后才继续生成渲染初始化凭据及安装完成标记。正式会话脚本不再全量重建缓存。
- `desktop_container_session.sh` 保留 PolicyKit → PackageKit、ActivityManager → kded6 的依赖顺序，移除 KDE 服务启动的固定 `sleep 1`；按唯一 D-Bus owner 和实际对象的 Peer.Ping 等待，每个服务所有请求合计受 10 秒期限约束。已退出或僵尸服务明确失败；不能读取进程状态时失败，不对仍存活进程执行阻塞 wait。
- 新增 `desktop_plasma_session.py` 监督原生合成器和容器。两者运行于各自的本轮进程组；容器 PlasmaShell 及必要服务可响应、音频健康后，原子发布绑定监督进程和随机会话 token 的 marker。60 秒启动总期限内未就绪则失败清理。服务响应不宣称已绘制面板首帧。
- App 的 PRoot Plasma 分支同时检查监督进程、容器进程、Wayland socket 及匹配的本轮 marker；原生 Plasma 保留原状态判据。停止、删除和强制停止删除新 marker。受管容器停止不再按全局进程名称批量终止其他会话。
- 宿主与容器统一使用 CLOCK_BOOTTIME 时间基准及宿主传入的起点记录阶段耗时。
- 音频初始化的每条命令增加 3 秒期限及 1 秒强制终止宽限。新启动的共享 PulseAudio 记录独立服务日志；健康共享服务及已替换可执行文件的旧进程继续复用。容器会话启动前及运行中每约 5 秒检查宿主端点和 Android sink，失效明确撤销就绪并结束本轮会话，不无限重启、不静音继续，不终止健康共享音频。单轮健康检查最多包含两条 3 秒请求。
- `LaunchScriptInstaller` 已登记新脚本；AAR 内脚本字节与最终源码一致，未包含 Python bytecode。

## 本地验证

| 验证 | 结果 | 覆盖 |
| --- | --- | --- |
| `python3 docs/desktop-testing/test-container-plasma-startup.py` | 10 项通过 | 无固定秒级等待、服务退出/总期限、旧 marker/裸合成器、音频丢失/超时/无 sink、共享进程隔离、真实 CLI 停止并再次启动、缓存环境/错误传播 |
| `python3 docs/desktop-testing/test-desktop-initialization.py` | 23 项通过 | 安装/启动边界及缓存失败阻止发布初始化凭据 |
| `python3 docs/desktop-testing/test-desktop-audio.py` | 6 项通过 | 冷启动、复用、local-only、失败与 deleted-exe 共享服务 |
| `:termux-ui:testDebugUnitTest` 选定桌面用例 | 38 项通过 | DesktopEnvironmentTest 16、DesktopLaunchSpecTest 12、DesktopSessionStateResolverTest 8、DesktopTasksSettingsTest 2 |
| `:termux-ui:assembleDebug` | 成功 | Java 编译与 UI 模块 AAR；不是可安装 APK 的验证 |
| 四个变更 shell 的 `sh -n`、`git diff --check` | 通过 | 语法及空白 |
| OpenSpec `validate optimize-container-plasma-startup --strict` | 通过 | 规划格式；不替代运行验证 |
| Harness `strict-run` | Unavailable | 仓库缺少验证脚本及引导规则 |

Gradle 命令：

```sh
./gradlew :termux-ui:testDebugUnitTest \
  --tests 'com.termux.ui.desktop.runtime.DesktopEnvironmentTest' \
  --tests 'com.termux.ui.desktop.runtime.DesktopLaunchSpecTest' \
  --tests 'com.termux.ui.desktop.runtime.DesktopSessionStateResolverTest' \
  --tests 'com.termux.ui.desktop.api.DesktopTasksSettingsTest' \
  :termux-ui:assembleDebug --console=plain
```

恢复环境最初缺少 Hyprland 主题测试归档，已用仓库既有构建工具重新生成；生成的跟踪文件无差异，主题未塞回 APK。Gradle 自动补齐项目声明的 Android 34 SDK/build-tools 与 NDK 25；未修改项目构建参数、签名或权限。

额外运行旧 `test-gnome-usb-install.py`：39 项测试中报告 33 项失败，主要在包操作前被当前双层配置格式拒绝。在从 HEAD 导出的未修改源码上复跑也是同样 33 项失败，属于已有测试夹具不适配当前格式，不能宣称全仓测试通过。该脚本和其目标安装 shell 未因本变更改写；本轮相关安装边界另由上述 23 项验证覆盖。

## 尚未验证

- 尚未部署新代码，未采集优化前后三轮冷/热启动、真实面板/图标首帧和 Dolphin 首次窗口，不能承诺提速秒数。此前 8.19 秒是进程创建间隔，不能用作首帧对比。
- 音频服务目前缺少退出原因证据。本轮增加诊断日志、有限操作及失败监督，没有将其原始退出根因标为已修复。
- 容器缺少宿主 `org.kde.KWin` 的能力缺口保留，未桥接总线或放宽其策略。蓝牙、PipeWire、udev 等模块没有批量禁用或隐藏错误。
- 只读查询已确认现有 ActivityManager、kded6、PlasmaShell 对象和 Peer.Ping 接口；后续将整段等待助手用于远程只读探测时，本地 ADB 调用超时且未产生服务结果，不据此认定任何服务超时。
- 共享音频初始化在其他桌面的本地回归已覆盖，GNOME/XFCE/Hyprland 实际音频播放和 UI 仍需设备验证。

## 风险与回滚

未改变配置 schema、宿主/容器渲染组合、组件资产下载方式及原生包。必要服务意外退出或音频丢失会终止本轮容器会话并明确报告失败；这是批准方案中的故障处理。

回滚以上 Java 与应用脚本，恢复原就绪判据，并显式重新初始化容器；保留用户数据与健康共享音频。未触及 manifest 权限、证书、原生实现、CI、包配方或 D-Bus 权限策略。设备安装/重启验证、暂存、提交、推送及归档均未执行。
