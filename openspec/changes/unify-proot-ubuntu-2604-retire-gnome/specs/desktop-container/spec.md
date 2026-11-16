# PRoot 桌面统一底包规范（规划中）

## Requirement: 统一保留桌面底包
系统必须为 Plasma、Ubuntu GNOME、XFCE 和 Hyprland 容器客户端使用 ubuntu:resolute；创建和启动验证应与镜像元数据一致，不得回退到 Debian 或 Noble。

### Scenario: 新建受支持桌面
- 当用户新建任一保留的 PRoot 桌面时
- 则使用 Ubuntu 26.04 LTS 基础镜像并验证 ubuntu/resolute，桌面程序以普通用户 termux 运行，初始密码 termux。

## Requirement: 废弃独立 GNOME 容器
应用不得创建、启动或导入独立 GNOME 类型的 PRoot 桌面，必须在适用入口明确说明该类型已废弃，提示新建 Ubuntu GNOME；不得隐式改名或转换现存数据。

### Scenario: 选择与直接调用
- 当用户查看 PRoot 创建列表时，不显示独立 GNOME，但显示 Ubuntu GNOME。
- 当脚本、存量记录或旧备份请求 PROOT+GNOME 时，明确拒绝并保持原数据可清理。
- 当用户选择 Native GNOME 时，维持原支持行为。

## Requirement: 发行版相关组件真实匹配
系统必须核对 Resolute 的 GNOME、Yaru、扩展、Mesa 和 USB GIO 文件布局及 ABI，下载资产必须有可校验的身份和哈希；不将 Debian 构建伪装成 Ubuntu 构建。

### Scenario: 组件不兼容
- 当缺少实际匹配的包或 ABI 校验失败时，安装明确失败并记录原因；不得通过跳过校验或静默切换底包制造成功状态。

## Requirement: 非破坏性变更
系统不得自动升级/迁移/删除现存容器或备份。旧发行版在不支持的执行入口提示重建，保持可删除清理。完整图形、授权和支持的设备桥能力经新容器实测后方可宣称通过。

## Requirement: Ubuntu GNOME 原生显示链路
Ubuntu GNOME 必须复用升级后的 Android 原生 Mutter/GNOME Shell 50 配套组件，通过 System EGL/AHardwareBuffer 与 App 交换画面、输入。不得新增 PipeWire、Devkit、Openbox 或 Xlorie 作为 GNOME 显示中转。

### Scenario: 容器与原生运行时协作
- 当用户启动 Ubuntu GNOME PRoot 时，在普通用户的容器视图中运行版本匹配的 Bionic Shell/Mutter；应用及会话服务使用 Resolute glibc 程序，保持同一私有会话与文件系统视图。
- Bionic 和 glibc 程序不得加载对方的共享库或 introspection 类型库。
- 当运行时缺失、版本不匹配或显示初始化失败时，明确失败，不启动 PipeWire 或软件显示桥伪装成功。
- Native GNOME 与 Native Ubuntu GNOME 升级到配套 50 系列，保留直接 Android 输出；Native Ubuntu 主题/扩展须匹配新版本。

### Scenario: 就绪与退出
- 启动成功必须验证真实 Android 输出、Shell 会话及所需扩展；仅有 D-Bus 名称不等于显示成功。
- 合成器退出时清理本桌面所属进程，不影响其他桌面或删除容器。

## Requirement: 初始化只在保存阶段执行
桌面保存／安装必须完成所有持久初始化与静态验证；启动只执行已保存配置并记录本次会话状态。启动不得执行版本／依赖／主题／Glycin 哈希预检，不得重写默认设置、修复或重新安装。

### Scenario: 启动失败
- 当已保存的会话执行失败时，直接退出，保留原始日志。
- 不触发安装器、初始化器、自动修复或回退。
- 当保存阶段失败时，不发布新的可用准备结果。
- 启动终态失败或未就绪就退出时，关闭本次会话页面并保留失败日志，不继续等待或重试。
