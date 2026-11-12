# 原生 Ubuntu 风格桌面增量规范（规划中）

## ADDED Requirements

### Requirement: 原生Ubuntu风格卡片
系统必须允许新建原生Ubuntu GNOME风格桌面，并明确其使用Termux原生组件；容器Ubuntu GNOME必须继续使用ubuntu:noble。普通GNOME卡片必须保留。已存在的原生实例仍按现有唯一性规则隐藏候选；不得显示未支持占位项或新增底包选择。

#### Scenario: 新建原生Ubuntu桌面
- 当用户选择原生Ubuntu GNOME卡片
- 则系统采用独立外观profile、原生Android Wayland/System EGL、guest NONE与60Hz，不安装Ubuntu rootfs，不执行容器apt。

### Requirement: 完整可用的Ubuntu外观
系统必须提供适配GNOME48的Yaru Shell/GTK/图标/光标、Ubuntu壁纸、左侧Dock和程序入口、顶部状态区、托盘与桌面图标；收藏应用必须安装且可启动。组件必须固定版本、来源与摘要并保留许可。

#### Scenario: 桌面进入就绪
- 当Shell、Wayland和必须的扩展/辅助程序均验证就绪
- 则系统可报告会话就绪；实际界面与应用是否可用另需设备验收。

#### Scenario: 必须组件缺失或扩展报错
- 当主题、schema、依赖或必选扩展缺失/失败
- 则安装或启动必须明确失败，不能写成功marker、静默移除功能或回退普通GNOME。

### Requirement: 配置和生命周期隔离
系统必须隔离普通GNOME与原生Ubuntu的HOME/dconf/runtime，首次初始化外观，后续保留用户定制。停止和异常清理只能作用于会话拥有的进程。不提供旧数据迁移；不得因本次变更删除用户文件。

#### Scenario: 自定义后重启
- 当用户调整主题或Dock并重启
- 则系统保留其设置，不以启动脚本覆盖默认值，不改变普通GNOME配置。

### Requirement: 支持边界与回归
原生Ubuntu必须拒绝USB只读、归档、guest渲染模式和非60Hz组合。PRoot Ubuntu继续使用SYSTEM/LLVMPIPE，现有GNOME/Hyprland/XFCE/Plasma支持矩阵不得因外观适配改变。

### Requirement: 原生Ubuntu外观组件按需分发
APK不得内置大型原生Ubuntu外观资源包；创建桌面时从组件仓库的固定版本下载，经固定SHA256校验后安装。有效缓存必须可重用；损坏缓存必须重新下载，下载失败或摘要不匹配必须明确失败，不得写成功标记。启动已安装桌面不得依赖此次下载服务。

#### Scenario: 首次创建与重复安装
- 当首次创建原生Ubuntu桌面时，下载固定组件并校验后安装。
- 当再次安装且缓存摘要有效时，无需重复下载；若缓存损坏，重新获取并验证。

#### Scenario: 下载失败
- 当网络失败、下载不完整或摘要不匹配时，拒绝安装并清理临时下载，不得将其作为有效缓存。

### Requirement: Android原生组件解包
安装必须兼容不提供os.link的Termux Python；归档内部链接仍只允许引用先前包内常规文件，安装为内容一致的普通文件并执行逐文件摘要校验。该修正不改变桌面能力。

#### Scenario: Android Python缺少硬链接接口
- 当Python不提供os.link时
- 则内部图标复用条目仍能解包完成，文件摘要一致，验证完成后写入成功marker。

## 桌面退出缺陷修复
实际日志显示GNOME已使用System EGL运行，但启动器查询org.gnome.Shell.Extensions代理服务（core策略未启用）超时30秒后关闭会话；DING85在构造时遍历/proc/asound/cmdline被拒绝，扩展初始化失败。修复已批准的扩展/就绪/生命周期范围：就绪查询直接调用org.gnome.Shell、/org/gnome/Shell的同名扩展接口，不启用额外偏好设置代理服务；保留Active状态、错误状态和DING总线检查。原生DING去除启动时全局/proc扫描清理，由现有会话进程组拥有和清理DING子进程，不能跨桌面扫描/杀进程。构建固定新组件native-ubuntu-gnome-48-r2，保留上游来源/许可、补丁与摘要清单，不覆盖r1。增加拒绝错误D-Bus地址的进程夹具，验证原生补丁不触发/proc扫描并保留实际DING功能。回滚使用pre-session-exit-fix.tar.gz恢复本轮文件，旧组件不覆盖；安装目录在更新后需重新安装r2，不添加旧数据迁移。

## 基础应用补齐（用户已确认）
用户在确认缺少设置中心后明确要求“补上”，本轮沿用既有安装与会话高风险路径授权，补齐真正的原生GNOME Control Center、计算器、时钟、图片查看器。设置中心基于GNOME48源码适配Bionic，保留上游界面及实际可用的外观、工作区、通知、键盘、搜索、声音及辅助功能面板；依赖Android系统权限或缺少后台服务的网络、电源、用户管理等面板不展示，不放假入口。固定源码摘要及补丁、许可、原生包依赖可审查，不能以Android设置替代。实际验证设置窗口、修改与重启持久性及基础应用窗口。新增包独立按需获取，不内置大型资源；发布具体资产前遵守现有外部发布授权范围。无HTTP契约，product_docs仍Required/Pending。回滚仅恢复pre-apps-container-fix.tar.gz及本轮新增配方，不覆盖既有未提交修改、不迁移旧数据。
