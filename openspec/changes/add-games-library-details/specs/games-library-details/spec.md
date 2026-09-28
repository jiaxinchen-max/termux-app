## ADDED Requirements

### Requirement: 游戏库展示全部持久 Game 和实时权限状态

系统 MUST 从私有 Game Repository 加载游戏库，并在后台探测每个 rootUri 的当前访问状态。

#### Scenario: 已导入游戏权限丢失

- **WHEN** persisted read permission 不再存在
- **THEN** 游戏仍保留并显示在库中
- **AND** 卡片和详情标记权限丢失
- **AND** 用户可以进入重新授权流程

### Requirement: 用户可以管理本地私有封面

系统 MUST 支持选择本地图片作为封面，限制输入大小和像素边界，并复制到 app 私有存储。

#### Scenario: 替换封面时保存失败

- **WHEN** 新封面已 staged 但 Game 记录保存失败
- **THEN** 系统恢复旧封面
- **AND** 不把失败操作显示为成功

### Requirement: 用户可以查看和编辑游戏详情

系统 MUST 通过独立详情 Activity 展示主程序、目录权限、工作目录、参数和封面，并允许保存可编辑字段。

#### Scenario: Activity 重建

- **WHEN** 详情 Activity 在加载或编辑后重建
- **THEN** 系统通过 game-id 重载持久 Game
- **AND** 不创建重复 Game 或修改用户目录

### Requirement: 删除游戏只删除应用私有记录

系统 MUST 在二次确认后删除 Game properties 和私有封面，不得删除、移动或修改 SAF tree 中的任何文件。

#### Scenario: 用户确认删除

- **WHEN** 用户确认从游戏库删除
- **THEN** Game 不再出现在游戏库
- **AND** 用户原始游戏目录保持不变
