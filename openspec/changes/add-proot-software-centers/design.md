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

## XFCE 安装权限回归修正
设备安装日志确认依赖安装成功，最终准备失败于 rootfs 内的宿主 PREFIX 占位目录；该目录由 proot-distro 设为 000，必须保留。XFCE PRoot 改为将宿主私有 IPC 目录绑定到容器 /tmp/tusb-<id>，持久配置绑定到 /home/termux/.local/share/termux-xfce-<id>。保存时写入 guest 地址，宿主监督器保留 host 地址，USB 的 guest GIO 使用 guest 会话总线。不得 chmod 隔离占位目录，不得在启动阶段补写配置。此修正属于已批准任务 2/3 的正确性修复，不增加能力或修改 Native。

## GNOME Software 会话激活修正
Ubuntu GNOME PRoot 在安装、保存或恢复生成 core 会话配置时，将 org.gnome.Software 加入必需会话服务；文件缺失即准备失败，不发布 prepared-session.json。仅复制明确列出的服务，不开放标准服务目录或系统总线自动激活。Native 不新增此服务。提取原有 core 会话配置准备逻辑供行为回归调用；真实私有 D-Bus 验证可激活服务及受控服务执行，缺文件和 Native 隔离均受测试覆盖。已有配置需在更新运行资产后重新保存并重启桌面，不在普通启动迁移。回退代码后重新保存配置即可，软件包和用户数据无需删除。本轮用户授权限于修复与本地验证，不继承旧记录的推送授权。

## Hyprland m3ww 软件中心菜单修正
在 desktop_hyprland_theme.py 的 apps 中，仅 proot 分支增加 Software，命令指向 work/bin/termux-hyprland-software。复用已有准备阶段生成的容器包装器，保留用户身份、会话环境和服务监督；不直接在宿主执行 gnome-software。Native 仍扫描宿主 .desktop 文件，不新增容器入口。测试覆盖 PRoot 完整菜单、精确包装器路径、宿主应用隔离和 Native 自动发现。现有确定性主题构建工具生成测试夹具，核对生成内容未发生变化。回退仅移除此菜单项；不涉及服务、认证、签名配置或容器数据。
