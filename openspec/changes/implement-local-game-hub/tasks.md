# 本地游戏中心任务

## 0. 规划与架构门禁

- [x] 用户确认 proposal、design、spec 和 tasks。
- [x] 将总 change 拆成可独立验证的子 change，并为每个子 change 建立 Harness run。
- [x] 对 Manifest、TermuxService、脚本、X11 和 build 文件的实际改动取得 protected path 确认。

## 1. Module、Activity 与领域骨架

- [x] 新增 `:games` Android Library、module build、library manifest、consumer rules 和资源前缀；验收：`:games:assembleDebug` 独立编译通过。
- [x] 定义 `com.termux.localgames.api` 公开集成面和 `LocalGamesHostFactory`；验收：模块源码不存在 `com.termux.app.*` import。
- [x] 主 `app` 仅通过 module dependency、Application Host 初始化和入口 Intent 集成；验收：TermuxActivity 抽屉经无状态 adapter 调用公开 Intent API，设备栈验证通过。
- [x] 在模块内新增 `LocalGamesActivity`、主题和导航；验收：一级 tab、返回逻辑和 Activity recreate 模拟器测试已完成。
- [x] 建立 Game、RuntimeProfile、LaunchTask、ComponentTask、SaveSnapshot 模型；验收：私有持久化、严格 schema、LaunchTask/RuntimeProfile/LaunchSpec 旧版本迁移和 SaveSnapshot 校验已覆盖。
- [x] 建立 Repository 与 Orchestrator 接口；验收：接口保持纯 Java，可注入 fake backend，不依赖真实终端。

## 2. 网络组件任务

- [x] 将现有 package index、校验和安装能力适配到组件仓库；验收：现有 package fixture 可识别。
- [x] 实现持久下载任务、`.part`、Range、ETag/Last-Modified 和重试；验收：后端原语已覆盖正确 offset、对象变化、安全回退和失败重试。
- [x] 实现 staging 解压、校验、原子切换和失败回滚；验收：注入失败不会破坏旧版本。
- [x] 接入持久前台 Service、通知和进程重建恢复策略；验收：活动状态沿用稳定 task-id。
- [x] 接入组件页面；验收：16 个 Runtime/Wine 组件、持久任务状态与操作在 Activity 重建后保持一致。

## 3. 游戏导入与游戏库

- [x] 实现 SAF 权限持久化、目录扫描和扫描限制；验收：权限失效阻止导入并提供重新选择动作。
- [x] 实现 EXE 候选识别、排除规则和手动纠正；验收：仅 PE 签名有效的 EXE 入选，样本排序稳定且全部候选可手选。
- [x] 实现本地封面、游戏库、详情、最近游玩和安全删除；验收：私有封面事务与外部 marker AndroidTest 证明不修改用户原始目录。

## 4. 游戏级配置

- [x] 将 TermuxBox container 配置映射为 RuntimeProfile；验收：现有配置可读取且不会被隐式重写。
- [x] 实现推荐、稳定、兼容、自定义预设和变更摘要；验收：可恢复默认和上次成功配置。
- [x] 实现组件依赖预检和存储预算；验收：缺组件、空间不足和权限错误均在启动前阻断。

## 5. 启动任务与可选终端

- [x] 实现持久 LaunchTask 状态机和 task-id 观察；验收：Activity 重建不重复启动。
- [x] 在主应用实现 Termux `LocalGamesHost` adapter；默认 AppShell，按游戏可选命名 TerminalSession 且不强制打开终端页。
- [x] 修改启动脚本支持 LaunchSpec、直接 EXE 和 JSONL 事件；验收：路径含空格和特殊字符时正确启动。
- [x] 实现取消、超时、进程死亡核对和幂等清理；验收：各阶段故障注入后无错误锁和残余服务。

## 6. X11 会话与 INGAME

- [x] 在新 Activity 中挂载 X11 Surface 并映射连接/首帧状态；验收：首帧前不标记 RUNNING。
- [ ] 接入 WinHandler、游戏级 mapper 和触控 profile；实现已完成，DInput/XInput 实体样本验证待质量矩阵。
- [x] 实现 INGAME 覆盖层和安全退出；验收：后台、旋转和返回键行为符合设计。

## 7. 本地资产与恢复

- [x] 实现 prefix、缓存、日志、存档和快照的分类展示；验收：固定分类、有界私有占用统计和外部内容 protected 状态已验证。
- [x] 实现配置/prefix 快照和回滚；验收：SHA-256、发布失败回滚及 prepared/committed 重启恢复已验证。
- [x] 实现安全卸载；验收：默认只删除库记录/私有封面，外部游戏文件和存档固定 KEEP。

## 8. 质量门禁

- [ ] 完成单元、集成、Instrumentation 和真机矩阵测试；自动化与 API 30 模拟器已通过，实体手柄/真实游戏/GPU 矩阵待设备样本。
- [x] 回归 TermuxActivity、TerminalSession、TermuxBox、X11 和组件管理基础流程；验收：入口栈、两种 runner、TermuxBox 两页面、X11/native build 和组件 AndroidTest 通过。
- [x] 增加模块边界门禁；验收：CI 检查 `:games` 无 `:app` dependency 和 `com.termux.app.*` import。
- [x] 填写 code/test/CI/device/pre-merge 证据，关闭自动化范围 MUST_FIX；实体矩阵保持显式 Pending。
- [ ] 用户接受后更新 `docs/product/local-games.md`、同步 OpenSpec baseline 并归档。
