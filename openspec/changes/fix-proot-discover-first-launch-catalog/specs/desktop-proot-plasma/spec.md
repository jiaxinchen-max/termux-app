## ADDED Requirements

### Requirement: Ubuntu Plasma 安装完成前准备应用目录
系统 SHALL 在 Ubuntu PRoot Plasma 桌面依赖安装后、声明安装成功前，刷新官方可信软件源并建立发行版 AppStream 应用目录；普通启动 SHALL 不重复执行此网络准备。

#### Scenario: 全新底包首次打开 Discover
- **WHEN** 最小 Ubuntu 底包首次完成 Plasma 安装
- **THEN** 系统先完成 AppStream 索引下载与目录建立，再初始化并标记桌面安装成功
- **AND** 首次干净会话中的 Discover 能通过真实 PackageKit 显示可用应用，无需关闭重开来加载初始目录

#### Scenario: 索引或目录准备失败
- **WHEN** APT 索引刷新或 AppStream 目录命令失败
- **THEN** 返回可见的实际错误，安装失败且不写成功标记
- **AND** 不关闭 TLS/签名验证，不用空列表冒充联网成功

#### Scenario: 其他桌面与重复启动
- **WHEN** 使用 native、GNOME、XFCE、Debian 或启动已安装的桌面
- **THEN** 不加入该 Ubuntu Plasma 安装后网络准备步骤
