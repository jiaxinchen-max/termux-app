# 当前任务完成情况（2026-11-16）

用户本次明确授权“发布相关组件到组件仓库，总结当前任务完成情况”。当前代码与组件已交付，完整 App 验收尚未完成，未构建或发布新 APK，不归档。

| 工作项 | 当前结果 | 验证边界 |
| --- | --- | --- |
| PRoot 底包统一 | Plasma／Kubuntu、Ubuntu GNOME、XFCE、Hyprland 客户端的目录和安装代码均固定 ubuntu:resolute（26.04 LTS） | 四类完整全新安装仍待验收 |
| 独立 GNOME 容器下线 | 创建、执行、归档入口拒绝 PRoot+GNOME；保留 Native GNOME 和 Ubuntu GNOME | 不迁移旧容器，不默默替换成其他类型 |
| GNOME 原生显示链路 | Mutter 50.1-4、Shell 50.1-2、GJS 1.88.0、SpiderMonkey 140；移植现有 Android backend，新 ABI GIR 真实生成 | 无 PipeWire 显示桥；PRoot 与 Native 完整 GUI 的覆盖范围不同 |
| PRoot 真实会话 | 普通用户桌面、Dock、Home、四扩展、终端输入、Nautilus、会话总线和清理已实测 | 隔离测试容器及私有库覆盖，不能称为新 APK 全新安装通过 |
| 稳定性修复 | 窗口可见性队列重入与 keymap 同步重入已修复 | r4 无诊断 8 次 DING 重启、9 个所属进程退出，无关宿主进程保留 |
| 保存／启动分离 | 初始化、静态校验、默认配置、主题、缓存及文件部署移至保存／安装；启动失败退出会话页 | 实际冷启动未改写静态配置；缺少保存结果退出且不修复。页面退出只经过 Java 编译及测试，未在新 APK 实测 |
| Resolute 适配 | glibc GIO 与 Glycin 使用真实 Resolute 工具链构建，安装校验精确文件与来源；Mesa 使用匹配上游资产 | 图片解码、GIO 命令和生产助手验证通过，真实 USB／高通 GPU 待测 |
| Native Ubuntu 外观 | 真正 Resolute 数据包生成 Yaru、图标、壁纸和四个 Shell 50 扩展；两次构建哈希相同，12 项实际资源测试通过 | 完整 Native GUI 与独立 GTK 应用组合仍待验收 |
| 组件发布 | 两个公开集成验证版、22 个资产、源码／来源锁／SHA256SUMS／验证说明齐全 | 全部原始公开 URL 回读验大小与 SHA256 通过，另验证 1 个上游 Mesa 资产；不改变 Wine catalog |

## 公开发行版

- [gnome-50-resolute-20261116-r1](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/gnome-50-resolute-20261116-r1)：4 个新核心 DEB、3 个复用依赖、GIO 和 Glycin，共 15 个资产。
- [native-ubuntu-gnome-50-r1](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/native-ubuntu-gnome-50-r1)：26,178,566 字节外观包与配套源码、来源和校验，共 7 个资产。

发行版绑定组件仓库清单提交 3c98bd7，App 源码为 52ccd543；App 安装器已经固定这两个版本的 URL 和实际哈希，本次无需改安装代码。旧组件仍保留；本轮不删除或替换用户容器。回退可恢复 App 之前提交及对应组件清单，固定版本资产继续保留，不能自动降级用户系统。

## 验证与剩余工作

801 项 Java 测试通过。保存、GNOME 会话／进程、Hyprland、Plasma 安装、GIO、Glycin、恢复及渲染定向回归已通过。宽泛旧 test-desktop-scripts.sh 仍使用旧运行路径、废弃接口与启动缓存预期，未通过，不宣称全仓库测试完成。规定的 Harness strict gate 和 OpenSpec CLI 缺失。

剩余验收：Native GNOME 50 全套 GUI／独立应用组合、四类 PRoot 全新安装、真实鼠标交互、Discover 密码授权、USB、高通 GPU，以及新 APK 的完整安装／启动／失败退出验证。完成后再交付指定名称 termux-app_apt-android-7-debug_arm64-v8a.apk。产品一致性继续 Pending，整体 NotReadyForRelease，未归档。

公开下载证据见 evidence/component-publication.json，完整逐资产记录在组件仓库 repository/gnome/publication-20261116.json（提交 2e4b062）。历史失败及其后修复证据继续保留于 verification.md。
