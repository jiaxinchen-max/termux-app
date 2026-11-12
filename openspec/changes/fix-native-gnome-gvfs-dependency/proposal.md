# 修复 Native GNOME 的 GVfs 安装缺口

## 问题与授权

设备120.24.191.156:8057的正式App启动日志显示缺少org.gtk.vfs.Daemon.service，设备未安装gvfs。ICU79 SpiderMonkey已通过设备摘要核对。此前诊断记录已列明安装依赖、就绪检查和回归测试范围；用户在阅读诊断后明确要求“修复这个问题”，作为该范围的实施确认。

## 变更范围

Native GNOME安装显式依赖Termux gvfs，成功前检查启动器core策略要求的dconf、GVfs和Nautilus服务；App安装状态检查覆盖这些服务，避免缺依赖仍报告可启动。新增命令行为和文件系统就绪测试，构建原签名ARM64非testOnly APK。现有桌面配置和游戏数据不迁移，ICU组件版本和发布清单不变。

api_docs为N/A：无HTTP契约变化。product_docs为Required：影响Native GNOME安装完整性，目标docs/product/desktops.md；该文档当前缺失，验收后再维护，一致性保持Pending。
