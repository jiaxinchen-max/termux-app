# 修复 Proot Plasma 软件源与桌面被系统终止

## 原因
当前模拟器 Ubuntu 官方 HTTP 软件源的 AppStream 图标索引返回 503，同一 HTTPS 地址正常；Android 15 于 2026-11-15 09:41:09 明确以 Trimming phantom processes 杀死宿主音频、监督器与 KWin，上限为 32。用户在诊断与修复方向交付后明确要求“修复这些问题”，授权本轮修复及该模拟器上的验证。

## 变更
- 新装 Ubuntu 桌面在 APT 更新前将官方 archive/security/ports 源改为 HTTPS；保留发行版、组件和签名。当前模拟器备份后同样修正并验证真实 Discover。
- 在已授权模拟器通过 ADB 关闭幻影进程监控，保存原值、读回有效状态并冷启动复测；不假设普通 App 有修改系统设置权限。
- 容器私有系统总线仅发布 Plasma 已支持的 PackageKit/PolicyKit 激活服务，避免启动 PRoot 不具备硬件能力的 UPower/ModemManager/fwupd/wpa_supplicant/udisks 服务；保留会话总线及可用桌面组件。
- 核验异常退出时现有状态解析与真实进程一致；已有逻辑满足时不额外修改。

## 范围与文档
不修改原生 KWin，不编译生态 deb，不更改签名和渲染模式。无 HTTP API 合约变化，api_docs=N/A。产品兼容性与安装行为受影响，product_docs=Required，拟更新 docs/product/desktop-proot-plasma.md；验收前一致性 Pending。无 Git 提交、推送或发布授权。
