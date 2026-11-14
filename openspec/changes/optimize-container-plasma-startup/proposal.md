# 容器 Plasma 启动优化

## Why

模拟器当前会话中，KWin 与 plasmashell 进程创建相隔 8.19 秒，App 却仅凭宿主合成器就绪报告桌面可用。正常启动还会串行等待服务并全量重建 KDE 缓存；音频端点失效造成持续重试。目标是减少实际等待并如实展示启动状态。

## What Changes

- 将 KDE 全量缓存构建放到容器安装、重新初始化的完成边界，正常启动不再全量重建或扫描缓存。
- 使用有总期限的服务就绪等待替代固定休眠；保留 PolicyKit → PackageKit 的依赖及必要服务失败处理，验证依赖后再并行独立 KDE 服务。
- 为当前容器会话增加独立就绪证据、阶段耗时和超时清理，宿主 KWin 就绪不再等于容器 Plasma 就绪。进程存活、服务响应与画面首帧分开记录。
- 查明并修复 PulseAudio 启动成功后端点消失的生命周期问题，复用健康共享服务；失败有界且可诊断，不靠静音或无限重启掩盖。
- 核查 Dolphin 首次打开及其他发行版/桌面对共享音频与就绪逻辑的影响。

## Capabilities

### New Capabilities

- `container-plasma-startup`：容器 Plasma 的初始化缓存、启动依赖、运行就绪及失败诊断。当前仓库无正式基线规格；此能力补充活动变更 optimize-all-desktop-startup-checks 的安装/启动边界，不推翻其要求。

### Modified Capabilities

无正式基线能力可修改。

## Impact

计划涉及 desktop_container_session.sh、desktop_install.sh/容器初始化助手、desktop_start.sh、desktop_stop.sh、desktop_common.sh，以及 DesktopTasks/环境就绪辅助类及聚焦回归。共享音频改动需检查 GNOME、XFCE、Hyprland；不批量改写其启动流程。

无 HTTP 契约变更，api_docs=N/A。用户可见行为变化，product_docs=Required，目标 docs/product/desktops.md，验收前仅记录计划。

兼容性：沿用当前配置格式及宿主/容器渲染选择，不迁移旧数据；需要缓存的现存测试容器通过显式重新初始化准备，不在启动偷偷补建。非目标：新增发行版、重编译桌面 deb、跨身份共用 D-Bus、KWin 服务代理、隐藏错误日志、关闭音频、发布 APK。缺少 org.kde.KWin 记录为能力缺口，本轮不放宽总线权限。

风险：过度并行导致依赖竞态、旧 marker 误判、缓存用户不一致、音频监督误伤共享客户端。回滚应用脚本和就绪判定，重新执行原初始化；不删除用户桌面数据，不杀健康共享音频。

调查依据：/home/user/workspace/artifacts/container-desktop-startup/diagnosis.md。8.19 秒是进程创建间隔，尚无冷启动首帧基线，不能承诺固定提速秒数。

状态：用户已明确“批准实施”，本地实现和验证进行中；设备未部署。仓库缺少 Harness 引导文件及 strict-run 脚本，严格门禁只能记为 Unavailable。
