# 实施任务（用户已批准实施）

## 1. 规划确认与组件边界

- [x] 1.1 确认 proposal/design/specs/tasks、App 独立选择范围及旧配置导入；完成条件为记录用户明确确认及审批范围。
- [x] 1.2 实施前核对适用 protected-path 规则；完成条件为记录实际策略、影响、验证、回滚及所需审批，不以缺失清单推定批准。
- [x] 1.3 建立合成器/主题目录并记录 r8 复用 r7 的发布证据；完成条件为目录身份一致性测试通过，无虚构 r8 runtime。

## 2. 独立主题包与安装

- [x] 2.1 生成 classic-r7 与 m3ww-v1 静态主题候选包、manifest、许可及打包说明；完成条件为独立重复打包哈希一致、资产清单与许可可核验。
- [x] 2.2 实现按固定目录获取、安全解包、独立哈希验证、锁与候选激活；完成条件为正常安装、篡改、越界路径、链接、超限及中断失败测试通过，旧包保留。
- [x] 2.3 按所选主题安装并检测依赖；完成条件为 classic 无需 m3ww 专属工具、m3ww 缺依赖不能报成功、仅换主题不改变 runtime 哈希的测试通过。

## 3. 配置与 App 适配

- [x] 3.1 扩展模型、版本化持久化及受控 schema3/旧主题标记导入；完成条件为往返、未知值拒绝、自定义配置保留、备份恢复以及非 Hyprland/归档回归通过。
- [x] 3.2 接入创建与设置中的合成器/主题字段和兼容提示；完成条件为选择保存后重启、重命名、复制及输出修改不丢值，运行中切换阻止且原选择不变。
- [x] 3.3 统一 Java、shell、Python 的启动身份与安装检查，按所选主题启动服务；完成条件为参数交接、损坏组件拒绝、原生/PRoot 路由及服务故障清理测试通过。

## 4. 联调与交付

- [x] 4.1 完成实现评审并处理全部 MUST_FIX；完成条件为 Markdown/YAML 评审记录、问题闭环及聚焦验证证据齐备。
- [x] 4.2 执行 ARM64 APK 构建与资产身份检查；完成条件为可核验 APK、签名和 SHA，缺环境则如实阻塞而不标完成。
- [ ] 4.3 在经授权设备验证经典/m3ww 的原生与 PRoot 启停、切换、配置保留和失败回滚；完成条件为逐场景真实证据，未验证范围保持 Pending。
- [ ] 4.4 汇总交付与用户验收结果；完成条件为说明实际支持的组合、独立资产公开可用性和未完成门禁，主题包发布与设备部署按各自授权执行。

## Workflow follow-up

- 用户最终接受后更新 docs/product/desktops.md，执行产品文档一致性核查；Verified 前不归档。
- Harness 文件当前缺失，strict-run 为 Unavailable；恢复工具后执行 .harness/scripts/verify.sh --change-id separate-hyprland-runtime-theme --strict-run。
- Git staging/commit/push、发布和归档分别遵循明确授权。

## 本轮验证记录

103 项桌面 Java 测试、69 项 Python 检查与主题目录 --check 通过；ARM64 Debug APK 构建及签名校验通过。实施评审与修复闭环见 .harness/runs/separate-hyprland-runtime-theme/implementation-review.md 和 implementation-review-findings.yaml。交付产物及日志位于 workspace/artifacts/hyprland-validation。

用户明确确认：Linux 下 termux-api.deb 随 App 编译；Hyprland、KWin 等 termux-packages 生态包在指定编译机编译。本轮复用既有 r7 合成器，不启动生态包重编。

4.3 因无连接设备保持 Pending；4.4 等待用户验收。strict-run 缺少仓库脚本为 Unavailable；主题公网发布已按追加授权完成，产品文档一致性与归档均待最终验收。

## 5. 用户追加授权的组件仓库同步

- [x] 5.1 准备独立组件资料、源材料、发布清单，App 目录同步 URL/大小/SHA 并通过生成一致性检查。
- [x] 5.2 审查差异后提交/推送组件仓库，创建独立候选 Release 并上传静态主题及资料，不覆盖旧 r7/r8。
- [x] 5.3 匿名下载新主题和公开目录，复核大小、SHA 与 App 配置一致。
- [x] 5.4 重新验证 ARM64 APK，文件名保持 termux-app_apt-android-7-debug_arm64-v8a.apk，保留验签及哈希证据。

本轮组件仓库提交 `1547cb572c5cf16376ce95995c0f1403337ab42a` 已推送；候选发布 `hyprland-components-20261113-r1` 的九项资产、公开仓库目录和既有 r7 runtime 均通过匿名下载大小/SHA 检查。App 分支保持 master-x11-submodule-tmp，改动未提交/推送。最终 APK 使用指定名称，103 项 JVM 测试重新通过，签名和内置目录/主题逐字节核验通过；证据位于 workspace/artifacts/hyprland-publication。

## 已确认的交付方式修订（download-runtime-assets-on-demand）

用户已确认主题与 API 配套资源改为资产库按需下载，取代本文件此前内嵌主题/CLI/deb 的交付要求。Android API 宿主保留 APK；Linux 下随 App 构建配套包与是否打入 APK 分开处理。最终要求及验收场景以 download-runtime-assets-on-demand 的四项规划为准，历史发布与验证记录保留原意，不声称旧 APK 已外置。
