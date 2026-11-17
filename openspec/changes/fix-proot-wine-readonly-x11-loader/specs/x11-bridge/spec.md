## MODIFIED Requirements

### Requirement: 动态加载器只读
系统 MUST 在 Android app_process 加载桥接 loader.apk 前移除其所有写权限，兼容 Android 14 及以上的动态代码安全要求。不得关闭平台校验或替换为未校验代码。

#### Scenario: 新安装或重装桥接包
- **WHEN** APT 或 Pacman 安装生成的桥接包
- **THEN** 包内 loader 保留 0755 以支持非 root dpkg 安全同步，安装完成钩子将两个 loader.apk 设为不可写，终端启动脚本仍可执行，原文件名、包名、版本及依赖保持兼容。

#### Scenario: 已安装旧桥接包后启动 Wine
- **WHEN** Games 启动准备发现现有有效桥接但 loader.apk 为 0755
- **THEN** 自动移除写权限并保留文件内容，无需下载重装或重建 Wine prefix。

#### Scenario: 不能修复加载器权限
- **WHEN** 文件系统拒绝移除加载器写权限
- **THEN** 在启动准备阶段明确报错，不继续等待注定失败的 X server socket。

#### Scenario: 既有后端及配置
- **WHEN** 完成权限修复后重新启动 PRoot Wine
- **THEN** 仍使用用户已选后端，原容器、prefix、渲染与桥接通信协议保持原样。

#### Scenario: 非 root 首次安装及重装
- **WHEN** 普通 UID 的 dpkg 在开启安全同步时解包，或替换已只读的旧 loader
- **THEN** 解包成功，不因 .dpkg-new 权限失败，且不禁用同步。
