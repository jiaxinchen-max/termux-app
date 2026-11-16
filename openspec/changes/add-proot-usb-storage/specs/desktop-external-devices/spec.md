# 桌面外接设备（已批准，实施中）

## ADDED Requirements

### Requirement: 四类 PRoot 的只读 USB 存储入口

系统 MUST 为当前 Ubuntu 26.04 的 XFCE、Plasma、Ubuntu GNOME、Hyprland PRoot 提供可明确启用的只读 USB 存储能力。范围限 Android USB Host 可枚举和授权、现有桥接可读取的 BOT/SCSI FAT/exFAT 卷，不表示通用 USB 透传。

#### Scenario: 浏览与复制
- **WHEN** 用户已启用只读 USB 且连接兼容设备并完成授权
- **THEN** XFCE/Hyprland 可在 Thunar、Ubuntu GNOME 可在 Files、Plasma 可在专门 USB 文件入口的 Thunar 浏览并复制文件到容器；写回设备被后端拒绝。

#### Scenario: 设备不兼容或被占用
- **WHEN** 设备不能由 Android USB Host 打开，或不是支持的存储协议/文件系统
- **THEN** 显示设备无法使用及具体可得错误，不报告挂载成功，不创建虚假的块设备或空卷。

### Requirement: 用户授权与会话凭据

系统 MUST 复用主 APK 的 Android USB 权限机制，并将卷服务限定为现有鉴权桥接；不依赖额外 API APK，不绕过授权，不暴露口令。

#### Scenario: 待授权或拒绝
- **WHEN** 新设备需要授权
- **THEN** 显示待授权；用户在前台 App 请求时才弹出 Android 系统授权；拒绝或超时保持失败状态，不声称文件卷已连接，且不阻塞桌面显示。

#### Scenario: 客户端使用凭据
- **WHEN** guest GIO 连接已有只读文件服务
- **THEN** 通过正确映射的私有口令文件完成鉴权，口令不进入可见 URI、参数、普通日志或导出归档；未授权客户端的读取失败。

### Requirement: 保存期准备和宿主容器隔离

系统 MUST 在安装/保存阶段完成静态配置及依赖准备，guest 加载匹配 glibc 的组件，保留宿主 PREFIX 占位目录的隔离权限。

#### Scenario: 启用或保存失败
- **WHEN** 停止桌面后启用 USB，并且依赖或配置准备失败
- **THEN** 任务明确失败，不把无效配置发布为可用，不损害此前可启动的配置。

#### Scenario: 普通启动
- **WHEN** 用户启动已准备好的桌面
- **THEN** 仅创建会话 IPC 和启动所属运行服务；不安装组件、修配置或做硬件/版本预检，必要服务启动失败直接退出；USB 关闭时不运行桥接。

#### Scenario: guest 路径与总线
- **WHEN** 四类 PRoot 的文件管理器访问 USB 卷
- **THEN** 文件管理器与 GIO helper 使用同一正确 guest session bus、guest 可见 IPC/口令路径和 glibc 模块；不会向 rootfs 内 000 权限的 host PREFIX 目录创建子项，不修改该权限。

### Requirement: 设备和桌面生命周期

系统 MUST 区分无设备、未授权、已连接、拔出及枚举失败，按拥有关系释放本会话资源。

#### Scenario: 空设备和热拔出
- **WHEN** 启动时没有 USB 设备，或运行期间设备拔出
- **THEN** 前者正常显示桌面且准确呈现空设备，后者移除对应卷并释放资源；一次枚举失败不等同于设备全拔出。

#### Scenario: 正常停止和服务失败
- **WHEN** 用户停止桌面或必要 USB 服务异常退出
- **THEN** 正常停止先断开 GIO 卷再结束文件服务/broker，最后结束 session bus；异常遵循失败退出并清理本会话，不自动修复重启，不终止其他桌面的服务，不把清理错误报告为成功。

#### Scenario: 导出当前容器
- **WHEN** USB 配置仍开启且用户请求导出
- **THEN** 继续准确提示先关闭 USB 的现有条件；关闭并保存后沿用四类容器的已有导出能力，不归档设备权限或运行资源。

### Requirement: 显式可写 USB 卷

四类受支持 PRoot MUST 提供 read-write，与 off/read-only 分别持久化。Native MUST 拒绝 read-write；配置准备仍在保存阶段完成。

#### Scenario: 手动接管设备
- **WHEN** 用户选择读写并授权 USB
- **THEN** 设备保持未挂载；用户手动连接时检查 Android 可移动卷均已卸载，否则拒绝并显示原因；不能在桌面启动前等待设备。

#### Scenario: 写操作与持久化
- **WHEN** 已接管兼容的 FAT/exFAT USB 卷
- **THEN** 文件管理器可新建、复制、重命名和删除，正常卸载后重新连接可读取相同内容；只读模式继续拒绝写入。

#### Scenario: 非正常释放
- **WHEN** GIO 卷忙、刷盘失败、文件服务异常或写入中拔出
- **THEN** 显示真实失败，不能声称安全弹出；刷盘/服务异常必须重插设备才能重新接管。
