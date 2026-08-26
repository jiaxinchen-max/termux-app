# 本地游戏中心设计

## 总体方案

采用“独立 feature module + 独立前台 Activity + 持久任务编排 + 主应用 Termux 执行桥接”结构：

```text
:app
  -> implementation project(':local-games')
  -> TermuxLocalGamesHost implements LocalGamesHost
      -> TermuxService / existing runtime

:local-games
  -> LocalGamesActivity
  -> Library / Detail / Import / Settings / Session UI
  -> LocalGameRepository
  -> LocalGameOrchestratorService
      -> ComponentDownloadManager
      -> LocalGamesHost interface
  -> project(':termux-shared')
  -> project(':termux-x11')
```

`:local-games` 拥有完整产品能力，但不引用 `:app`。Activity 只提交命令和观察状态，不持有游戏进程、下载线程或终端 Session。任务由 Service 持有，持久化层保存游戏、配置、组件任务和运行任务。主应用通过模块公开的 `LocalGamesHost` 提供 TermuxService 执行、应用级通知和必要权限能力。

## Gradle 模块边界

新增模块：

```text
local-games/
├── build.gradle
├── consumer-rules.pro
└── src/
    ├── main/AndroidManifest.xml
    ├── main/java/com/termux/localgames/
    │   ├── api/          # 主 app 可见的稳定集成面
    │   ├── activity/     # LocalGamesActivity
    │   ├── domain/       # Game、Profile、Task、Snapshot
    │   ├── data/         # 本地持久化与文件索引
    │   ├── components/   # 下载、校验、安装、版本切换
    │   ├── runtime/      # Orchestrator、状态机、事件
    │   └── ui/           # Fragment/View/Adapter
    ├── main/res/
    ├── test/
    └── androidTest/
```

依赖方向：

```text
app -> local-games -> termux-shared
                  -> termux-x11
                  -> AndroidX/Material
```

约束：

- `:local-games` 不得依赖 `:app`，不得 import `com.termux.app.*`。
- 模块公开 API 限定在 `com.termux.localgames.api`，其他包不作为集成契约。
- TermuxService、TermuxApplication 和既有 app 内客户端不移动到 feature module。
- 如果模块需要新的跨功能稳定契约，优先放入 `termux-shared`，不得通过反射访问 app 私有实现。
- library manifest 声明 `LocalGamesActivity` 和模块私有 Service；资源统一使用 `local_games_` 前缀。
- 新模块保持 Java/XML/ViewBinding；引入 Kotlin/Compose 另开 change。

## 主应用集成面

模块提供：

```java
LocalGames.install(LocalGamesHostFactory factory);
Intent intent = LocalGames.createLaunchIntent(context);
```

`LocalGamesHost` 至少包含：

- 创建和连接 Termux 执行会话。
- 查询 Termux prefix、运行目录和应用级配置。
- 申请/检查主应用权限。
- 暴露日志、通知和进程终止能力。

主应用只做三类修改：

1. `settings.gradle` 与 `app/build.gradle` 引入模块。
2. `TermuxApplication` 初始化 `LocalGamesHostFactory`。
3. 原入口调用 `LocalGames.createLaunchIntent()`。

HostFactory 只持有 Application Context，不持有 Activity；应用进程恢复时必须重新完成初始化。

## Activity 与导航

- `LocalGamesActivity` 位于 `:local-games`，使用模块内独立 DayNight NoActionBar theme。
- 一级页面：游戏库、组件、设置。
- 二级页面：游戏详情、导入、运行配置、任务详情。
- 进入运行态时切换为横屏 Game Session 容器，挂载 `TermuxScreenView/LorieView`；退出后恢复原导航状态。
- Activity 重建时使用 task-id 恢复当前下载或运行会话，不重新启动任务。
- `TermuxActivity` 只调用模块公开入口，不承载新页面和业务状态。

## 领域模型

```text
Game
  id, name, rootUri, executable, workingDirectory, arguments, artwork, lastPlayed

RuntimeProfile
  winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
  box64Preset, env, inputProfileId, componentVersions

LaunchTask
  taskId, gameId, state, stage, progress, startedAt, pid, exitCode,
  errorCode, recoverable, logRef, environmentFingerprint

ComponentTask
  taskId, packageName, version, url, expectedSize, sha256,
  downloadedBytes, etag, lastModified, state

SaveSnapshot
  snapshotId, gameId, prefixId, paths, createdAt, reason, checksum
```

不向普通用户暴露 Container；每个 Game 关联一个 RuntimeProfile 和 prefix。高级设置可以展示底层字段。

## Service 与任务状态

模块内新增 `LocalGameOrchestratorService`，通过 `LocalGamesHost` 获取主应用提供的 Termux 执行能力。建议状态机：

```text
QUEUED
-> PRECHECK
-> PREPARING_COMPONENTS
-> PREPARING_PREFIX
-> STARTING_DISPLAY
-> STARTING_AUDIO
-> STARTING_GAME
-> WAITING_FIRST_FRAME
-> RUNNING
-> STOPPING
-> CLEANING
-> SUCCEEDED | FAILED | CANCELLED
```

约束：

- 每个任务有稳定 task-id，阶段变化先持久化再通知 UI。
- shell 输出 JSONL 事件，包含 taskId、stage、progress、errorCode、message 和 logRef。
- 不解析终端屏幕缓冲区。
- 取消根据阶段区分安全取消、延迟取消和强制终止。
- 启动和清理幂等；锁文件记录 task-id、pid 和创建时间。
- 应用进程恢复后对 pid、锁、X11 socket、Wine server 和数据库状态进行核对。

## 本地游戏导入

- 使用 SAF 选择游戏目录或 EXE 文件，并持久化 URI 权限。
- 扫描深度、文件数量和目录大小设置上限，避免阻塞与耗电。
- 根据 PE 文件、图标、文件名、目录关系和排除词生成 EXE 候选。
- 区分“已安装游戏”和“运行安装程序”，首版只保证已安装游戏流程。
- 保存 working directory 和 arguments，所有 shell 参数逐项引用。
- 封面默认使用 EXE 图标或用户选择的本地图片，不依赖网络元数据。

## 组件下载与安装

- 复用现有 package index、版本、大小和 SHA-256 定义。
- 下载写入 `.part`；存在部分文件时发送 `Range: bytes=<size>-`。
- 记录 ETag/Last-Modified；远端对象变化时丢弃旧 partial 并重新下载。
- 206 响应和 Content-Range 必须与本地偏移一致；服务端不支持 Range 时安全回退到重下。
- 下载任务持久化，支持暂停、继续、重试、网络策略和前台通知。
- 解压到 staging 目录，验证后原子切换；失败不破坏已安装版本。
- `TermuxBoxRepository` 收敛为组件元数据、校验和安装适配器，Fragment Executor 不再拥有任务。

## X11、输入与运行会话

- 复用 `LorieViewRuntimeController.attachTermuxScreenView()` 和 WinHandler。
- X11 Surface 连接状态映射到 `WAITING_FIRST_FRAME/RUNNING`，不能仅以 Wine 进程创建视为启动成功。
- 运行中覆盖层提供输入、FPS/HUD、音量/亮度和安全退出入口。
- 手柄 mapper、触控 profile 和游戏级设置在启动前冻结到 LaunchSpec。
- Activity 暂停不自动结束游戏；Service 决定后台策略并维持通知。
- 安全退出顺序：请求游戏退出、等待、终止 Wine、同步本地状态、停止音频/X11、释放输入、删除锁。

## 数据、存储与迁移

- 新数据优先存放在模块专属的 `local-games` 根目录和数据库，不改变既有 Termux 用户文件布局。
- 复用现有 container.conf 时增加适配层，不让 UI 直接读写 shell 配置。
- 存储分类：游戏引用、prefix、组件、shader/cache、日志、存档/快照。
- 卸载游戏默认只删除游戏记录和可选 prefix/cache，不删除外部游戏目录和存档。
- schema 迁移必须可前向升级；升级失败保留旧数据并阻止写入。

## 受保护路径

预计涉及：

- `android-entry-critical`：注册新 Activity/Service 和入口。
- `termux-service-critical`：建立执行适配器或任务完成回调。
- `runtime-script-critical`：支持直接 EXE 和结构化事件。
- `x11-input-critical`：新 Activity 挂载显示与运行中输入。
- `build-boundary-high`：新增 `:local-games`、调整 settings 和 app dependency 是必需改动。

实现前必须把实际文件写入 manifest，并记录对应批准、验证和回滚。

## 测试策略

- 单元测试：状态机、EXE 候选、路径引用、Range/ETag、校验、迁移和错误映射。
- 集成测试：Service 重绑定、Activity 重建、进程恢复、下载暂停继续、组件原子安装。
- Instrumentation：SAF 权限、横竖屏、后台/前台、通知和返回键。
- 真机：至少覆盖一个 Snapdragon/Turnip 设备；后续扩展 Mali。
- 游戏样本：轻量 2D、DX9、DX11 各一个；记录首帧、输入、音频、退出和残留进程。
- 回归：原 Termux terminal session、TermuxBox 容器和 package manager 基础流程。

## 交付分片

1. 模块骨架：创建 `:local-games`、公开 API、主应用 Host adapter、Activity 和领域模型，不启动真实游戏。
2. 组件链：下载续传、校验、安装和任务恢复。
3. 导入链：SAF、EXE 候选、游戏库和详情。
4. 启动链：LaunchSpec、shell 事件、直接 EXE、状态恢复。
5. 会话链：X11、输入、INGAME、安全退出。
6. 资产安全：快照、卸载、迁移、诊断和兼容回归。

每个分片建议使用独立子 change；本 change 作为总设计，业务实现不得一次性大爆炸提交。

## 风险与回滚

- 首先保留现有 TermuxBox UI 和启动路径，新 module 通过依赖和入口开关启用。
- 模块集成异常时移除主应用依赖和入口即可回退，不要求回滚 Termux 核心模块。
- 新任务系统失败时可以退回现有组件安装和容器启动，不迁移或删除旧数据。
- 运行脚本保留旧参数入口；结构化事件使用可选输出通道。
- 数据迁移前生成备份；组件切换保留上一已验证版本。
