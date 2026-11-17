# 实施任务（用户已确认实施）

- [x] 1. 核对现状与规划。完成条件：确认桌面 Resolute、Wine Debian v12、镜像/模板/共享路径耦合及工具缺口，形成中文四件套与文档分类。
- [x] 2. 用户确认方案。完成条件：用户已明确批准推送后实施，并取消历史兼容要求；已同步四件套，APK原名保持。
- [ ] 3. 补齐可复现构建及来源锁。完成条件：实际核实 Ubuntu 26.04 aarch64 OCI digest、APT/包版本及依赖闭包，产出无用户数据的候选镜像；证明未混装 Debian 基础系统库。
- [x] 4. 验证 Wine 与前缀。完成条件：逐类完成 ABI 及 Hangover/Wine/Proton 前缀准备验证，生成真实模板、清单和来源摘要，失败不得通过重绑元数据绕过。
- [x] 5. 接入新配方。完成条件：Java模型/默认入口/任务spec、安装/启动/warmup/组件/备份恢复一致使用新身份；沿用共享根，不实现历史容器、前缀和备份兼容。
- [x] 6. 接入安装事务和重建停止。完成条件：先确认进程退出再准备新根，失败不发布 ready、不动游戏前缀；取消、中断、空间与摘要错误及同配方恢复有可复验结果。
- [x] 7. 完成聚焦测试与实现审查。完成条件：身份、错配、恶意归档、三类运行、安装恢复等自动验证通过，无开放 MUST_FIX；实际运行 strict 命令并如实记录缺失，不宣称完整门禁通过。
- [x] 8. 完成真实 Android PRoot 验证。完成条件：在获准的隔离环境验证三类来源 PE32/PE64、中文、文件、图形、音频和退出；保留原用户环境，无法验证项记 Pending。
- [x] 9. 准备制品发布审阅结果。完成条件：真实候选文件、来源锁、大小/SHA、索引与回滚可审阅；另获明确 Release 发布授权后才发布，核对地址后才启用默认下载。
- [x] 10. 构建原名 APK。完成条件：签名、arm64、源码资产、镜像索引及体积变化可核验，文件名为 termux-app_apt-android-7-debug_arm64-v8a.apk；设备安装另按明确授权执行。
- [ ] 11. 最终用户验收及产品一致性。完成条件：用户接受后维护 docs/product/wine-runtime.md 并完成一致性证据；未接受前保持 Pending，不归档。Git、Release、APK安装与归档授权分别记录。

## 当前证据与剩余条件

任务3：OCI/APT及媒体来源已锁定，候选实际产出，媒体补充包主机和设备字节一致重现；单一builder CLI尚未从零完整执行，因此保留未完成。发布仅为已告知该限制的prerelease候选。

任务5/7：真实image和三套bundle及两个索引均已接入；发布后48项索引/版本/前缀Java回归通过。早期快照测试仍期望Debian的43项、旧版本和摘要，已按实际发布值更新，原失败证据保留。严格脚本缺失，已再次实际尝试exit127，不计为通过。

任务8：三类PE32/64共18项功能/公开PCM/WMV2通过，12次媒体释放完成，18个原始退出码均0；私有测试服务已退出。硬件GPU及App实际交互仍Pending，此勾选不表示最终用户接受。

任务9：用户明确批准后已发布四个全新prerelease，匿名完整下载全部大小/SHA一致；资产仓库仅提交并推送repository/index-v1.json，提交7d2bb960ee1847ee92e93b76e06363d739239ecc，公开main及固定commit索引核对通过。两份索引各只加四项、既有条目保持。用户最新“推送代码”已额外授权本次主应用stage/commit/push；目标为master-x11-submodule-tmp，回执见artifacts/ubuntu-wine-rootfs/main-application-push-result.json。

任务10：原名arm64 APK为45485383字节，比前版增加1081字节；原证书v1/v2、testOnly=false、源码资产/47项索引和完整HTTP下载核对通过。APK构建时七个native子模块既有补丁及主应用基线已核验，本次提交不改其gitlink或补丁；未安装APK。

任务11：App/GPU验收、最终用户接受及产品一致性继续Pending，未归档。交付证据见artifacts/ubuntu-wine-rootfs/apk-download-verification.json和candidate/publication-plan.json。
