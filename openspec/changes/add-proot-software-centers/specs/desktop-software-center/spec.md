## ADDED Requirements

### Requirement: 三个 PRoot 桌面提供 DEB 应用中心
Ubuntu GNOME、XFCE、Hyprland PRoot SHALL 安装 GNOME Software 和 DEB 插件，并准备 AppStream 目录；Plasma SHALL 保留 Discover，Native SHALL 保持现有行为。

#### Scenario: 安装新桌面
- **WHEN** 安装三个目标 PRoot 桌面之一
- **THEN** 在安装阶段完成应用中心依赖、应用目录和服务配置，应用以 termux 运行

### Requirement: 真实容器权限认证
应用中心 SHALL 通过 guest PackageKit 和 PolicyKit 管理 DEB；授权 SHALL 由真实 PAM helper 和用户密码决定。

#### Scenario: 需要管理权限
- **WHEN** 应用中心请求安装或删除软件
- **THEN** 显示普通用户 GTK 密码窗口，正确密码依赖真实授权，错误或取消不绕过认证

### Requirement: 每会话服务与准备分离
普通启动 SHALL 只运行已准备的会话，不执行 APT 更新、包安装或默认配置修复。必要服务失败 SHALL 清理本会话并退出，不重试。认证代理崩溃 SHALL 报错但不终止健康桌面。

#### Scenario: 关闭与重复启动
- **WHEN** 关闭桌面或启动失败
- **THEN** 停止拥有的容器服务和总线，不终止其他桌面或原生服务

#### Scenario: 保存和恢复
- **WHEN** 保存设置或恢复支持的备份
- **THEN** 在该阶段完成新 runtime 对应的服务配置；缺少依赖直接提示，不留到启动修复

### Requirement: GNOME 菜单能够激活软件中心
Ubuntu GNOME PRoot 的 core 会话 SHALL 在准备阶段发布 org.gnome.Software 的会话激活服务，并在该文件缺失时拒绝完成准备。Native SHALL 保持原有激活服务范围。

#### Scenario: 点击软件中心
- **WHEN** 用户在已完成准备的 Ubuntu GNOME PRoot 会话中点击软件中心
- **THEN** 私有会话总线能够找到 org.gnome.Software 的服务描述并执行应用入口

#### Scenario: 激活服务缺失
- **WHEN** 安装、保存或恢复时缺少 org.gnome.Software.service
- **THEN** 准备失败并指出缺失文件，不发布成功配置，不在普通启动补写

### Requirement: Hyprland m3ww 提供容器软件中心入口
Hyprland m3ww PRoot 启动器 SHALL 显示 Software，并通过已有 termux-hyprland-software 包装器启动容器应用。Native SHALL 保持宿主桌面文件发现行为，不加入容器软件中心固定入口。

#### Scenario: 打开 m3ww PRoot 应用列表
- **WHEN** 用户打开 m3ww PRoot 启动器
- **THEN** 列表包含终端、文件管理器和软件中心，软件中心命令精确指向该桌面的容器包装器，不混入宿主桌面应用

#### Scenario: Native 应用发现
- **WHEN** Native 存在可见宿主桌面应用
- **THEN** 继续通过 gio launch 发现并启动该应用，不添加 PRoot 软件中心固定入口
