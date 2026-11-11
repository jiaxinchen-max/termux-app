## ADDED Requirements

### Requirement: 桌面宿主安装不等待配置文件输入

桌面安装及容器恢复宿主依赖补齐MUST使用非交互debconf环境。每处宿主APT/pkg包安装及pkg update触发的升级MUST明确传递dpkg配置冲突策略：优先默认处理，没有默认时保留现有配置，MUST NOT等待用户按键。

#### Scenario: 修改过的OpenSSL配置与新包冲突
- **WHEN** Native XFCE4安装升级OpenSSL且旧openssl.cnf已被用户或脚本修改
- **THEN** 安装自动保留已有配置并继续，日志仍可查看

#### Scenario: 其他宿主安装入口
- **WHEN** 安装Native Plasma/GNOME、PROOT桌面宿主依赖或恢复容器所需宿主依赖
- **THEN** 源仓库包和下载deb安装均采用同样策略

### Requirement: 原包管理失败语义不变

非交互策略MUST NOT吞掉失败、重试、自动修复包状态或替换维护脚本；MUST沿用原日志和退出码/任务结果传播。MUST NOT通过全局APT配置影响用户手动包管理。

#### Scenario: 包管理命令失败
- **WHEN** 包管理器返回非零退出码
- **THEN** 安装立即以原失败码结束，状态标记失败而非成功，不执行后续安装阶段
