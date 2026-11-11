## ADDED Requirements

### Requirement: 重建依赖有可见终端

用户确认重建依赖后MUST在独立普通终端session中执行安装，并自动关闭偏好设置抽屉、显示该session。安装输出MUST实时呈现，MUST NOT作为不可见后台任务提交。

#### Scenario: 安装成功
- **WHEN** 安装脚本返回0
- **THEN** session显示成功并回到shell提示符，保留安装输出供用户查看

#### Scenario: 安装失败
- **WHEN** 安装脚本返回非零
- **THEN** session显示失败及原退出码，保留错误输出，不自动重试或关闭

#### Scenario: 自动关闭工具箱session设置已开启
- **WHEN** 用户执行重建依赖
- **THEN** 安装session仍使用普通交互会话，不因该设置而自动关闭

#### Scenario: 参数及脚本文本包含特殊字符
- **WHEN** 安装路径或本地化提示包含空格、引号或shell语法字符
- **THEN** 作为完整参数执行或显示，不发生拆分或命令注入

### Requirement: 沿用安装和终端规则

本改动MUST沿用当前包和APT安装规则、session数量上限与Service管理，不改变程序模式首次补齐流程或其他session。
