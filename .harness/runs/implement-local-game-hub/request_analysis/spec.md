# Requirement Analysis

## 完整性

- 目标、非目标和用户可见行为已明确。
- Activity、Service、任务状态、组件下载、X11、输入、存储和回滚边界已设计。
- 本地游戏功能按可独立验证的交付分片拆解。
- 已增加独立 `:local-games` 模块、公开 API、Host adapter 和禁止反向依赖约束。
- 受保护路径已识别，实际修改前仍需确认。
- 产品文档和领域模型影响已声明。

## 关键决策

- 不在 `TermuxActivity` 内继续堆叠游戏 UI。
- 功能实现归属 `:local-games`，主 `app` 只负责依赖、初始化、入口和 Termux Host adapter。
- Activity 不拥有下载和游戏进程。
- 正常流程不创建用户可见终端页。
- 网络能力严格限制为运行组件。
- 保留现有 TermuxBox 路径作为迁移期回退。

## 待确认

- 独立 Activity 名称暂定 `LocalGamesActivity`。
- 总 change 拆成多个实现子 change，避免一次性修改全部运行链。
