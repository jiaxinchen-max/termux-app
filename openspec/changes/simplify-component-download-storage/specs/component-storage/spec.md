# 组件存储

## MODIFIED Requirements

### Requirement: 下载目录唯一且浅层
系统 MUST 仅将组件下载包写到共享存储 Download/Termux/components。

#### Scenario: 下载组件
- **WHEN** 用户触发组件下载
- **THEN** 使用该目录，不复制旧私有下载目录，不读取固定Download/component目录，不回退Android/data

#### Scenario: 目标不可写
- **WHEN** 下载目录无法创建或写入
- **THEN** 停止任务并提示用户授予App存储访问权限后重试

### Requirement: 手动来源不受目录限制
系统 MUST 允许既有手动安装入口读取用户通过系统文件选择器选择的本地包。

#### Scenario: 选择任意位置文件
- **WHEN** 用户选中可读取的支持格式组件包
- **THEN** 按现有安装流程执行，不要求移动到特定下载目录，不扫描整个存储

### Requirement: 备份保存位置固定
系统 MUST 将RootFS和Wine C盘备份保存到共享存储Download/Termux/backups，不提供位置或名称选择。

#### Scenario: 创建备份
- **WHEN** 用户发起备份
- **THEN** 自动生成备份文件名并写入固定目录，不覆盖已有文件；目录不可写则停止并提示授权

#### Scenario: 恢复备份
- **WHEN** 用户发起恢复
- **THEN** 只显示固定目录内对应类型的备份列表，无备份则明确提示，不提供任意目录浏览；实际覆盖前保留确认

#### Scenario: 外部组件安装
- **WHEN** 用户手动安装外部组件
- **THEN** 文件选择器仍允许选择任意可读取位置的支持格式包

### Requirement: App自有文件使用Wine命名
系统 MUST 将App自有game-或games-前缀文件名统一为wine-前缀。

#### Scenario: 创建及读取管理文件
- **WHEN** 生成或读取备份、prefix模板缓存或其他App自有管理文件
- **THEN** 生产和消费端均使用wine-名称，不兼容旧文件名；用户原始文件及第三方组件源文件名不被强制更名，APK固定名称不变
