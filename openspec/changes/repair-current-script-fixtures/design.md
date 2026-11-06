# 当前脚本回归夹具修复设计

## Context

动机和范围见 `proposal.md`。基线 `4c4229d2` 的六个脚本均在当前 Linux 工作区复现失败，原始日志位于 `/home/user/workspace/artifacts/continue-4c4229d2/`。

生产启动器、LaunchSpec 编解码器和桌面显式音频契约是本次夹具适配的依据；测试与源码有业务语义冲突时必须记录并返回规划，不能为了测试通过修改生产行为。

## Goals / Non-Goals

目标是让原有六个回归入口重新运行到其真正测试的行为，并在行为损坏时退出非零。非目标见提案；本设计不改变生产 shell、Activity、Service、X11 或输入实现，不新增组件和网络依赖。

## Decisions

### 1. 固定接续基线并隔离工作区

在 detached worktree `/home/user/workspace/termux-app-continue` 中工作，不改变包装仓库分支及其子模块指针。仅修改已确认范围的脚本、确有必要的局部测试辅助文件和状态文档。

### 2. 对照真实代码修复提取与依赖夹具

- 控制器：先核对 `start_termux_box_game.sh` 当前 override 计算和导出代码，再更新提取边界。提取必须非空且完整可执行。继续覆盖未配置手柄时对应项为空且显式 Direct3D 基线保留、DInput/XInput 隔离、多个 Libraries 的顺序、DLL 架构与幂等发布。旧 Java 文件路径也须核对当前 `wine/` 包拆分。
- 环境优先级：现有提取序列新增 `resolve_native_box64`，夹具应明确模拟该无关依赖及当前配置读取，不得丢弃真正的 snapshot/preset/restore 序列；保留含引号、空格、多个变量和四种调试模式。
- Native 启动：使用当前 `wine/data/LaunchSpecCodec`、安装器和实际启动器核对字段及资产依赖。补全局部配置和相邻 launcher，让真实外层脚本执行到被测链路。先保留原 argv/环境/取消/超时/清理断言，再增加实际缺失依赖的负向断言。
- RootFS 启动：按当前 schema 更新 spec，并核对 runtime manifest、Wine/translator 选择、prefix 标记及固定绑定规则。保留未实现渲染器阻断、无自动 fallback、特殊字符 argv、取消与资源清理断言。

### 3. 显式建模音频前置条件

GNOME 直接调用夹具显式提供局部 `PULSE_SERVER`。生命周期夹具在 spec 中提供 `DESKTOP_AUDIO_MODE=host-pulseaudio`，通过局部 fake PulseAudio 模拟可用 daemon，不接触宿主真实 daemon。需覆盖缺失/非法模式、daemon 不可用时明确失败；已有效的音频配置不能掩盖桌面清理失败和先停后启的断言。

### 4. 模块和资源边界

| 边界 | 本次处理 |
| --- | --- |
| Activity / Service | 不改源码、入口、生命周期与任务所有权 |
| 任务状态 | 执行现有脚本并验证事件、退出码及终态；不直接伪造成功 |
| shell | 只改 `termux-ui/scripts/` 测试入口；生产 assets 只读 |
| X11 / 输入 | 使用局部进程和 Unix socket；DLL 断言消费已跟踪资产，不运行设备 Xserver |
| 存储 | 用 `mktemp`/临时目录；路径适配当前 `front` 契约，不触碰设备 C 盘或游戏目录 |
| 组件下载 | 不安装、不联网下载运行组件；mock 仅提供被测脚本的外部依赖 |
| HTTP / 产品文档 | 均为 `N/A`，原因见提案及 run manifest |

### 5. 验证与证据

逐脚本验证后组合运行六项。补跑模块边界及与音频/生命周期相关的桌面合同、RootFS staging/prefix recovery 检查，分别记录命令、退出码、日志和跳过原因。对关键断言进行受控破坏验证：在临时副本故意更改相关行为，确认对应脚本非零退出；不得修改正式工作树的生产资产。

`docs/project-status.md` 只更新本次实际通过的脚本项，不重写 761 项 Java 单测/APK 构建的历史结果，也不把脚本通过当作真实 USB、Plasma Stop 或硬件矩阵通过。

## Risks / Trade-offs

- 提取边界漂移 → 明确校验提取结果，执行真实块，并增加受控破坏检查。
- mock 太宽掩盖生产问题 → 只模拟外部依赖；被测逻辑必须来自生产脚本，保留负向和清理断言。
- 测试误清理宿主进程 → fake 进程位于临时目录，清理必须按夹具 PID/进程组，不允许全局杀进程。
- 无设备 → UI、真实游戏、USB、GPU 和 Stop 真机矩阵继续标记未验证。
- 无权威 Harness 规则 → 当前 ledger 仅记录事实；strict gate 标记不可运行，不创建替代 verifier 或声称 strict 通过。

## Protected Paths

计划不改 Manifest、build、TermuxService、生产 assets、CI 或子模块指针。权威 Harness policy 文件不存在，无法引用真实 policy id；不得编造。若实现发现必须修改上述路径，需补充影响、验证、回滚并取得范围确认后再修改。

## Migration Plan

没有产品或数据迁移。确认规划后逐项修复夹具并验证，最后更新本次证据。回滚仅撤销本 change 的测试/文档改动；保留原始复现日志。Git staging、commit、push、PR、组件发布及部署不在本次授权范围。
