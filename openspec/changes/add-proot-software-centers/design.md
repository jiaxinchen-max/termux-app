# 实现设计

## 安装和准备
三个 PRoot 安装路径明确安装 gnome-software、gnome-software-plugin-deb、packagekit、appstream、apt-config-icons、polkitd、python3-gi、gir1.2-polkit-1.0 和 zenity。复用 AppStream 准备脚本，在安装时获取 APT 应用索引。保存/恢复验证所需组件，部署容器专用服务配置和管理脚本；不在启动时补包。

## 会话服务
新增窄范围的容器包管理服务监督器，由 guest root 启动私有 system bus、PolicyKit、PackageKit 和密码代理。服务配置在保存时生成；运行仅创建 IPC、启动/监督/停止本会话子进程。仅发布已显式启动的服务，不自动激活硬件守护进程。必要服务启动失败直接退出并清理，不重试。认证代理运行异常报告认证不可用，不终止已正常运行的桌面。

GNOME 使用现有短 runtime 挂载和准备命令，把 system bus 管理方改为 guest root；GNOME Shell 与应用继续普通用户。XFCE PRoot 统一走现有会话监督器，包括 USB 关闭路径。Hyprland 原生合成器保持原有宿主总线，所有 guest 客户端显式连接独立 guest system bus，应用中心有专门启动入口。

## 认证与隔离
复用现有进程级 PolicyKit 注册代理，GNOME Software 会话使用 zenity，Plasma 保留 kdialog。认证仅使用 PolicyKit 的 PAM helper/cookie，不伪造授权、登录会话或密码结果。固定标准 guest system bus 地址供清理环境后的 PAM helper 使用，不连接宿主服务。

## 风险、回退和验证
服务生命周期、短 Unix socket 路径、PRoot 身份映射是主要风险。只操作本会话的子进程。保留 Plasma 实现以控制回归面。回退本次提交并重新安装/保存新容器；已装 Ubuntu 包无需自动删除。没有已登记的 protected path；不修改签名、凭据、CI 或组件发布配置。使用行为测试验证就绪、失败清理和隔离，回归现有用户/认证/准备流程，设备执行独立服务测试，构建并签名 APK。
