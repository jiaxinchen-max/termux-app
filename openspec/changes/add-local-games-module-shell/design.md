# 本地游戏模块骨架设计

## 总体方案

```text
app
  -> implementation project(':games')
  -> TermuxLocalGamesHostFactory

games
  -> api/LocalGames
  -> api/LocalGamesHost
  -> api/LocalGamesHostFactory
  -> activity/LocalGamesActivity
  -> termux-shared
```

模块不依赖 `:app`。`LocalGames.install()` 只保存 HostFactory；Activity 首次创建时按 Application Context 生成 Host。Host 不持有 Activity。

## Activity 与导航

- Activity 位于 library manifest，`exported=false`。
- 使用 XML/ViewBinding 和 Material/AppCompat。
- 当前只有 toolbar、runtime 状态和游戏库空状态，为后续 Fragment 导航保留内容容器。

## Service 与任务状态

本分片不创建 Service 或真实任务。公开 Host 只暴露只读 runtime availability，后续执行契约另行扩展并评审。

## 数据、存储与迁移

N/A，本分片不写持久数据。

## 组件下载与安装

N/A。

## X11、输入与运行会话

N/A；本分片不依赖 `termux-x11`，避免空壳阶段引入无使用依赖。

## 受保护路径

- `build-boundary-high`：settings 和 app dependency。
- `android-entry-critical`：TermuxApplication 初始化。
- 不修改 TermuxActivity、TermuxService、Manifest 主文件、运行脚本和 X11。

## 测试策略

- `:games:assembleDebug`。
- `:app:compileDebugJavaWithJavac`。
- 边界脚本检查 module build 无 `project(':app')`，源码无 `com.termux.app`。
- Harness strict-run。

## 风险与回滚

按 proposal 执行。
