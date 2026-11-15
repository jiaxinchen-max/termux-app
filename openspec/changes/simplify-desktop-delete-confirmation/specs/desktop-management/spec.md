# 桌面删除确认规格

## MODIFIED Requirements

### Requirement: 明确的单次删除确认
系统 SHALL 对 PRoot 桌面展示一次确认，明确将删除桌面及指定容器内全部数据、操作不可恢复、已导出备份保留，且无额外删除资料勾选项。

#### Scenario: 确认删除容器桌面
- **WHEN** 用户确认删除 PRoot 桌面
- **THEN** 使用现有停止及容器删除任务，不再提供仅移除入口的默认分支。

#### Scenario: 取消删除
- **WHEN** 用户取消对话框
- **THEN** 不提交任何删除任务。

#### Scenario: 删除 native 桌面
- **WHEN** 用户删除 native 桌面
- **THEN** 提示只删除桌面配置和会话状态，保留宿主已安装软件和文件，继续使用原有配置删除路径。
