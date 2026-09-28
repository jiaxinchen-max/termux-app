# Games SAF 游戏导入设计

## 总体方案

```text
LocalGamesActivity
  -> GameImportActivity
       -> SafPermissionManager
       -> SafGameDocumentTree
            -> GameDirectoryScanner
                 -> PeExecutableInspector
                 -> ExecutableCandidateRanker
       -> FileGameRepository
```

Android SAF 适配与纯 Java 扫描/排序分离。Scanner 只依赖 `GameDocumentTree`，JVM 测试使用内存目录树。

## Activity 与导航

- 游戏库添加按钮打开模块私有 `GameImportActivity`。
- 导入页通过显式 `ACTION_OPEN_DOCUMENT_TREE` 请求可持久读权限，不请求全盘存储权限。
- 选中目录后后台扫描；Activity recreate 保存 tree URI，验证持久权限后重新扫描，不保存不可序列化 Provider 对象。
- 候选使用单选列表，用户可编辑游戏名、相对工作目录和启动参数；无候选、权限失效或扫描失败时禁止确认并提供重选。

## Service 与任务状态

N/A。目录扫描是 Activity 范围内的可取消只读任务，不创建前台 Service；离开页面即停止当前扫描，已持久 URI 权限保留。

## 数据、存储与迁移

- `GameScanLimits` 默认限制深度 8、文档 20000、目录 2000、PE 候选 256、目录声明大小 1 TiB。
- `GameScanResult` 保存计数、限制命中集合和已排序候选。
- `FileGameRepository` 在 `files/games/library` 使用每游戏一个 properties 文件和同目录临时文件原子替换；参数逐项编码。
- Game 中 `rootUri` 保存 tree URI，`executable`/`workingDirectory` 保存相对路径，不派生不稳定的真实文件路径。

## 组件下载与安装

N/A。

## X11、输入与运行会话

N/A。

## 受保护路径

- 不修改 `.harness` 脚本/策略、主 app manifest、TermuxService、脚本、X11、native 或 build 文件。
- `games/src/main/AndroidManifest.xml` 属于功能模块声明，不命中 `android-entry-critical`。

## 测试策略

- JVM：PE 校验、排序/排除词、大小写扩展名、扫描深度/文档/目录/大小/候选限制、Provider 失败、Repository round-trip/损坏数据/原子写。
- AndroidTest：SAF picker Intent action/flags、导入 Activity 私有声明、Activity recreate 后保留待扫描 tree URI 的恢复路径。
- 回归：Games 全量 JVM、AndroidTest compile、lint、assemble、app compile、module boundary。

## 风险与回滚

按 proposal 执行。扫描结果不写用户目录；持久化失败不产生已导入 Game。
