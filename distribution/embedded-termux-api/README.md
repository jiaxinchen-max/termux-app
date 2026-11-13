# Android API 配套组件

Android API 的 Java/IPC 宿主仍在主 APK；配套 CLI、脚本、deb 和 UPower 由组件资产库独立分发，APK 只保留 runtime-api-components.json 的可信固定目录。

Linux 下随 App 执行 generateApiCli，用相同 NDK 和固定源码构建四种 ABI，产物在 termux-api/build/outputs/apiComponents，不进入 Android assets。目录与重新构建不一致则构建失败；有意修改配套协议时先更新规划、重新生成目录并发布匹配版本。

```sh
python3 tools/embedded-termux-api/build.py --ndk /path/to/android-ndk --output termux-api/build/outputs/apiComponents
```

新增版本目录仅在审查匹配发布时使用 --write-catalog 更新。每 ABI 的确定性 ZIP 包括该架构 CLI/deb、共用脚本/回调；ARM64 包还包含已固定的 UPower deb。当前四 ABI deb 与 embedded-termux-api-0.60.0-r1 的公开摘要一致；ZIP 发布为 embedded-termux-api-0.60.0-r2。ZIP 的每文件清单、归档大小和 SHA 都保存在 APK 固定目录中，不能动态取 latest。

本目录 upower_1.91.5-1_aarch64.deb 为原有构建输入，SHA-256 为 e0198daeabe6e8e5013c9d393340118f27c583c8a68cdbc2f774e2b4d3b478f3，来源为同一组件库 embedded-termux-api-0.60.0-r1；它只用于生成独立 ZIP，不进入 APK。原始 CLI 许可和源码在 tools/embedded-termux-api。

安装仅在显式初始化/修复任务发生：当前 ABI 的 HTTPS 固定资源先校验、缓存，安全解包到候选目录，再持锁激活；失败恢复旧文件，完整版本才写凭据。缓存和完整已安装内容可离线复用。基本终端初始化与 API 组件状态分开，下载失败不删除 prefix，可在提示中重试或继续使用终端。普通桌面启动不新增下载。

Hyprland、KWin 等 termux-packages 原生包仍在指定远程编译机编译；本轮未变化的原生合成器不重编。实际设备 API/权限/USB 及图形输入验证待设备验收，自动化不能代替它们。
