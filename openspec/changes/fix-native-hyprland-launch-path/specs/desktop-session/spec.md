# 桌面会话要求增量

## MODIFIED Requirements

### Requirement: Native Hyprland 固定入口兼容
系统必须接受固定 Native Hyprland 的受管工作目录，使主题服务和桌面操作可以运行。原有 Native 自定义记录与容器受管目录必须保持兼容，不得删除用户配置。

#### Scenario: 固定入口启动主题
- 当工作目录为 HOME/.termux/hyprland-native-hyprland
- 则主题 serve 必须启动前台面板服务，不能以 Invalid desktop path 退出。

#### Scenario: 固定入口执行桌面操作
- 当工作目录为 HOME/.termux/hyprland-native-hyprland
- 则桌面操作 CLI 必须通过目录校验并执行请求动作。

#### Scenario: 旧记录及容器兼容
- 当工作目录为宿主旧 hyprland-desktop-<ID> 或容器 desktop-<ID> 的受管位置
- 则对应模式的主题和桌面操作 CLI 必须继续可用。

#### Scenario: 无效路径拒绝
- 当父目录不在当前模式受管位置、ID 缺失或包含非法字符、容器使用宿主目录或宿主使用其他 native-* 名称
- 则 CLI 必须在执行动作或启动服务之前拒绝。

### Requirement: 升级后的脚本自动生效
应用覆盖升级后，启动必须使用当前 APK 自带的桌面脚本，无需重新安装桌面。只更新应用管理的脚本，不执行软件包安装、下载或准备探测，不修改已初始化的规格、用户配置和数据。

#### Scenario: 已有安装记录升级后启动
- 当运行目录残留旧脚本且新 APK 含有修复
- 则必须在提交启动之前替换为新脚本，保留规格及用户配置。

#### Scenario: 刷新失败或操作冲突
- 当本地脚本无法刷新、存在安装任务或归档冲突
- 则不提交旧脚本；现有冲突保护先于脚本更新，复制失败返回可识别错误。

### Requirement: 宿主应用共享可用桌面环境
私有 D-Bus 激活的宿主程序必须获得就绪 Wayland 会话的显示与 XDG 环境，且不得加载合成器私有库。直接启动与 D-Bus 激活的应用应连接同一桌面，不得为单个应用绕过标准启动机制。

#### Scenario: D-Bus 激活编辑器或 Qt 程序
- 当合成器已经生成最终 Wayland socket 且用户通过标准 desktop 条目启动程序
- 则激活服务必须收到对应 WAYLAND_DISPLAY、XDG_RUNTIME_DIR 与宿主库环境。

#### Scenario: 激活环境更新失败
- 当私有总线拒绝环境同步或工具执行失败
- 则启动必须报告错误并清理当前会话，不发布 ready。
