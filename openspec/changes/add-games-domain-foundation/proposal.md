# Games 领域基础提案

## 背景

`:games` 已具备独立模块和 Activity 空壳，但尚无稳定领域对象与任务边界。下载、导入和启动链若直接绑定 UI，会造成状态无法恢复且后续难以替换实现。

## 目标

- 建立 Game、RuntimeProfile、LaunchTask、ComponentTask、SaveSnapshot 不可变模型。
- 为任务状态和阶段提供显式枚举、合法转换与输入校验。
- 定义游戏、配置、启动任务、组件任务、快照 Repository 契约。
- 定义 Activity 可调用、后端可替换的 LocalGameOrchestrator 契约和观察订阅。
- 提供纯 JVM 单元测试，不依赖 Android Context 或真实终端。

## 非目标

- 不实现数据库、SAF、组件网络下载、Wine/X11 或 Service。
- 不修改主 app、TermuxBox、TermuxService 和现有运行脚本。
- 不新增第三方依赖。

## 用户可见行为

无新增页面行为；该分片为后续游戏库、组件任务和启动任务提供稳定状态契约。

## 影响范围

- `games/src/main/java/com/termux/localgames/domain/**`
- `games/src/main/java/com/termux/localgames/data/**`
- `games/src/main/java/com/termux/localgames/runtime/**`
- `games/src/test/**`

## 风险与回滚

- 风险：模型过早绑定 Android 或具体存储；全部类型保持纯 Java，URI 使用字符串表示。
- 风险：状态转换含糊；由模型方法集中校验终态和进度不变量。
- 回滚：删除新增包即可，不影响现有模块入口。
