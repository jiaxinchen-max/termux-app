# 当前脚本回归夹具修复任务

## 1. 接续基线和规划确认

- [x] 1.1 在独立 worktree 检出截图中的 `4c4229d2` 并读取工程状态；验收：HEAD 精确匹配，包装仓库分支和子模块指针不变。
- [x] 1.2 复现六个已记录的失败并保留日志；验收：每项命令、非零退出码和原始错误可从 run evidence 核对。
- [x] 1.3 确认 proposal、design、specs、tasks 范围；验收：记录用户对本次六项测试夹具修复的明确确认，再修改测试实现。

## 2. 控制器与环境变量回归

- [x] 2.1 修复 `test-controller-runtime.sh` 的 override 代码提取和旧包路径，保留 DLL、mapper、导出及幂等断言；验收：脚本退出 0，临时破坏 override 导出时退出非零，保存两类日志。
- [x] 2.2 补齐 `test-launcher-env-precedence.sh` 的 resolver/配置依赖，保留四种预设、用户变量和特殊字符断言；验收：脚本退出 0，临时移除 restore 时断言失败，保存日志。

## 3. Native 与 RootFS 启动回归

- [x] 3.1 按当前 codec/installer/launcher 修复 Native LaunchSpec、持久路径、相邻脚本与配置夹具；验收：`test-start-local-game.sh` 退出 0，保留 argv、环境、失败、取消、超时和锁清理断言，保存日志。
- [x] 3.2 按当前 schema/manifest/translator/prefix 契约更新 RootFS 夹具；验收：`test-start-rootfs-game.sh` 退出 0，覆盖无效 spec、渲染器失败、无 fallback、参数保真及取消清理，保存日志。

## 4. 桌面音频与生命周期回归

- [x] 4.1 为 `test-desktop-gnome-scripts.sh` 补齐显式音频地址与局部 mock；验收：原有成功/失败矩阵通过，保留或新增缺失音频前置条件的失败断言，保存日志。
- [x] 4.2 为 `test-desktop-managed-lifecycle.sh` 补齐显式音频模式及局部服务；验收：先停后启、清理失败/丢失 spec/资产、渲染器拒绝场景均通过，新增缺失/非法音频模式和服务失败断言，保存日志。

## 5. 集成检查与记录

- [x] 5.1 组合运行六项脚本及模块边界、相关桌面合同、RootFS staging/prefix recovery 检查；验收：逐项记录命令、退出码和环境限制，没有静默跳过或把未运行项记为通过。
- [x] 5.2 按实际结果更新 `docs/project-status.md` 的六项脚本状态及本次 run ledger；验收：本次结果不混同历史 APK/761 Java 单测或真机矩阵，所有剩余设备待办保持可见。
- [x] 5.3 执行测试/实现评审、OpenSpec strict 校验与差异检查；验收：无开放 MUST_FIX，生产源码及子模块指针不变；Harness strict verifier 若仍缺失，明确记为不可运行，不宣称完整 Harness 通过。

## Workflow follow-up

- 用户验收后再按项目规则处理归档；本次不归档历史 change。
- Git staging、commit、push、PR、组件发布和部署需要单独授权。
- 真实 USB、PRoot Plasma Stop、默认 phantom-process、并发 display owner 和真实网络恢复安装属于后续工作，不因六项脚本通过而关闭。
