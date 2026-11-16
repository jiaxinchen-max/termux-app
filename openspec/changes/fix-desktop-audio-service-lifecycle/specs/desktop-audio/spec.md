# 桌面音频运行服务

## ADDED Requirements

### Requirement: 每次运行管理共享音频依赖

每次启动桌面 MUST 检查显式宿主音频端点和 Android sink。可用时 MUST 复用，不依赖可执行文件 PID 识别，不杀进程或再启动。不可用时 MUST 限时停止本 App UID 下身份已确认的旧 PulseAudio，确认退出后启动一次并验证。音频服务生命周期不得被归类为一次性桌面初始化。

#### Scenario: 包更新后现有服务仍健康
- **WHEN** --check 可能误判但 loopback 端点和 Android 输出正常
- **THEN** 保留服务与现有客户端，正常进入桌面。

#### Scenario: 旧服务无法复用
- **WHEN** 端点不可用或只剩 null sink
- **THEN** 先停止已确认的旧音频进程再单次启动，成功后才进入桌面；身份不明或停止/启动失败即退出。

#### Scenario: 运行与保存恢复一致
- **WHEN** Native/PRoot 桌面从 App 启动，或安装、保存、恢复准备音频
- **THEN** 使用同一服务管理流程，不在启动中修改桌面环境、下载组件或重试桌面。
