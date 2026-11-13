# 运行时资产按需下载设计

## Context

现状与动机见 proposal.md。两套主题已在 hyprland-components-20261113-r1 发布，但目录仍为 source=bundled；desktop_hyprland_components.py 从脚本旁读取归档，LaunchScriptInstaller 复制它们。APK 同时保留 2,469,104 字节旧字体和主题内同一字体。

API 的四个 ABI deb 已在 embedded-termux-api-0.60.0-r1 发布，经本地 SHA 检查与本次 Linux 构建完全一致。EmbeddedApiInstaller 当前从 APK 抽取 CLI、脚本、deb 和 ARM64 UPower。termux-api/build.gradle 把生成输出直接加为 assets，preBuild 自动编译。API 初始化分别从 TermuxInstaller 和运行时任务宿主调用。TermuxInstaller 在移动 prefix 后调用 API 安装；现有非空 prefix 分支直接跳过，不能照搬为网络安装。

## Goals / Non-Goals

目标为外置字节、受信任按需获取、离线复用和完整失败恢复。Android API 的 Java/IPC、Activity/Service、权限和 USB 保持当前实现。X11、输入、渲染及桌面路由不变。API CLI 在 Linux 随 App 构建；未变化原生合成器不重编。精简范围不涉及 bootstrap so、Wine 负载或 Android 资源裁剪。

## Decisions

### 构建产物与 APK 输入分开

主题生成器输出主题到 distribution 产物/构建目录，APK assets 只保留 JSON、脚本和消费者需要的目录。删除无消费者的旧主题资源与旧字体，源资源与许可仍在 distribution/hyprland-themes。

API 继续使用当前 NDK 和固定源码在 Linux 构建，输出迁至 termux-api/build 下独立组件产物目录，不能作为 Android assets 输入。按 ABI 生成确定性配套归档，包含 CLI、公共脚本、回调及对应 deb；ARM64 配套 UPower 从现有固定资产获得。APK 保留小型目录：ABI、契约版本、每文件身份、归档 URL/大小/SHA。缺乏 Linux 时不得悄悄跳过构建或打包陈旧内容。

复用已公开且哈希一致的 deb，不制造不必要的新原生构建。需要便于 Java 安全安装的配套资源归档时发布独立 ZIP，归档内 deb 与固定历史 deb 校验一致。新元数据通过新修订发布，历史 r1 字节和 source-lock 不改写。

### 下载、缓存和安装

主题仍由 Python 安装器验证资源及 manifest；获取阶段使用固定目录的 HTTPS 地址、大小和 SHA。先验证已安装包/有效缓存，再下载；按哈希标识缓存，临时下载校验后原子改名，失败清理临时文件。下载放在安装锁保护范围并保留候选回滚语义，不因本地无归档就拒绝已经正确安装的主题。

API 采用后台下载与安全配套归档安装，不依赖尚未安装的 Python 或先执行未验证脚本。只选择当前进程 ABI 对应固定项。HTTPS 重定向须有界且不能降级 HTTP；连接与读取超时有界，下载/解压总大小、文件数和每项大小受限。校验候选后再更新 bin/libexec 与 share 下的兼容路径，使用安装锁、事务记录和备份恢复保证失败不会冒充完整版本；下次初始化恢复中断状态。路径白名单拒绝链接、重复文件和越界。校验在应用私有目录执行，保留旧有效 API 直到新候选验证完成。

### 初始化与用户可见状态

基础 prefix/bootstrap 与 API 组件的完成凭据分开。TermuxInstaller 保留可用 prefix；API 首次下载失败明确提示 API 未完成并提供重试，基础终端仍可用。既有非空 prefix 不等于 API 已初始化，显式 API 重试走相同恢复路径。网络和 IO 均在工作线程执行，Activity 只显示状态，不阻塞主线程。

运行时/桌面明确初始化任务依赖 API 完成，失败沿原任务错误通道返回，不能启动一个缺少配套 CLI 的安装流程。Service/任务状态沿用现有提交、运行、失败与重试机制；普通桌面启动只读当前凭据，不额外联网。shell 保留 share/embedded-api 的兼容路径，必要时更新错误文案以反映组件来源，不改变 API 主包路由。

### 规划冲突与发布顺序

此提案在用户确认后取代两个未归档变更中的内嵌交付约定；先更新活动规划与测试预期，再改代码。已发布 source-lock 和验证文件保留为历史事实。准备并审查新配套资料后，沿用既有组件仓库同步授权范围发布新增资产，匿名下载校验完成才将 App 目录固定到新 URL；不覆盖旧资产、不推送 App、不部署或归档。

## Risks / Trade-offs

- 首次安装断网 → 明确区分基础终端和 API 状态，下载可重试；已有有效组件离线可用。
- API 版本与 APK 不匹配 → 编译源固定、目录契约匹配及每 ABI 哈希检查。
- 多文件中断/升级混合 → 先验证完整候选，再使用锁和事务备份/恢复，只有完整版本才写成功凭据。
- 老版本残留脚本旁归档 → 新消费者只按固定目录/缓存复用，经验证的已安装包可用；不能把任意残留文件当作资产库可信来源。
- 包体节省低于预期 → 对最终 ZIP 条目、APK 大小和签名块实测对比。当前可移出的主题、重复字体、API/UPower 约 3 MB，实际交付以构建结果为准。

## 受保护路径

仓库未提供 protected-path policy id（Unavailable）。按已有 API 变更风险清单，termux-api/build.gradle、EmbeddedApiInstaller、app/TermuxInstaller 及运行时任务入口视为高风险。影响为资产输入、初始化状态与脚本写入。需在规划确认时明确覆盖这些路径；验证为不依赖完整 prefix 的归档安装、断网/错误 ABI/哈希失败/并发/中断恢复测试、四 ABI 构建匹配、ARM64 APK 签名及归档检查。回滚恢复旧消费路径和旧 APK，保留有效缓存与用户 prefix，不清除用户数据。不触及 Manifest、签名、权限、CI 或原生 API socket 实现。

## Migration Plan

确认本提案后更新相关未归档交付描述；实现外置和安全安装并通过故障测试；发布新元数据/配套归档；匿名核验；构建精简 APK 并核对无重复二进制、名称和 SHA。设备验证在获得实际设备后执行；产品 docs/product/desktops.md 与 docs/product/android-api.md 在最终用户接受后维护并检查一致性。

仓库 Harness 引导文件及 strict-run 脚本缺失，严格门禁为 Unavailable，不能以 OpenSpec 校验替代。当前阶段仅规划，不改业务代码和现有已发布资产。
