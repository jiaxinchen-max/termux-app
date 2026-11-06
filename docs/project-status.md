# 工程结构与完成进度

更新日期：2026-11-06。源码分支：`master-x11-submodule-tmp`。

项目处于功能集成与设备回归阶段。核心操作已有 App UI 验证，但完整设备/驱动矩阵及部分专项验收尚未完成，不能据此判定达到正式发布条件。以下按实际证据列状态，不用缺少验收口径的百分比表示完成度。

## 1. 工程边界

实际 Gradle 模块以根目录 `settings.gradle` 为准：

```text
app                         APK、Termux 宿主、模式路由、Launcher、退出清理
termux-ui                   UI 页面、组件安装、后台任务与业务配置
  activity / api            公共页面、导航、宿主契约
  wine/                     EXE 配置、运行环境、启动、独立 C 盘归档
    activity/api/data/domain/runtime/service/artwork/importer/recovery
  desktop/                  桌面创建、安装、会话、归档与 USB 入口
    activity/api/data/domain/runtime/service/archive
  components/               组件索引、下载校验、版本安装与投影
  data / domain             共用存储、任务与组件数据
  platform / service        主题、方向、宿主接入与公共任务服务
  src/main/assets/local-games/
                            Native / PRoot 安装、启动、音频、显示及归档脚本
termux-x11                  X11SessionView、输入、内置 Xserver 与原生依赖
termux-render               GLES / AHardwareBuffer 显示桥接
termux-usb                  内置 USB host 授权及 FD 传递
termux-shared               Termux 公共能力
terminal-emulator           终端模拟器
terminal-view               终端视图、输入与绘制
shell-loader / stub         Shell 装载与接口
float-ball                  悬浮入口
distribution/termux-app-packages/
                            组件仓库索引与迁移/发布工具
tools/termux-usb-bridge/     桌面只读 USB 桥接、原生适配及测试工具
docs/desktop-testing/       设备验收、故障对照、发布与待办证据
openspec/                   历史需求、设计和任务清单
```

`termux-ui` 通过 `LocalGamesHost` / `TermuxLocalGamesHostFactory` 使用宿主能力，不反向依赖 `app`；显示通过 `X11SessionView` 接入。Wine 与 Desktop 已拆为同级包，共用页面、组件框架和存储契约留在公共包。

仓库根部的 `games`、`termux-x11-app`、`termux-x11-display`、`termux-x11-preference` 等目录未列入当前 `settings.gradle`，不作为独立构建模块统计。

## 2. 数据与分发

UI 数据统一以应用内部 `files/front` 为根，不提供旧 `files/games` 自动迁移：

| 路径 | 内容 |
| --- | --- |
| `front/library`、`profiles`、`imports` | 独立 EXE 配置、运行参数与导入文件 |
| `front/containers` | Wine 独立容器及 C 盘；同一 EXE 可创建多份配置 |
| `front/launches` | 启动规格、任务、事件、日志、锁与取消标记 |
| `front/runtime` | 安装脚本、环境准备任务及显示后端选择 |
| `front/components` | 组件下载、安装版本与任务 |
| `front/desktop` | 桌面记录、运行规格、状态与日志 |

PRoot RootFS 由宿主 `proot-distro` 管理，Native 软件包仍安装到 Termux 对应 prefix；不应将所有组件实体误认为都位于 `front`。

通用自有组件已迁移至 `jiaxinchen-max/termux-app-packages`，旧 `termux-app` 中已校验的组件源副本和未引用历史环境归档已清理。第三方来源、KWin 和 Xserver 独立仓库保持不变；详见 [组件迁移](desktop-testing/packages-repository-migration-20261103.md)。

| 组件 | 固定发布版本 | 源码分支 |
| --- | --- | --- |
| Xserver | `termux-xlorie-21.1.16-r9` | `jiaxinchen-max/xserver` / `codex/gnome-glx-swap-event` |
| KWin | `termux-hwaccel-6.6-r28` | `jiaxinchen-max/kwin` / `codex/kwin-r28-egl-teardown` |
| GNOME / USB 支撑组件 | `gnome-usb-20261030-r1` | `jiaxinchen-max/termux-app-packages` 分发 |

r9/r28 已公开，39 项资产及 App 的 14 个下载 URL 已校验；源码、构建输入和验收边界见 [发布记录](desktop-testing/release-xserver-r9-kwin-r28-20261106.md)。App 本轮提交推送不创建 APK release。

## 3. 功能进度

| 功能 | 当前状态与验收边界 |
| --- | --- |
| 终端 / UI 双模式 | 已实现并经 App UI 验证；终端恢复基础 session list，UI 独立导航，终端扩展键保留；双返回退出清理同 UID 进程和服务。 |
| UI 导航与主题 | Wine / Desktop / Terminal / Files / Settings 统一导航；集合网格/列表、Native/Proot 模式数量入口、Auto/Light/Dark、终端及会话框配色已实现，分项验证记录见测试目录。 |
| Launcher | 默认模式对应桌面/终端名称与图标；桌面使用黑白显示器企鹅图标。Fold 与小米已验证，英文/中文名称已验证。 |
| Wine EXE 配置 | 管理单元为独立配置；同一 EXE 的 Native/Proot 记录互不覆盖。创建沿用当前模式，保存时准备环境；运行页方向、触控独立。 |
| Proot 导入与损坏保护 | Fold 正式 UI 导入、显式构建、保存准备、运行 Winfile、退出及删除通过；损坏环境/C 盘保留并明确失败，不自动重建；组件显式绑定，构建与 C 盘有空间预检。 |
| Wine 备份恢复 | Native 无备份入口；Proot 单配置只备份 C 盘和对应注册表，基础环境备份位于设置的核心组件。显式恢复可补齐归档的运行标记。 |
| 多版本组件 | 游戏配置与组件管理复用版本管理页面，支持版本选择和本地导入；安装输出进入会话框并支持跟随滚动、累计日志去重及主题适配。 |
| Native 桌面 | XFCE、Plasma、GNOME 已有正式 App 启动/操作验证；Fold Native KWin System EGL 正常 Stop 已验证。 |
| Proot XFCE | 固定 Debian trixie；正式安装、System EGL 启动、文件管理、触控、音频、归档恢复通过。Fold 的显式 LLVMpipe 模式曾因缺失导入扩展明确失败，不标记该模式通过。 |
| Proot Plasma | 固定 Debian forky；安装、Dolphin、Konsole、触控、音频及重新打开视图已通过。r28 完整 Proot Stop 回归尚未完成。 |
| Proot GNOME | 固定 Debian trixie / GL3；Fold LLVMpipe 与小米 Zink/Turnip 的概览、Files、持续刷新已通过，Fold 音频通过；GNOME 归档未支持。 |
| 桌面导入导出 | XFCE/trixie、Plasma/forky 归档与容器/设置独立管理；不归档 Termux 侧组件，恢复前自动检查并安装所需组件。XFCE 正式导出/恢复已通过；缺失组件的实际网络安装分支未完整验收。 |
| 内置 USB 模块 | 无需独立 Termux:API；枚举、授权协议、FD 与无设备 UI 已验。GIO/GVfs 只读集成用于 GNOME Files / XFCE Thunar；真实 U 盘与 Dolphin 集成未通过验收。 |

关键记录：[Fold 从零 UI 验证](desktop-testing/pixel-fold-clean-ui-20261104.md)、[Proot 导入复测](desktop-testing/proot-import-recovery-20261106.md)、[GNOME/USB](desktop-testing/gnome-usb.md)、[Launcher](desktop-testing/launcher-mode-icons-20261106.md)、[Wine 归档](desktop-testing/wine-cdrive-backup-20261103.md)。

## 4. 必须保持的行为

- Native / PRoot 及图形模式显式选择，请求与实际驱动一致；失败直接报错，禁止自动换模式或渲染器。
- 已有损坏、缺失或中断环境保留现场并提示显式重建；非空损坏 C 盘禁止模板覆盖。
- XFCE 使用 trixie、Plasma 使用 forky；不验证已确认不兼容的反向组合。
- APK 本地构建，deb 按目标 ABI / libc 在对应远程环境构建。
- 正式功能通过 App 界面操作验收，脚本夹具、诊断和构建不替代业务验收。

## 5. 待完成与风险

1. 真 USB 盘的 Android 授权、读盘、卸载验收；Plasma Dolphin / Solid / UDisks2 集成尚未实现。
2. r28 的 Proot Plasma 停止完整回归；当前 Native Stop 通过不能替代该项。
3. Android 默认 phantom-process 限制下的长桌面会话。Fold 部分图形测试临时关闭过裁剪，测试后已恢复，不能声称默认限制已解决。
4. 并发显示 owner 冲突门禁；暂停外部烟测后的成功不等于并发问题修复。
5. 桌面归档恢复时缺少支撑组件的真实网络安装；GNOME 归档暂不支持。
6. Discover 旧实例空池的精确历史根因未完整重放；新版本主路径通过，不关闭该证据边界。
7. 6 个脚本测试尚未通过：`test-controller-runtime.sh` 的覆盖块提取、`test-launcher-env-precedence.sh` 的新增 resolver 调用、`test-start-local-game.sh` 的启动夹具、`test-start-rootfs-game.sh` 的 spec schema，以及 `test-desktop-gnome-scripts.sh` / `test-desktop-managed-lifecycle.sh` 的音频夹具。这些失败均已实际记录，需同步契约后再验证，未作为通过或删除测试。
8. Fold 最后验收时仅余约 1.07 GiB；新建独立 C 盘需至少 3 GiB。已恢复临时移走的 Native 组件并完成 24,621 文件 SHA 校验，容量问题未永久解决。
9. 桌面历史 TODO 仍有旧 tag 清理等条目；未经当前远端核对，不标记完成。

## 6. 本次提交前检查

- 本地 `:app:assembleDebug` 成功，包含四 ABI 原生目标。
- `app` 50、`termux-ui` 603、`termux-x11` 108 项 Java 单测通过，共 761 项；`terminal-view:testDebugUnitTest` 无测试用例。
- 模块边界检查通过；RootFS staging 8 场景、prefix recovery 5 场景、维护环境隔离检查通过。
- 桌面脚本合同测试通过（macOS 需 `TMPDIR=/tmp` 避免 Unix socket 路径过长），USB 策略 6 项、恢复预检 39 项通过。
- GIO 安全测试 10 项全部因平台条件跳过；Mesa 18 项中 5 项通过、13 项跳过。Linux 专用测试不计为在本机已验。
- 上述 6 个脚本测试失败单独保留，不声称所有自动检查全绿。
- 29 个运行 Shell 脚本语法、文档链接和 Git 空白检查通过。
- 7 个原生子模块的 28 个文件差异逐字节确认全部来自已跟踪的 `patches-darwin` 构建补丁；不更改子模块提交指针，不把补丁应用结果另行提交到上游仓库。
- 本次仅整理文档并提交已有改动，没有重新执行完整设备 UI 矩阵；既有验证按对应日期与 APK 记录引用。

构建日志：`/tmp/termux-project-summary-20261106-gradle.log`；脚本结果：`/tmp/project-test-*.log`。设备证据中的 `/tmp` 路径属于本机临时产物，不保证新 clone 可访问。
