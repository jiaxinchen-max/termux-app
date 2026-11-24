# Design

## Context

目标和范围见 proposal.md。初始恢复点为 detached 6885827；用户随后明确要求直接在 master-x11-submodule-tmp 开发，已切换至该分支，实施基线为 46ed6302，切换时无业务改动。仓库缺少指定的 Harness owner/workflow/rules 与 verify.sh，不能声称严格门禁已通过。

AndroidIDE dev 源码 77ee1a3 包含 Termux 副本、Sora 编辑器、Gradle Tooling API 进程和大量 Java/XML 服务。其最低版本为 26，本项目最低版本为 21。采用能力提取与适配，复用当前 Termux，避免重复类、全局 HOME 修改和最低版本被动抬升。

## Goals / Non-Goals

目标：单一 native 后端，首期项目创建/打开、编辑保存、构建日志与 APK 导出闭环。非目标：首期不移植布局设计器、完整语义索引和 Kotlin 补全，不自动安装 APK，不接入 PRoot/X11 构建，不修改现有容器、游戏或发布命名。

## Decisions

1. UI：在 termux-ui 内新增独立开发页面，Activity 负责导航和展示。按需提取编辑器能力并保留来源许可；避免直接依赖 AndroidIDE application 和它的 Termux 模块。输入遵循 Android 标准输入法与外接键盘，不改现有 X11 输入。
2. 存储：项目在当前 Termux 私有 HOME 下的独立项目目录内执行，记录名称、位置、创建时间。共享存储或 SAF 项目通过明确导入复制进入工作区；检查规范化路径、符号链接越界和覆盖冲突，构建不从不可执行的共享存储直接启动。导出通过系统文档接口选择位置。
3. 工具链：从真实已安装软件和包数据库发现 JAVA_HOME、SDK、aapt2、clang、cmake、ninja、NDK。查询仓库并展示缺失项后安装；已满足版本不重复下载。使用项目 Gradle Wrapper，不用系统最新 Gradle 覆盖项目版本；禁止临时全局改写 JAVA_HOME/SDK。
4. NDK：官方包源码已证实 ndk-sysroot、ndk-multilib、libllvm 等构成 native 编译基础；完整标准 NDK 兼容未验证。先运行最小 C/C++ 编译，再验证 Gradle externalNativeBuild；不得仅发现 clang 即声明 NDK 就绪。若目录/API/ABI 不兼容，显示具体限制并据证据准备最小适配层，不下载 x86_64 Linux NDK 冒充可执行工具链。
5. 构建：Service 监督单项目任务，状态为待开始、运行中、取消中、成功、失败、已取消。复用现有 Termux 执行能力，参数列表传递路径，日志持久化且有大小上限；按任务进程组取消，不误杀桌面/Wine/其他终端。重建页面可恢复日志；进程异常退出不能永远显示运行中。
6. 外部工程脚本以应用 UID 执行，明确提示首次构建会运行工程代码；不授予新的网络服务或 Android 权限。构建成功必须结合任务结果与当前产物发现，展示 APK 路径、时间、大小，避免把旧 APK 当成本次成果。安装由用户手动进行。
7. 组件和边界：大体积工具按需安装，不内置主 APK；原始 APK 类型与名称不变。无 HTTP 合约变更，api_docs=N/A。产品文档目标 docs/product/android-development.md，验收前保持 Pending。

## Risks / Trade-offs

- 版本组合不兼容 → 固定测试工程的 AGP/Gradle/JDK 组合并显示兼容错误。
- 原生编译器可用但标准 NDK 不可用 → 将 native C/C++ 和 externalNativeBuild 验证分别记录，不扩大支持声明。
- 磁盘不足/下载中断 → 安装阶段检查空间，失败不标记就绪，保留现有有效工具链与项目。
- 低版本 Android 不兼容 → 开发入口能力检测，保留主应用 minSdk；首期无法兼容的依赖须替换或隔离。
- 仓库未提供 protected-path policy → 不虚构 policy id；计划修改普通 UI、任务、数据、资源和 manifest，新增权限、签名、CI 或底层服务变更前单独说明影响与验证。

## Migration Plan

无历史开发数据迁移。先完成本机工具链验证，再实现 UI 和任务闭环，最后构建正常 APK 供用户手动安装。回滚移除入口与新增代码，保留用户项目、已下载工具和其他应用数据。

## 已批准的入口细化

Settings 新增 Advanced 子菜单，IDE 开关默认关闭并持久化；开启时横屏顶部和竖屏底部导航同时新增 IDE 标签，关闭时同时隐藏。所有根页面恢复时刷新标签，禁止直接进入关闭的 IDE 页面。现有底部导航已满五项，扩展为支持六项的导航控件，不能导致菜单加载异常。关闭功能只隐藏入口，不删除项目、工具链或停止正在进行的任务。

## 当前实施细化与验证边界

- `IdeWorkspace` 管理 HOME/projects，创建时间存入项目元数据；新建和 SAF 导入均先写隐藏暂存目录，成功后发布，删除项目先移入隐藏回收目录再递归删除且不跟随符号链接。导入忽略模块构建产物、Gradle 缓存与根级 local.properties，保留源码中名为 build 的普通包目录。
- `IdeTemplate` 生成 Java Activity/XML 工程，AGP 8.5.2、Gradle 8.7、SDK/API 34、build-tools 34.0.0、JDK 17—21。Wrapper 取自 Gradle v8.7.0，附 Apache 许可；发行包与 SDK 命令行工具下载固定校验和。仅增加小型 Wrapper 和脚本资源，不内置 JDK/SDK/NDK。
- `IdeActivity` 提供目录层级、128 KiB UTF-8 文本编辑、Java/XML 基础词法颜色、未保存确认和配置重建恢复；尚无语义补全和布局设计器。工具路径可自动发现或由用户指定，仅写入当前任务环境。
- `IdeBuildService` 为非导出前台服务，使用现有权限；一次执行一个任务。独立 setsid 进程组的身份由任务脚本路径和 /proc 会话/进程组核验；取消不按进程名或应用 UID 广泛终止。任务状态原子持久化，进程中断后标为 interrupted。输出滚动保留，任务缓存保留最近 8 项。
- 构建前将旧 APK 移离项目输出目录，成功时另外验证 ZIP 的 AndroidManifest.xml，再快照保存本次 APK。仅通过系统文档接口导出，不安装 APK；普通宿主 APK 名称、类型、签名配置均不改动。
- 源码修改仅限新增 IDE UI、模型、服务、测试、资源和同模块清单；不新增 Android 权限，不修改 Wine/Desktop 执行服务。回滚移除这些入口/组件，保留 HOME/projects、SDK、JDK 和用户已导出的 APK。
- 模板已在本机 Linux 构建环境生成实际 APK。设备原生 C/C++ 探针通过，实际 Java 准备因应用 UID 环境下的软件源 DNS 失败而未完成；不得将主机示例构建和模拟测试当成 Android native 的闭环验收，更不宣称完整 NDK/externalNativeBuild 兼容。

## 重启后页面切换 ANR 修复

2026-11-24 重启后 ANR 采样显示主线程在 UiNavigation.refreshOptionalDestinations → Material 底部菜单重建 → EmojiCompat.getLoadState；线程为 Runnable，不能据此认定 EmojiCompat 死锁。横屏仅显示顶部标签，但原实现仍同步构造底部菜单并切换 IDE 可见状态，引起隐藏菜单重建。底部菜单改为仅竖屏需要时初始化，初始化时合并可见性和选中状态并只刷新一次，无变化时复用视图；旋转与设置恢复仍同步入口。工具页文件探测、任务恢复移至已有 IO 执行器。此修复不更改 IDE 功能范围、权限或用户数据，普通 APK 编译通过后仍需用户安装并进行设备回归，不能把局部采样当作完整根因证明。
