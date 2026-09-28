# Games 游戏级运行配置提案

## 背景

游戏库已经能导入和维护本地游戏，但尚未为每个 Game 保存可冻结的运行配置，也无法在启动前判断目录权限、Termux 运行时、组件和存储是否满足要求。现有 TermuxBox container 已包含可复用配置，但 Games module 不应直接依赖 app 私有实现或隐式改写旧配置。

## 目标

- 为每个 Game 持久化独立 RuntimeProfile，支持 schema 校验、原子发布和上次成功配置快照。
- 通过 `LocalGamesHost` 只读获取 TermuxBox container 配置并显式导入为 RuntimeProfile，不修改源 container。
- 提供推荐、稳定、兼容、自定义四类预设、字段级变更摘要、恢复默认和恢复上次成功配置。
- 在启动前统一预检 SAF 权限、Termux runtime、组件依赖/版本和私有存储预算。
- 在游戏详情中提供独立运行配置 Activity，明确展示保存配置和实时预检结果。

## 非目标

- 不启动 Wine，不创建 LaunchTask，不修改 TermuxService、启动脚本或 X11。
- 不迁移、删除或写回 TermuxBox container.conf/prefix。
- 不自动下载缺失组件；预检仅给出阻断项，组件页负责下载。
- 不做设备 GPU 自动识别；推荐预设使用可解释的固定基线。

## 用户可见行为

用户从游戏详情进入“运行配置”，可选择预设、编辑关键字段、导入已有 TermuxBox 配置并查看变更摘要。页面持续展示目录权限、运行时、缺失组件和可用空间；阻断项未解决前明确标记为“尚不可启动”。

## 影响范围

修改 `:games` 的 API、domain、data、runtime、Activity/资源/测试，以及 app 内 Games Host adapter。新增模块私有 Activity，不改变主入口和既有 TermuxBox UI。

## 风险与回滚

- 旧配置兼容：Host 仅返回不可变 snapshot，映射失败跳过单条并展示错误，不写旧文件。
- 配置损坏：properties 采用严格键集合、同目录临时文件和原子 rename；损坏记录阻止覆盖。
- 空间误判：预算采用保守值并在结果中展示所需/可用字节；最终启动阶段仍需再次预检。
- 回滚：移除新增 Activity/API/Repository/预检代码和私有 profile 文件即可，Game、TermuxBox container 和用户目录不变。
