# 本地游戏模块骨架提案

## 背景

总 change `implement-local-game-hub` 已确认使用独立 `:games` Android Library。首个实现分片只建立稳定模块边界和可启动 Activity 空壳，避免直接把下载、Wine/X11 和复杂 UI 同时迁入。

## 目标

- 新增可独立编译的 `:games` Android Library。
- 模块内声明 `LocalGamesActivity`、主题和基础空状态页面。
- 模块公开 `LocalGames`、`LocalGamesHost`、`LocalGamesHostFactory` 三个最小集成 API。
- 主 app 通过 Gradle dependency 和 `TermuxApplication` HostFactory 初始化完成单向集成。
- 建立禁止模块依赖 `:app` 或引用 `com.termux.app.*` 的边界脚本。

## 非目标

- 不实现游戏导入、组件下载、持久化、Wine 启动和 X11 会话。
- 不迁移或删除现有 TermuxBox 代码。
- 不修改当前 TermuxActivity/TermuxBox 的用户界面入口。
- 不引入 Kotlin、Compose、Room 或新的第三方依赖。

## 用户可见行为

- 模块 Activity 可由主应用内部 Intent 创建并显示。
- 页面显示本地游戏标题、空游戏库和 Termux runtime 集成状态。

## 影响范围

- `settings.gradle`
- `app/build.gradle`
- `app/src/main/java/com/termux/app/TermuxApplication.java`
- `app/src/main/java/com/termux/app/localgames/TermuxLocalGamesHostFactory.java`
- `games/**`

## 风险与回滚

- 风险：模块依赖方向错误造成 Gradle 循环；通过静态边界脚本阻断。
- 风险：Application 初始化影响主进程启动；HostFactory 构造保持无 I/O。
- 回滚：移除 settings include、app dependency 和 Application 初始化即可。
