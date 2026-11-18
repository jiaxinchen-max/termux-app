# Wine 与 Desktop 卡片修复

Wine已批准修复保持原样；用户明确要求扩展检查desktop并一并修复，本轮修改7个desktop布局。修复长标题裁切、列表长按钮挤掉正文、新增导入标签无限换行、创建标题撑高和大字体角标／状态／更多图标超高。管理网格128×160dp，封面52dp、48dp管理操作触控范围保持；创建页保留按字体整体自适应，名称固定两行并省略。

真实资源编译及29项聚焦测试通过，含9项desktop和20项Wine回归。Wine五文件SHA256与此前交付一致。实际原始失败和最后成功日志均保留在artifacts/desktop-card-layout-verification。git diff --check通过；strict实际127，缺失仓库脚本。已按用户后续下载要求构建arm64 APK，签名、ZIP及下载完整性校验通过；设备验收Pending。

Stage：AwaitingUserAcceptance。API文档N/A，产品文档Required（local-games.md与desktop.md），一致性Pending。未Git暂存、提交、推送、安装、归档。

APK下载：原名termux-app_apt-android-7-debug_arm64-v8a.apk，45430807字节，SHA256 aeffc7ce8704c094efea75cd3b4bc07e76775cf5eafc3368e721b98583b8e9c8。记录见apk-delivery-evidence.json。

## 2026-11-18 用户授权推送
用户明确要求推送代码，Git授权已更新。同步远端55026c58，保留其Ubuntu26.04迁移与桌面修复；创建卡片冲突合并保留实际Activity的固定边界及本轮角标、两行省略和等比缩放。聚焦29项与完整应用23项测试均通过，两组布局测试已纳入仓库。参见pre-push-review.yaml、pre-push-verification.json与evidence/pre-push。strict仍实际exit127；产品一致性、用户接受和设备视觉验收保持Pending。无安装、归档或APK重命名。
