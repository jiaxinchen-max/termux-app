# 原生 Ubuntu 风格 GNOME 桌面

## 背景与目标
用户在询问 native 是否支持 Ubuntu 桌面后要求“适配一下，纯 gnome 太丑了”。本次新增原生 Ubuntu GNOME 外观配置，提供 Yaru、Ubuntu 壁纸、左侧 Dock、应用菜单、顶部状态区、托盘和桌面图标。使用 Termux 原生 GNOME 48 与 Android System EGL；原生卡片明确标注 Ubuntu 风格，不表示运行 Ubuntu 底包。PRoot Ubuntu GNOME 继续使用真实 ubuntu:noble。

## 范围
复用现有 UBUNTU_GNOME 类型，在 native 下增加受支持卡片，并保留普通 GNOME。Native 两种 GNOME 外观配置使用独立 HOME、dconf、runtime 与就绪状态，共享经摘要校验的只读组件；原生模式继续每种桌面仅建一个。新增 GNOME48 适配的 Yaru Shell/GTK/图标/光标、Ubuntu Dock、AppIndicator、DING 与必要原生依赖，初始化真实存在的文件管理器、终端和编辑器入口。若某原生应用缺少则补充受支持原生应用，不能留下无法打开的图标。
不使用 Ubuntu glibc 可执行文件，不改变合成器、60Hz约束和System EGL，不添加软件回退、旧数据迁移、登录管理器、Snap或商店。原生Ubuntu首版USB与归档仍关闭。容器Ubuntu及其渲染策略不变。

## 交付与风险
产物为可构建的原生外观组件、安装与启动校验、回归证据及ARM64 APK。实际桌面需另行取得具体设备部署授权后验证，不以fixture或编译成功代替视觉验收。扩展metadata支持48只是候选兼容证据，不能保证Android Wayland运行成功。组件发布为独立外部写入，在产物及摘要准备齐后请求具体发布授权。

## 文档和门禁
api_docs=N/A：无HTTP契约变化。product_docs=Required，验收后更新docs/product/desktops.md，consistency=Pending。本checkout缺Harness bootstrap、strict脚本、OpenSpec CLI、baseline specs和规范产品目录；记录Unavailable，按本地规划和显式验证推进，不宣称完整门禁通过。本次详细规划与受保护路径待用户确认。

## 已批准的分发调整
用户确认将导致APK增加28.20MB的原生Ubuntu外观包移至组件仓库按需下载（“这还用问吗?肯定的啦”）。发布目标为jiaxinchen-max/termux-app-packages的新固定版本native-ubuntu-gnome-48-r1，不覆盖已有组件。APK仅保留安装/校验helper，创建原生Ubuntu桌面时下载；桌面功能与来源不变。

## 安装缺陷修复
模拟器实际安装日志确认Termux Python3.14无os.link接口，内部hardlink解包抛AttributeError。本修复属于已批准的原生外观安装与修复任务：保留固定组件与路径校验，将归档内部hardlink内容写为普通文件，使Android可完成安装。APK名、签名、外置分发与桌面行为不变。

## 桌面退出缺陷修复
实际日志显示GNOME已使用System EGL运行，但启动器查询org.gnome.Shell.Extensions代理服务（core策略未启用）超时30秒后关闭会话；DING85在构造时遍历/proc/asound/cmdline被拒绝，扩展初始化失败。修复已批准的扩展/就绪/生命周期范围：就绪查询直接调用org.gnome.Shell、/org/gnome/Shell的同名扩展接口，不启用额外偏好设置代理服务；保留Active状态、错误状态和DING总线检查。原生DING去除启动时全局/proc扫描清理，由现有会话进程组拥有和清理DING子进程，不能跨桌面扫描/杀进程。构建固定新组件native-ubuntu-gnome-48-r2，保留上游来源/许可、补丁与摘要清单，不覆盖r1。增加拒绝错误D-Bus地址的进程夹具，验证原生补丁不触发/proc扫描并保留实际DING功能。回滚使用pre-session-exit-fix.tar.gz恢复本轮文件，旧组件不覆盖；安装目录在更新后需重新安装r2，不添加旧数据迁移。

## 基础应用补齐（用户已确认）
用户在确认缺少设置中心后明确要求“补上”，本轮沿用既有安装与会话高风险路径授权，补齐真正的原生GNOME Control Center、计算器、时钟、图片查看器。设置中心基于GNOME48源码适配Bionic，保留上游界面及实际可用的外观、工作区、通知、键盘、搜索、声音及辅助功能面板；依赖Android系统权限或缺少后台服务的网络、电源、用户管理等面板不展示，不放假入口。固定源码摘要及补丁、许可、原生包依赖可审查，不能以Android设置替代。实际验证设置窗口、修改与重启持久性及基础应用窗口。新增包独立按需获取，不内置大型资源；发布具体资产前遵守现有外部发布授权范围。无HTTP契约，product_docs仍Required/Pending。回滚仅恢复pre-apps-container-fix.tar.gz及本轮新增配方，不覆盖既有未提交修改、不迁移旧数据。
