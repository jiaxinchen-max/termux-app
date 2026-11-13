# GNOME 就绪检查

## ADDED Requirements

### Requirement: 瞬时探测超时不提前终止有效的就绪窗口
GNOME 启动器 SHALL 在既有总期限内重试单次超时的就绪查询，且保留真实失败语义。

#### Scenario: 单次查询超时后恢复
- **WHEN** Shell 仍存活，首次查询超时，后续查询在总期限内确认正常
- **THEN** 桌面就绪并可操作，不因首次查询超时退出

#### Scenario: 持续超时或进程死亡
- **WHEN** 总期限耗尽，或必须的 Shell/服务进程退出
- **THEN** 启动明确失败并清理本次进程，不生成有效 ready 标记

#### Scenario: 扩展明确失败
- **WHEN** 扩展报告带实际错误的失败状态
- **THEN** 启动失败并保留错误，不将其作为瞬时超时忽略
