# 已确认实施任务

- [x] 1. 确认截图及范围；完成条件：明确删除共用更多菜单中的 Install，保留主安装流程，中文规划及文档分类完整。
- [x] 2. 删除菜单入口；完成条件：MENU_INSTALL 常量、构建、点击分支均删除，其他菜单及主按钮/自动安装调用保留。
- [x] 3. 审查与本地验证；完成条件：最小差异审查、相关现有测试和 arm64 APK 构建通过，记录设备和 strict gate 限制。
- [x] 4. 原名交付；完成条件：签名、ABI、资产及下载哈希核验通过，APK 保持 termux-app_apt-android-7-debug_arm64-v8a.apk。
- [ ] 5. 最终验收；完成条件：用户确认界面结果后处理产品文档一致性；当前不归档。

## 本地验证
13 项现有定向测试及 arm64 debug 构建通过。源码精确只删除五行，编译产物已确认无 MENU_INSTALL；与前次 APK 对比，仅 classes2.dex 和签名元数据内容变化，资产及原生库均一致。签名、原名、ABI、资产及完整 HTTP 下载哈希核验通过，详见 verification.json 和 apk-verification.json。Android 界面未实机验收；strict gate 因缺脚本退出 127，不声明通过。
