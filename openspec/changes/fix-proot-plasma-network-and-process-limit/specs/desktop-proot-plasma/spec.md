# Proot Plasma 修复规范

## ADDED Requirements

### Requirement: Ubuntu 官方源使用 HTTPS
Ubuntu 容器安装桌面时 SHALL 在首次 APT 刷新前将 archive.ubuntu.com/ubuntu、security.ubuntu.com/ubuntu、ports.ubuntu.com/ubuntu-ports 的 HTTP URI 改为 HTTPS，保留所有 suites/components/Signed-By 与包签名验证，并可回滚。

#### Scenario: 官方源与第三方源混合
- **WHEN** Ubuntu 同时含 Deb822/传统官方 HTTP 源与第三方源
- **THEN** 仅官方 URI 改为 HTTPS，首次修改有备份，重复执行不改变备份；非 Ubuntu 不改变源。

### Requirement: 容器不激活无硬件能力的系统服务
Proot Plasma 私有系统总线 SHALL 只激活真实的 PackageKit 和 PolicyKit 服务，不提供虚假的 UPower、调制解调器、固件更新或块设备接口。

#### Scenario: Discover 请求硬件后端
- **WHEN** Discover 或 Plasma 请求容器不具备的硬件系统服务
- **THEN** 服务不存在及时返回错误，不生成对应守护进程；APT PackageKit 和 PolicyKit 仍真实响应。

### Requirement: 已授权模拟器解除幻影子进程裁剪
修复过程 SHALL 保存设备原设置、读回有效状态并验证正常 App 冷启动；不声称普通 App 可以自动更改系统级限制。

#### Scenario: 桌面与 Discover 持续运行
- **WHEN** 在该模拟器关闭监控后由正常 UI 启动桌面并加载 Discover
- **THEN** 验证 Plasma 与宿主合成器存活，软件源刷新成功，超过已知退出窗口无新的 Trimming phantom processes。
