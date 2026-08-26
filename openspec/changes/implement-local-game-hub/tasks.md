# 本地游戏中心任务

## 0. 规划与架构门禁

- [ ] 用户确认 proposal、design、spec 和 tasks。
- [ ] 将总 change 拆成可独立验证的子 change，并为每个子 change 建立 Harness run。
- [ ] 对 Manifest、TermuxService、脚本、X11 和 build 文件的实际改动取得 protected path 确认。

## 1. Module、Activity 与领域骨架

- [ ] 新增 `:local-games` Android Library、module build、library manifest、consumer rules 和资源前缀；验收：`:local-games:assembleDebug` 独立编译通过。
- [ ] 定义 `com.termux.localgames.api` 公开集成面和 `LocalGamesHostFactory`；验收：模块源码不存在 `com.termux.app.*` import。
- [ ] 主 `app` 仅通过 module dependency、Application Host 初始化和入口 Intent 集成；验收：移除入口不影响模块编译，模块不反向依赖 app。
- [ ] 在模块内新增 `LocalGamesActivity`、主题和导航；验收：可独立打开、旋转和返回，不影响 TermuxActivity。
- [ ] 建立 Game、RuntimeProfile、LaunchTask、ComponentTask、SaveSnapshot 模型；验收：序列化和 schema 迁移测试通过。
- [ ] 建立 Repository 与 Orchestrator 接口；验收：UI 测试使用 fake backend，不依赖真实终端。

## 2. 网络组件任务

- [ ] 将现有 package index、校验和安装能力适配到组件仓库；验收：现有 package fixture 可识别。
- [ ] 实现持久下载任务、`.part`、Range、ETag/Last-Modified 和重试；验收：中断后从正确 offset 恢复。
- [ ] 实现 staging 解压、校验、原子切换和失败回滚；验收：注入失败不会破坏旧版本。
- [ ] 接入组件页面和前台通知；验收：Activity 重建和应用重启后任务状态一致。

## 3. 游戏导入与游戏库

- [ ] 实现 SAF 权限持久化、目录扫描和扫描限制；验收：权限失效有明确恢复动作。
- [ ] 实现 EXE 候选识别、排除规则和手动纠正；验收：样本目录识别结果稳定。
- [ ] 实现本地封面、游戏库、详情、最近游玩和安全删除；验收：不修改用户原始游戏目录。

## 4. 游戏级配置

- [ ] 将 TermuxBox container 配置映射为 RuntimeProfile；验收：现有配置可读取且不会被隐式重写。
- [ ] 实现推荐、稳定、兼容、自定义预设和变更摘要；验收：可恢复默认和上次成功配置。
- [ ] 实现组件依赖预检和存储预算；验收：缺组件、空间不足和权限错误均在启动前阻断。

## 5. 启动任务与隐藏终端

- [ ] 实现持久 LaunchTask 状态机和 task-id 观察；验收：Activity 重建不重复启动。
- [ ] 在主应用实现 Termux `LocalGamesHost` adapter，模块正常流程不创建可见终端页；验收：用户从游戏详情直接进入启动状态页。
- [ ] 修改启动脚本支持 LaunchSpec、直接 EXE 和 JSONL 事件；验收：路径含空格和特殊字符时正确启动。
- [ ] 实现取消、超时、进程死亡核对和幂等清理；验收：各阶段故障注入后无错误锁和残余服务。

## 6. X11 会话与 INGAME

- [ ] 在新 Activity 中挂载 X11 Surface 并映射连接/首帧状态；验收：首帧前不标记 RUNNING。
- [ ] 接入 WinHandler、游戏级 mapper 和触控 profile；验收：DInput/XInput 样本输入正确。
- [ ] 实现 INGAME 覆盖层和安全退出；验收：后台、旋转和返回键行为符合设计。

## 7. 本地资产与恢复

- [ ] 实现 prefix、缓存、日志、存档和快照的分类展示；验收：占用统计可解释。
- [ ] 实现配置/prefix 快照和回滚；验收：失败配置可恢复上次成功环境。
- [ ] 实现安全卸载；验收：默认不删除外部游戏文件和存档。

## 8. 质量门禁

- [ ] 完成单元、集成、Instrumentation 和真机矩阵测试。
- [ ] 回归 TermuxActivity、TerminalSession、TermuxBox、X11 和组件管理基础流程。
- [ ] 增加模块边界门禁；验收：CI 检查 `:local-games` 无 `:app` dependency 和 `com.termux.app.*` import。
- [ ] 填写 code/test/CI/device/pre-merge 证据，关闭 MUST_FIX。
- [ ] 用户接受后更新 `docs/product/local-games.md`、同步 OpenSpec baseline 并归档。
