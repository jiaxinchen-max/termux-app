# Design

## Context

参见 proposal.md。现有 App/初始化器固定校验 debian:forky，不适合直接伪装 Ubuntu；本轮用独立测试入口调用既有会话监督器，不改产品校验。设备原桌面状态为 exit:70。

## Goals / Non-Goals

目标为实际完成 Ubuntu 安装、包完整性与 EGL 校验、Plasma 和 Dolphin 的兼容性观测。非目标为发布 Ubuntu 产品选项、解决所有设备探测或掩盖已有计时和音频监督缺陷。

## Decisions

固定 `ubuntu:26.04@sha256:065aa8626841f16057b78b027b464cd59c4fac36c204c72dcbf48d66f5fa8a96`，名称 `ubuntu2604-plasma-test`。通过当前 proot-distro 安装，apt 使用现有 proc-fdinfo statx helper 和原始 maintainer scripts，失败保留完整日志。先 apt 模拟并记录包/推荐项，尽量保持 Debian 现有包清单；遇发行版命名/路径差异按实际包内容适配临时入口并留证，绝不伪造安装成功。

Activity、Service 产品代码不变；验证成功后增加独立、明确标为 test 的临时桌面记录以使用现有显示视图，保留既有记录。App 安装/归档仍固定 Debian，本实验不使用这些入口，不宣称产品化支持；临时记录启动仅复用无底包校验的既有会话路径。测试 shell/日志置于独立文件目录，通过现有显示桥和原生 KWin 运行，复用当前输入通道。仅在已有桌面退出后占用共享显示，启动前检查。使用宿主 System EGL、容器 LLVMpipe，保持 1280×720@30。下载仅官方 Ubuntu OCI/APT；不编译 Hyprland/KWin deb，不改资产仓库。

计时以宿主真实 CLOCK_BOOTTIME 和采集端时间为准，不使用 guest 伪 /proc/uptime。服务响应、第一张可视桌面、面板完整出现、Dolphin 首次窗口分开记录；未采集到的指标记缺失而非通过。现有 3 秒音频检查保持原样进行首轮，发生故障则记录，不把其退出归因于 Ubuntu。

### 下载执行调整

用户追加要求“传输文件就是这么不稳定，别挣扎了，直接写脚本在模拟器下载更快”。停止本地大包传输，上传小型原生 Python 脚本，在模拟器直接读取自身生成的 APT URI 清单，8 路并发下载官方 HTTPS 包；已有完整缓存按大小和清单哈希复用，未完成文件留在 partial，校验通过后原子发布到 APT 缓存。下载成功自动恢复原有 apt/helper 安装与 prepare 校验，日志和退出码均落盘。APT 的签名/包校验和真实 maintainer scripts 保持启用。为减少庞大包摘要输出的解析成本，仅在实验恢复流程给 APT 设置 quiet=2；安装/配置错误仍记录。最初下载阶段正常 SIGINT 中断，dpkg 未开始，退出码作为历史保留。

实测发现官方 OCI 底包含 `/etc/apt/apt.conf.d/docker-clean`，其 `APT::Update::Post-Invoke` 会删除已下载的全部 deb。首次直下载 1217 个包校验成功后，恢复脚本的正常 update 触发清理，导致重复下载。确认尚无 dpkg 子进程后中断该次 apt，将此单个缓存清理配置移入实验目录 `docker-clean.original` 备份；不改变其他 APT 钩子、签名/包校验或 maintainer scripts。随后仍由模拟器原生脚本补齐缓存并恢复安装。该实验适配必须随结果交付，不能归因于 Ubuntu Plasma 运行兼容性。

## Risks / Trade-offs

- 下载/安装耗时与存储增长：先检查空间，保留进度日志，可继续未完成安装。
- 软件包/systemd 用户配置不兼容：复用既有 statx 安装，不替换 postinst 或全局绕过校验。
- 会话协议或渲染不兼容：采集日志和画面，不提升为产品支持结论。
- 当前脚本/音频故障干扰比较：明确标注，不将单次响应当作首帧。
- 无 repo protected path 业务变更；仅本地规划与实验文件。

## Migration Plan

用户“那就换一下ubuntu底包测试一下”授权当前模拟器上的独立底包安装和测试会话。设备不卸载现有桌面、不覆盖 App；测试会话退出后可直接启动原 Debian 桌面作为回滚。Git 提交、推送、APK发布和正式默认底包切换均非本轮交付。

### 后续诊断边界与实际结果

完整包安装与 EGL 检查成功后，首轮经 App 正常入口启动。容器服务响应约 42.974 秒，但宿主 pactl 的 3 秒健康检查超时导致会话退出，不能归因于 Ubuntu 崩溃。为分离音频影响，仅在独立实验目录复制运行脚本，临时副本跳过周期音频检查；原 App runtime 未修改，且初始音频检查保留。这份副本的 60 秒启动窗口仍超时。

最后仅将临时副本的启动截止延至 180 秒，未继续绕过其他策略；该轮约一分钟内在启动 Plasma 阶段触发 PRoot `compare_paths2` 的 `length1 > 0` 断言，容器退出 status 1。临时副本不能证明正常策略下的兼容性或音频稳定。该轮截图为 App 的会话退出遮罩，不能作为客体首帧证据；此前 App 正常启动的截图只见黑屏和光标。没有完成 Dolphin 可见窗口的冷/热启动测量，不宣称 Ubuntu 更快。所有实验保留容器、日志和停止记录。

### 不存在能力的隔离规避实验（用户追加授权）

用户要求检查能否规避 PRoot 不具备的能力，并明确每轮功能测试前杀死 App，从当前 UI 默认入口干净启动。后续本实验每轮先 Android force-stop com.termux，再按 UID、可执行文件、PID/starttime 和已知实验归属定向处理残留；不复用上一轮桌面、总线或音频。冷启动使用系统 Launcher 解析的 UiLauncher，不改用户默认模式。观察器位于 run-as 测试上下文，记录为诊断进程，不计入桌面残留。

先在完全清理的原脚本下记录对照，再只在独立 Ubuntu 容器中使用软件自身支持的配置逐项规避。Qt 6.10.2 源码明确支持 QT_NO_XDG_DESKTOP_PORTAL=1；采用独立测试容器会话环境设置，不冒充 portal/快捷键/KWin 服务。若 Qt 禁用 portal 改善启动，再评估只对缺失的 ScreenCast/RemoteDesktop 等接口设置 portal none，保留可用文件选择等能力。保留 PackageKit/PolicyKit 校验与现有宿主音频监督；不使用 GUI 测试会话后端，不修改系统 D-Bus 认证/权限。

只在独立测试容器可备份的环境文件或配置中做临时适配，启动/停止使用原 App runtime。原部署 runtime、Debian、产品默认配置与 UI 默认选择不改；每轮收尾回滚该实验配置。记录实际壁纸、面板、Dolphin窗口和能力损失；无实际可视证据不标性能成功。正式产品策略若需要变化，另列产品级提案供确认，不将实验授权视为提交、推送或发布授权。

### 托盘整组排除定位

Qt portal 对照后仍无可见面板，因此追加一次相互独立的排除实验：先恢复原 portal 环境，再备份并临时移除隔离 Ubuntu 用户配置中的 systemtray 包含体及其 AppletOrder 引用；保留启动器、任务栏和时钟。该实验会暂时隐藏包括可用功能在内的全部托盘，不能作为正式方案，也不能据此断言某个硬件插件是根因。仍执行 force-stop、残留归属核验和 UI 冷启动，完成后逐字节恢复配置。
