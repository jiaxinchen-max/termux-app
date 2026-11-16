# 修复 Ubuntu GNOME 容器备份恢复

用户在排查后明确要求“开始修复”，批准前述导出、恢复依赖与恢复后会话初始化一起补齐的范围。上次 Ubuntu Plasma 发行版修复保留了 GNOME 拒绝规则，UI 也保留了禁用菜单及“不支持备份”的提示，本次补齐整条归档链路。

支持当前 Ubuntu Resolute / Ubuntu GNOME / System EGL / llvmpipe / USB 关闭的容器完整备份恢复；保留容器名称，重名按现有数字后缀规则处理。恢复时只安装宿主必需组件，容器应用和用户数据直接从备份恢复；为新身份重建机器相关会话文件。初始化在安装、保存和恢复阶段完成，普通启动失败退出。

本次不扩展 Hyprland 归档，不支持独立废弃 GNOME、旧格式迁移或 Native 归档。现有 XFCE/Plasma 支持保留。范围与实现计划已由本轮指令确认，不重复请求许可。

无 HTTP 契约变更，api_docs=N/A。product_docs=Required，目标 docs/product/desktop-container-archive.md，用户最终接受前 consistency=Pending。仓库缺少 Harness 指令、verify.sh、baseline specs 和 OpenSpec CLI，如实记录，不声称门禁通过。不归档、不部署到用户 App；沿用用户任务结束推送代码保存进度的授权。
