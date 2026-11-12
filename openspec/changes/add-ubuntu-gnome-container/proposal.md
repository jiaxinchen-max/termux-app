# 增加 Ubuntu GNOME 容器桌面

## 背景与授权
用户已批准创建页面仅展示受支持桌面卡片、隐藏底包选择，并明确“不用考虑兼容旧数据”。最新答复允许 Ubuntu 作为原“仅 Debian”范围的例外，支持 Debian 和 Ubuntu 底包。本变更承接该授权，将真实 Ubuntu GNOME 加入创建目录；详细方案与高风险路径已由用户明确“批准执行”。

## 目标与用户可见行为
容器创建展示 XFCE、Plasma、GNOME、Hyprland、Ubuntu GNOME 五个受支持桌面卡片，自动匹配底包。前四项沿用当前 Debian 版本，Ubuntu GNOME 使用 Ubuntu 24.04 LTS noble ARM64；不展示底包下拉框或尚未支持的占位项。原生桌面仅保留现有四项，不展示 Ubuntu GNOME。
Ubuntu GNOME 使用 Ubuntu 官方仓库的 Shell、Ubuntu session、Yaru 和 Ubuntu Dock 等开源组件，呈现 Ubuntu 桌面、应用启动入口、状态区及文件管理器。保留普通 GNOME 卡片，不能以重命名 Debian GNOME 实现 Ubuntu 卡片。

## 范围与非目标
继承双层渲染设置；Ubuntu 首版仅支持宿主 System EGL 与容器 llvmpipe，显式说明软件渲染。Ubuntu 专用 Zink、USB GIO 组件尚无验证，不开放相应选项，不加载 Debian trixie 二进制组件。现有 Debian 矩阵不变。不新增发行版×桌面的自由组合，不承诺完整登录管理器、systemd 或 Snap 商店；安装 apt 应用不依赖 Snap。
仅支持当前格式，不迁移旧配置、归档或启动规格，不主动删除用户文件。Ubuntu GNOME 暂不提供备份恢复，沿用未支持桌面的明确拒绝规则。

## 风险、验证与回滚
Ubuntu 的 Mutter、扩展与无 systemd 的 PRoot 会话需要实际验证；软件渲染存在性能限制。先完成隔离安装及启动检查，再构建 ARM64 APK。若 Dock、桌面图标或窗口不可用，不标记功能通过。回滚仅恢复本次源码及新增资产，保留 r10 修改及用户数据，禁用 Ubuntu 卡片；不提供旧格式兼容。

## 文档与门禁
无 HTTP 契约变化，api_docs=N/A。产品能力文档目标 docs/product/desktops.md，product_docs=Required，consistency=Pending，最终接受后维护。当前 checkout 缺少 Harness bootstrap、strict verify.sh、OpenSpec CLI 和 baseline specs，完整门禁为 Unavailable；采用显式规划与本地验证记录，不能声称严格门禁已通过。不继承 Git 推送、部署或归档授权。

## Ubuntu安装缺陷和基础应用（用户已确认）
实际desktop-61adcfe6安装日志显示fonts-ubuntu-classic与fonts-ubuntu互斥，apt退出100。删除清单中的classic包，保留noble默认fonts-ubuntu；不使用忽略依赖或破坏dpkg状态的方式。用户要求检查容器安装失败并补齐桌面应用，修复沿用既有安装链路授权；Ubuntu容器同时显式安装gnome-control-center、计算器、时钟、eog，因--no-install-recommends不能依赖推荐包。设置程序和真实桌面入口须纳入文件检查和会话激活。验证真实noble apt解析与安装结果，失败不写marker；既有Debian路径不变。回滚仅恢复pre-apps-container-fix.tar.gz本轮文件，保留rootfs和用户数据；无旧数据迁移。无HTTP契约，产品知识保持Required/Pending。

## 已授权安装/会话修复中的DING排障
最终APK实测容器完整安装成功，但Ubuntu DING47读取/proc/asound/cmdline被Android拒绝，必选扩展失败导致会话退出。沿用当前容器安装、会话和单会话清理授权，安装后对固定Noble DING源码做SHA256识别和原子补丁，移除全局/proc扫描，子进程由现有会话进程组清理。未知源码或文件不完整明确失败，不写成功marker；启动只验证已修复的源码，不自动迁移旧数据。补幂等、未知版本拒绝和marker故障回归，重编正常APK并在当前模拟器验证。回滚仅恢复本轮脚本及配方快照；组件旧发布不覆盖。无HTTP契约，产品文档仍Pending。
