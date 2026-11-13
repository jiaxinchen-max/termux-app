# Hyprland 合成器与主题分离设计（用户已批准实施）

## Context

动机见 proposal.md。公开 r8 README 和 source-lock 均声明原生运行包与 r7 相同；本轮读取发布元数据确认 r8 无独立 runtime 资产。当前 desktop_hyprland.py 将 VERSION/RELEASE/ASSET/哈希固定为 r7；DesktopLaunchSpec、DesktopEnvironment、desktop_install.sh 和 desktop_start.sh 另有重复身份。desktop_hyprland_theme.py 的 r7 实为经典外观，m3ww-v1 资源由脚本生成并随 APK 分发。

当前无 openspec/specs 基线、docs/product、仓库级 Harness 工作流及 protected-path 清单；不发明策略编号或门禁结果。规划阶段仅写规划文档；用户已批准后续实施。后续高风险路径实施前须记录影响、回滚与验证并获得所需审批。

## Goals / Non-Goals

目标是让配置、安装、启动和用户界面共享可核验的组件身份，使主题可独立交付和切换。合成器当前只有 r7 一个真实选项，主题提供 classic-r7 与 m3ww-v1；不通过制造 r8 二进制来满足版本命名。

不改变 Android 后端、X11/Wayland 连接、输入分发或渲染策略；不扩大 USB/导入导出支持，不在规划阶段构建、发布、提交或部署。

## Decisions

### 组件目录与包边界

新增一份受 App 信任的组件目录，记录 runtime 和 theme 各自的稳定 ID、资产 URL、归档与 manifest 哈希、架构、支持版本、必要依赖和入口白名单。Java 与 Python/shell 从同一源生成或读取一致身份，并用一致性检查防止漂移。拒绝在多个入口各自硬编码版本；拒绝接受用户输入 URL 或归档内提供的可执行安装指令。

runtime 保持 opt/termux-hyprland/0.57.0-r7；theme 采用独立版本化资源目录和每桌面可编辑配置目录。classic-r7 是对历史 r7 外观标记的明确命名。主题包只包含许可允许的静态配置、字体及壁纸；Python 控制逻辑由 APK 管理。EWW/Foot/Fish/Starship 等依赖通过可信 Termux 源安装并按目录要求检查，不能把未来 apt 版本当作已锁定身份。主题单独可安装资产尚未发布，实施阶段先生成和校验候选，未经发布授权不改生产下载指向。

### App 模型和存储

DesktopDistro 新增 Hyprland 专属 runtimeId/themeId；非 Hyprland 拒绝这些字段。FileDesktopDistroRepository 版本化持久格式；只增加对当前 schema3 的受控导入，不恢复其他历史 schema。旧 Hyprland 先读取合法 desktop-theme 标记，再复用当前对默认配置指纹的判断；自定义配置保持经典外观，未知标记明确失败。通过临时文件、原子替换与保留原配置避免部分升级；重命名、复制、修改输出及 renderer 的 with 方法必须携带组件字段。非 Hyprland 当前格式保留，归档支持范围不扩大；如需调整共享 schema，回归既有桌面归档兼容。

创建/设置界面展示两个字段，当前合成器只显示 r7；主题可选择经典或 m3ww。运行中不保存有效主题变更，提示停止后重试；停止状态下先验证/安装候选，再原子保存选择。新建桌面沿用当前 m3ww 默认。安装卡片分别呈现合成器与主题缺失，不再要求所有主题依赖的并集。

### 安装、任务及会话边界

Activity 只选择和提交任务；DesktopTasks 与现有 TermuxService 承接任务状态和日志；shell 根据明确 ID 请求受信目录中的资产；Python 完成固定哈希、安全解包、临时目录验证和激活。缺失依赖、包损坏及不兼容组合失败时不写成功标记，保留旧选择和组件，允许重试。安装与切换锁防止同桌面并发；共享 runtime 安装沿用组件锁。

DesktopLaunchSpec 传递两个受限 ID；desktop_install/start 和 Python 解析同一目录，取消分散的 r7 等式。DesktopEnvironment 只检查所选主题必需项。session 使用明确选择，不在启动时另行猜测主题。经典面板和 m3ww 服务由所选主题分支启动，保留现有私有 D-Bus、会话 token、socket 就绪与 stop 清理逻辑。启动日志记录 runtime/theme/manifest 身份。

Hyprland 原生与 PRoot 客户端命令、Wayland 绑定、Xwayland 运行包、输入入口和 renderer 白名单保持现有合同；测试证明这些边界仍正确。

## Risks / Trade-offs

- r8 命名混淆 → 在目录、界面和日志中区分 App 修订与运行包身份。
- 旧配置识别与新字段冲突 → 单次受控导入并保留用户文件；未知值拒绝而不猜测。
- 包未发布或哈希不匹配 → 阻止上线该下载项，候选包本地验证不等于公网可安装。
- 共享组件被其他桌面使用 → 本轮不提供自动删除旧组件，切换只调整当前桌面。
- 缺少 Harness → OpenSpec 严格校验与聚焦测试单独记录；不能代替 strict-run。

## Migration Plan

确认规划后先建立目录及候选主题包，验证身份与解包边界，再接入存储、界面和启动链路。旧 schema3 与主题 marker 导入只在备份后执行。主题更新保留已有用户配置，新的默认资源与旧配置并存。恢复旧组合时原子写回之前选择并重新启动；旧 App 若不支持新格式需恢复迁移备份，不能承诺直接降级兼容。

通过 Java/脚本聚焦测试、ARM64 APK 构建和原生/PRoot 实机验证记录结果；未具备设备或构建环境时标记 Pending。最终验收后再维护 docs/product/desktops.md，完成一致性与归档流程。

## 追加交付：公开目录与主题资产

发布独立版本 hyprland-components-20261113-r1，记录两个静态主题归档、manifest、许可、可复现源材料、App 基线与当前文件指纹及验证限制。组件仓库以 repository/hyprland 独立目录管理，避免把桌面组件混入 Wine 运行环境清单。App 的同源生成目录补充主题 release、url、size 及 r7 runtime 大小，保持与公开目录逐字节一致；安装继续使用 APK 内置的相同主题字节。新发布标记候选，不能复用历史实机证据声称本次已设备验收。

先完成资料、源材料和配置生成并审查，再推送组件仓库及上传公开资产，匿名下载复核大小和 SHA，最后按原名构建与交付 ARM64 APK。生态 deb 无变化，不触发远程原生重编。PAT 只存于认证进程内存，不出现在源码、Git URL、日志或产物。

## 已确认的交付方式修订（download-runtime-assets-on-demand）

用户已确认主题与 API 配套资源改为资产库按需下载，取代本文件此前内嵌主题/CLI/deb 的交付要求。Android API 宿主保留 APK；Linux 下随 App 构建配套包与是否打入 APK 分开处理。最终要求及验收场景以 download-runtime-assets-on-demand 的四项规划为准，历史发布与验证记录保留原意，不声称旧 APK 已外置。
