## ADDED Requirements

### Requirement: 终端待机页执行所选启动脚本

终端模式的 X11 待机页 MUST 支持选择本地可执行启动脚本并通过按钮运行；按钮 MUST 显示所选启动项名称。

#### Scenario: 单击已选择的脚本
- **WHEN** 用户单击启动按钮且所选文件有效
- **THEN** 以独立终端会话执行该文件一次，路径中的空格和引号不改变参数边界
- **AND** X11 界面继续依据实际服务连接状态显示

#### Scenario: 尚未选择脚本
- **WHEN** 用户单击启动按钮且没有有效的当前选择
- **THEN** 按钮显示中文“启动”、英文 `start`，点击后打开脚本选择入口，不静默执行固定命令

### Requirement: 长按选择和管理启动项

启动按钮 MUST 支持长按打开启动项管理，支持添加、选择、移除及持久化当前选择。

#### Scenario: 长按并取消
- **WHEN** 用户长按按钮后取消管理
- **THEN** 不执行脚本，并保留原选择

#### Scenario: 选择脚本后重建页面
- **WHEN** 用户保存选择并重新创建 Activity
- **THEN** 列表与当前选择恢复，按钮继续显示该项名称，重复读取不增加重复项

#### Scenario: 移除启动项
- **WHEN** 用户移除当前启动项
- **THEN** 只更新配置，不删除对应文件，后续点击不使用越界或陈旧索引

### Requirement: 脚本 session 属于桌面会话

脚本 MUST 由 TermuxService 的独立终端 session 运行，Service MUST 保持该 session 的关联，Activity 重建和界面切换 MUST NOT 丢失关联或终止 session。

#### Scenario: 退出桌面
- **WHEN** 用户确认退出桌面
- **THEN** 终止并回收本入口拥有的脚本 session，同时复用原有 XServer 退出流程，不终止无关终端 session

#### Scenario: 重建 Activity 后退出
- **WHEN** 脚本 session 运行期间 Activity 被重新创建，用户随后退出桌面
- **THEN** Service 仍能终止原有脚本 session，不创建或遗留重复 session

#### Scenario: 脚本自然结束
- **WHEN** 脚本进程结束，包括 Activity 未绑定的情况
- **THEN** 按既有终端会话机制回收 session，不新增脚本诊断、依赖检查或备用命令执行

### Requirement: 终端入口不改变其他运行链路

该功能 MUST 限定在终端模式 X11 待机页，不改变 UI 模式待机页、Wine/PRoot 程序启动或 prefix 恢复。

#### Scenario: 打开 UI 模式待机页
- **WHEN** 用户使用 UI 模式的 X11 会话页面
- **THEN** 页面保持现有待机展示，不出现终端脚本管理按钮
