## ADDED Requirements

### Requirement: Hyprland 桌面创建与兼容
系统 MUST 以稳定的 `hyprland` 标识提供原生及 PRoot 客户端桌面创建，首期仅开放经过验证的 system 渲染模式；原生同类型最多一个，PRoot 允许独立容器重复创建。

#### Scenario: 创建原生桌面
- **WHEN** 用户选择原生 Hyprland
- **THEN** 系统创建 Hyprland 桌面记录并使用对应安装和启动路径；已存在同类型原生桌面时禁止重复创建。

#### Scenario: 创建容器客户端桌面
- **WHEN** 用户选择 PRoot Hyprland
- **THEN** 系统创建独立容器，原生宿主运行 Hyprland 并仅向当前容器提供必要连接。

#### Scenario: 请求不支持的能力
- **WHEN** 用户或旧数据请求 Hyprland 的 Zink/llvmpipe、USB 共享或导入导出
- **THEN** 系统明确拒绝不支持的操作，不静默替换渲染器或生成不可恢复的归档。

### Requirement: 可核验组件与安装结果
系统 MUST 使用固定版本、架构及校验和的真实 Hyprland 组件清单，验证匹配依赖和默认客户端；不能用安装 Plasma 代替 Hyprland。

#### Scenario: 安装失败
- **WHEN** 组件下载、校验、解包、依赖安装或安装检查失败
- **THEN** 系统报告失败及可定位日志，不写成功标记，并允许用户重试。

### Requirement: 原生 Android 显示与交互
系统 MUST 在无 root、无直接 DRM 设备要求的 Android 环境中通过原生 Hyprland 后端呈现真实窗口，支持应用现有输入入口和默认平铺操作。

#### Scenario: 打开默认客户端
- **WHEN** 用户启动桌面并从应用入口打开终端或文件管理器
- **THEN** 对应窗口可见，触摸指针和键盘输入生效，窗口可切换、调整布局，画面随分辨率和刷新率配置正确更新。

#### Scenario: 后端不支持所需能力
- **WHEN** 后端不支持初始化所需格式、同步或渲染能力
- **THEN** 启动失败并显示日志，不隐式切到 KWin 或其他渲染模式，不以黑帧宣称成功。

### Requirement: 会话状态与清理隔离
系统 MUST 用当前 Hyprland supervisor、独立 Wayland socket 和当前会话就绪记录判断运行状态；每个桌面的进程、临时文件与持久配置必须隔离。

#### Scenario: 旧 socket 或旧 marker 残留
- **WHEN** 当前 supervisor 未就绪或已经退出，但旧 socket/marker 仍存在
- **THEN** 系统不得显示运行中，后续启动不得沿用旧会话身份。

#### Scenario: 停止并再次启动
- **WHEN** 用户停止一个 Hyprland 桌面后再次启动
- **THEN** 系统仅清理该桌面所属进程和运行时资源，保留用户配置，产生新的会话身份，其他桌面不受影响。

### Requirement: 既有桌面兼容
系统 MUST 保留 XFCE、KWin/Plasma、GNOME 的既有类型标识、安装路径和会话策略。

#### Scenario: 启动已有桌面
- **WHEN** 用户在加入 Hyprland 后启动已有 XFCE、Plasma 或 GNOME 桌面
- **THEN** 桌面继续按其原有配置运行，不继承 Hyprland 专用环境或受到其停止流程影响。


## 本轮候选交付调整（用户已授权）

用户最新要求：“将相关资料发布到组件仓库，然后编译新apk，我来手动验证”。据此授权将组件、构建配方、源码锁定和验证资料发布为独立 Hyprland 候选预发布；应用先开放 system 模式候选入口并构建可安装 APK，由用户手动执行设备验收，替代先由代理运行设备烟测的顺序。此调整不代表设备验证通过；任务 3 的运行证据、任务 11 的设备验收及产品一致性仍 Pending。不得自动部署，不涉及应用代码提交或推送。


### Requirement: 可操作的默认桌面配套
系统 MUST 为 Hyprland 提供 Wayland 状态栏、应用菜单、运行任务列表、工作区入口、时钟、通知服务和默认壁纸；Android 电量展示必须来自实际系统状态，无法读取时明确不可用。配套服务 MUST 归属于当前桌面并保留用户配置。

#### Scenario: 用户从桌面启动和管理应用
- **WHEN** 用户启动 Hyprland 并使用桌面应用菜单或任务列表
- **THEN** 可以启动终端和文件管理器、切换任务并关闭窗口；具有可触摸的浮动/平铺切换入口，Android 控制抽屉不再包含五个临时快捷按钮。

#### Scenario: 升级已有桌面或配套启动失败
- **WHEN** 已有桌面升级并重新启动，或必要配套缺失/退出
- **THEN** 保留原 Hyprland 用户配置，补充独立配套默认配置；必要服务失败时记录诊断并清除就绪状态，不继续显示完整桌面已就绪。

### Requirement: 如实报告 FP16 能力
系统 MUST 依据 EGL 扩展、可渲染格式和输出链路判断 FP16，不能虚报支持或隐藏所有渲染告警；普通 8 位输出下明确高级效果/色彩管理限制，不将渲染环境不支持等同于物理 GPU 不支持。

#### Scenario: Android 输出仅支持 8 位颜色
- **WHEN** 当前输出后端或渲染环境不能满足 FP16
- **THEN** 使用实际可支持的 8 位输出并记录能力证据，普通窗口可操作，限制说明可以由桌面菜单查看。
