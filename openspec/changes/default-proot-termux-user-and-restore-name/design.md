# 设计

## 账户与权限
新容器安装依赖完成后，以容器 root 创建 termux 普通账户，家目录 /home/termux、登录 shell /bin/bash、初始密码 termux。用户配置、缓存、主题及默认桌面文件写入该家目录，创建必要用户目录并核对账户为非零 UID。通过发行版受支持的 sudo 与认证机制提供用户可用的管理入口；安装任务显式使用管理身份，不能依赖会话是 root。

桌面登录显式选择 termux：proot-distro 入口使用 --user termux；直接调用 proot 的 GNOME 路径显式传入从账户取得的普通 UID/GID，拒绝缺失账户。统一 HOME/USER/LOGNAME/工作目录和 XDG 路径，不遗留 /root。宿主合成器仍由宿主提供，容器普通用户应可访问正确的显示和音频端点；不将宿主 D-Bus 当成容器总线。

## Plasma 服务边界
现有 desktop_container_session.sh 同时管理系统总线、会话总线、PolicyKit、PackageKit、KDE 服务和 plasmashell，不能简单把整段改为普通身份。启动器保留容器管理服务监督入口，以容器 root 启动需要权限的系统服务，再以 termux 启动独立会话总线和 KDE/应用；明确传递容器系统总线地址并限制运行时目录访问。使用容器管理端 root 代理调用 PolicyKit 原生 UnixProcess 注册接口，按私有系统总线返回的 UID/PID 仅选择 termux 客户端，密码窗口通过 runuser 切为 termux。实机发现普通代理注册时会遇到 Android /proc 物理 UID 与 guest UID 不一致；PolicyKit 明确允许 root 管理端为其他用户注册，借此保留正常的权限与 PAM 验证，而不伪造 /proc 数据或放开授权。管理代理通过 stdin 将认证请求交给新的 termux 工作进程，该进程显示密码窗口并通过 PolkitAgent.Session 交给发行版 PAM helper 验证；避免 PolicyKit 127 将 cookie 绑定到 subject UID 而 root helper 响应 UID 不符。标准 /run/dbus/system_bus_socket 只链接当前容器私有总线，拒绝覆盖其他端点，退出仅清理自己的链接。账户准备恢复发行版 helper 的 root:root/4755 权限（仅 PRoot 内模拟），保持正常 PAM 验证。，不修改授权规则、不伪造 logind 会话或认证成功。KDE 自带代理使用 UnixSession，不能直接设为此环境的必需服务。验证 D-Bus 凭据、PackageKit 授权、PolicyKit 认证代理、sudo 密码和服务退出清理；不以放开所有授权规则代替身份修复。GNOME、XFCE、Hyprland 客户端采用相同普通会话身份原则。

## 信息提示
在桌面信息/设置详情的容器区显示“默认用户：termux；初始密码：termux”，同时说明可在容器终端使用 passwd 修改密码、sudo 执行管理操作。不在 Native 桌面显示该提示，也不将初始密码声称为用户修改后的当前密码。更新现有语言资源。

## 名称恢复
DesktopArchiveMetadata.encode 已保存 desktop.name，DesktopArchiveEngine.importArchive 已构造同名桌面，因此保留格式和现有完整性校验。将清单发布前的最终名称选择放入 DesktopOperationGate.LOCK 下，读取最新清单：原名空闲则原样；占用则依次选择“原名 (1)”“原名 (2)”等最小可用后缀。按应用现有名称规范判定重复，保留合法中文、空格及转义字符；ID 和容器 alias 继续使用新身份，不能把展示名称用于文件路径。桌面卡片读取保存的展示名称，避免名称已恢复却仍显示生成的发行版编号。DomainValidation 对非空名称没有长度上限，不新增截断。只用 withName 修改发布记录，后续设置恢复使用相同 ID；失败清理沿用原事务身份。核查名称长度约束，并为后缀留出空间。

## 风险、回滚与验证
账户/服务边界涉及所有 PRoot 会话，需要新容器验证；旧数据兼容不在范围内，不删除或迁移现有设备容器。回滚代码后用旧版本流程新建容器，原备份和容器保留，禁止为回滚清空 App 数据。测试前 force-stop App，从干净进程启动。
验证账号非 root、密码验证、家目录与配置归属、显示/音频端点和 D-Bus；VLC 必须真实显示窗口并打开媒体，不能只用 --version 代替。Discover 需实际完成查询与一次安装授权。测试启动失败/停止后的监督服务清理，以及各桌面入口的普通身份。备份做无重名、连续重名、后缀有空洞、Unicode 名称、完整导出恢复和失败回滚。

## 工具边界
当前仓库缺少要求的 .harness 指令/strict gate 脚本、OpenSpec CLI、openspec/specs 和 docs/product 基线目录；本次依据源码手工编写方案，不宣称严格流程门禁通过。

## 授权崩溃修复
实机日志显示管理代理退出 139 导致 manager 全量清理。移除 Python PolkitAgent.Listener/GTask 虚函数跨 C 回调，使用 Gio D-Bus 明确实现认证代理接口；普通用户工作进程直接驱动 PolkitAgent.Session。管理端只接收真实 PolicyKit daemon 请求，保留 PAM/cookie 校验。授权代理单独监督，有界重启，失败不关闭普通用户桌面；不降低授权规则。
