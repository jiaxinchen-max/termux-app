# Games runtime preflight delta

## Requirements

### Requirement: 启动预检必须反映真实 TermuxBox 运行环境

系统 MUST 使用启动脚本实际消费的 TermuxBox 运行时判断组件是否可用，并兼容当前与旧版包管理元数据目录。

#### Scenario: 已有 TermuxBox 环境

- GIVEN 用户已安装启动所需 TermuxBox 组件
- WHEN Games 启动导入的游戏
- THEN 不得仅因缺少 Games 私有组件回执而报告 `COMPONENT_MISSING`

#### Scenario: 元数据陈旧或能力文件缺失

- GIVEN 组件只有版本元数据或只有能力文件
- WHEN 执行预检
- THEN 组件仍视为不可用

### Requirement: 预检诊断必须可操作

系统 MUST 在持久失败码中包含首个失败项 subject，并在启动页提供可读错误和可恢复任务重试入口。

### Requirement: 通过预检后的会话必须稳定连接显示

系统 MUST 使用启动脚本的 canonical 私有目录定位运行文件；Games X11 会话 MUST 在 Surface attach 后接收连接状态，并且不得被全局 X11 方向偏好覆盖自身方向策略。

#### Scenario: 私有目录存在 Android 别名

- GIVEN Host 传入 `/data/user/0/<package>/files` 下的启动脚本
- WHEN 脚本启动游戏
- THEN 不得因内部硬编码 `/data/data/<package>/files` 而报告 `launch_private_path_required`

#### Scenario: X11 首次连接时发生方向配置变化

- GIVEN Games 会话正在建立 X11 连接
- WHEN 设备应用横屏配置
- THEN Activity 原位处理配置变化且连接监听持续报告真实状态
