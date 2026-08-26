# Termux App Harness Workflow

| Stage | 主要产物 | Gate |
| --- | --- | --- |
| 1. Requirement Analysis | proposal/design/specs/tasks、request analysis、manifest | 目标、非目标、用户行为、兼容性、受保护路径和测试策略明确 |
| 2. Requirement Review | spec/tasks review、findings | 无开放 MUST_FIX；用户确认规划 |
| 3. Coding Implementation | 代码 diff、coding report | 只实现已确认任务；偏离设计必须重审 |
| 4. Code Review | code review、findings | 无开放 MUST_FIX；生命周期、线程、shell quoting、存储和兼容性通过评审 |
| 5. Test Writing | test plan、测试代码、test report | 行为变更有自动化测试或明确例外 |
| 6. Test Review | unit test review、findings | 无开放 MUST_FIX；测试覆盖真实失败路径 |
| 7. Pre-merge Packaging | pre-merge summary | 变更、风险、回滚和未覆盖项完整 |
| 8. CI Validation | CI result、evidence manifest | Gradle 命令成功，证据 checksum 匹配 |
| 9. Device Verification | device verify report | 安装、导入、下载中断、启动、旋转、后台、退出和进程死亡按范围验证 |
| 10. User Confirmation and Archive | final summary、产品文档、归档记录 | 用户接受；无未完成任务；active/archived gate 均通过 |

## 人工确认点

1. Stage 1/2：确认 proposal/design/specs/tasks 后才开始实现。
2. 修改 Critical protected path 或新增依赖前，确认影响和回滚。
3. 需要真机、外部网络或大体积运行组件时，确认验证成本和环境。
4. commit、push、发布和归档分别确认。

## Android 专项证据

- JVM 单测：按受影响模块运行 `testDebugUnitTest`。
- 编译：至少运行受影响 variant 的 `assembleDebug` 或更小的编译任务。
- Instrumentation：涉及 Activity、SAF、Service、旋转或进程恢复时必须提供。
- 真机矩阵：记录设备、SoC/GPU、Android 版本、ABI、游戏、运行组件版本和结果。
- 下载：记录 Range 响应、断点文件、ETag/Last-Modified、最终 SHA-256。
- 本地运行：记录 Task ID、阶段事件、首帧、退出码、清理结果和残余进程。
