## ADDED Requirements

### Requirement: Native GNOME 安装应交付必需的 D-Bus 激活服务

系统安装Native GNOME时必须显式请求Termux的gvfs包，并检查core服务策略强制要求的ca.desrt.dconf、org.gtk.vfs.Daemon及org.gnome.Nautilus服务文件。包管理命令成功不能代替文件完整性检查。

#### Scenario: 干净设备安装
- **WHEN** 用户通过App安装Native GNOME
- **THEN** 安装命令包含gvfs，三个服务文件齐全后才报告成功。

#### Scenario: 安装命令成功但服务缺失
- **WHEN** apt返回成功但任一必需服务文件缺失
- **THEN** 安装任务以非零状态失败，日志包含缺失服务名，不输出安装完成。

### Requirement: Native GNOME 安装状态应反映必需服务完整性

#### Scenario: 旧安装缺少GVfs
- **WHEN** 主要GNOME程序存在但org.gtk.vfs.Daemon.service缺失
- **THEN** App将桌面判为未安装，沿用安装入口完成修复，不进入注定失败的启动流程。

#### Scenario: 必需服务齐全
- **WHEN** 已有GNOME程序和三个必需服务均存在
- **THEN** App将Native GNOME判为已安装。

#### Scenario: PRoot GNOME
- **WHEN** PRoot GNOME已满足原有宿主及容器标记要求
- **THEN** 宿主GVfs服务文件缺失不改变其就绪状态。
