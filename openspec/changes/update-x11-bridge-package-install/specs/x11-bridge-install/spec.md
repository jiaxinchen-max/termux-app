## ADDED Requirements

### Requirement: 最新候选的键盘依赖

桥接包MUST声明无固定版本的xkeyboard-config依赖，APK MUST NOT内嵌其deb；安装时MUST通过APT选择已配置软件源的最新候选版本，包括更新已装旧版。

#### Scenario: 安装或更新依赖
- **WHEN** 更新索引成功后安装桥接包
- **THEN** APT解析并安装仓库候选键盘配置，已有旧版也被更新
- **AND** 缺少x11-repo时自动安装并刷新X11软件源后再解析桥接依赖

### Requirement: 强制安装指定本地桥接包

安装脚本MUST先更新索引，再重装APK携带的1.04.10-0桥接包，同版或设备版本较高也MUST执行安装，不预先卸载。

#### Scenario: 首次安装、同版、升级或降级
- **WHEN** 用户重建依赖，设备未安装或已有任意版本桥接包
- **THEN** 安装本次携带的1.04.10-0，同版重装，较高版本允许降级

#### Scenario: 安装阶段失败
- **WHEN** 索引更新或APT安装失败
- **THEN** 立即终止并返回非零；更新失败不继续安装，清理本次暂存包

### Requirement: 两个入口使用同一产物

终端重建和程序首次安装MUST引用统一脚本与新版deb。程序安装MUST等待进程自然结束，MUST NOT因为新增网络步骤使用固定两分钟截止时间；既有已安装判断MUST NOT改成版本自动升级。

#### Scenario: 生成和集成
- **WHEN** 运行生成脚本并构建APK
- **THEN** APK包含1.04.10-0桥接deb，安装器和包内脚本具有正确权限，旧桥接包、键盘deb和未使用的Pacman包不内嵌
