# 本地验证与设置审查

## 设置审查结果

| 设置 | Native / PRoot 结论 |
| --- | --- |
| 分辨率 | 新建及设置共用 Native、1280x720、1600x900、1920x1080、2560x1440，保留原有自定义值；Native 按本次启动窗口像素解析。 |
| 刷新率 | XFCE、Plasma、Hyprland 均提供 30/60/90/120 Hz；Ubuntu GNOME 在两种模式均固定 60 Hz，就地说明当前 Android 后端限制。 |
| 渲染器 | 共用已有 DesktopRendererPolicy；单个有效选项统一禁用并隐藏箭头，保留桌面及客户端渲染能力差异。 |
| 名称 | Native 固定入口沿用桌面名称，PRoot 实例可编辑，属于有意区别。 |
| USB | 共用能力策略；PRoot 及 Native XFCE 有可用实现，其余 Native 后端没有对应挂载能力；不虚构支持。 |
| 方向和输入 | 继续共用同一会话界面、全局设置及实例覆盖机制。 |
| Hyprland 组件和主题 | 仅在 Hyprland 显示；保留既有容器拥有客户端及主题的实现。 |

Mutter Android 源码 `tools/termux-usb-bridge/packaging/gnome/mutter/android/meta-backend-android.c` 将刷新率初始化为 60，仅解析宽高环境变量，因此本次不把菜单开放成后端无效的 90/120 Hz。

## 证据

- 67 项相关 Java 测试通过：termux-ui 42 项，app 25 项；覆盖两种运行模式乘四种可创建桌面、创建/设置实际交互、Native 模型与仓库/存档往返、数值准备与恢复、会话视图与实际 spec 一致、活动会话重入、互斥及失败保护。最终会话生命周期微调后，另重跑 3 项会话测试通过。
- 38 项 Python 测试通过：输出设置 5、Hyprland 16、GNOME 会话 15、D-Bus 激活 2。通过实际子进程验证新的 MUTTER_ANDROID_* / AQ_ANDROID_* 几何；非几何准备参数变化仍拒绝。
- 退出重试测试最初超时；使用 HEAD 原始 Activity 源码同样复现。测试替身未模拟停止 supervisor 写回 stopped，已修正测试替身，产品停止逻辑未变。
- 首轮扩大检查暴露旧 USB 单元测试错误地预期 `read-write` 被拒绝。HEAD 原始枚举与当前文件完全一致，独立 Java 执行均返回 READ_WRITE。该不相关旧断言未改动，也不计入本次通过的测试数。
- `git diff --check` 和修改的 Python 语法检查通过。
- `.harness/scripts/verify.sh --change-id unify-desktop-display-settings --strict-run` 退出 127：仓库缺少脚本，严格门禁为 Unavailable，不宣称 Harness 通过。

## 交付边界

本地构建 arm64-v8a Debug APK，文件名严格保持 `termux-app_apt-android-7-debug_arm64-v8a.apk`。校验签名、ABI、资产与源码一致性、包体和本地下载校验值，报告位于 `/home/user/workspace/artifacts/desktop-display-settings/`。隔离增量 APK 打包状态后构建，避免旧 ZIP 空洞造成包体膨胀。

候选未安装到模拟器；设备完整显示验证待完成。产品文档 `docs/product/desktops.md` 及一致性审核保持 Pending，等待最终验收。未归档、stage、commit、push、创建 PR 或部署。
