# 实施任务

- [x] 核对内置 USB、缺失的通用接收端及 BatteryStatus 阻塞证据。
- [x] 固定上游 Android/CLI 版本并列出 45 个方法、57 个命令。
- [x] 用户确认 proposal/design/specs/tasks 与受保护路径后开始实施。
- [x] 建立完整接口映射与权限、依赖、硬件、生命周期、验证矩阵，保留许可证。
- [x] 引入并适配 Android library、资源、组件、主应用初始化及按需权限。
- [x] 接入同 UID IPC 与标准 CLI/配套组件构建，修正硬编码外部包路由。
- [x] 优先打通真实 BatteryStatus，修复 UPower 阻塞式电池调用及子进程回收。
- [x] 逐项移植全部其余 API；USB 复用现有 broker 并补齐标准协议适配。
- [ ] 适配长会话、交互授权、断线/退出/取消、无权限与无设备错误。
- [ ] 完成权限/导出组件/调用来源/资源泄漏审查及关键自动测试。
- [ ] 回归 native/proot API 调用、USB、UPower 注册及 Dolphin 延迟，记录设备限制。
- [x] 构建普通 arm64 APK，核对签名、testOnly=false、文件名和体积增量。
- [x] 按条记录 45 方法与 57 命令的验证状态，执行可用检查并将缺失 strict gate 标为 Unavailable。
- [ ] 等待用户验收后更新产品文档和归档；Git 操作和组件发布分别等待明确授权。

## 当前验证限制

设备电池/UPower链路正常；Dolphin冷启动20.15秒、干净复测4.065秒，仍有波动。app全量测试8项失败并OOM，定向复测2项仍失败，未证明为既有问题。proot无已安装容器、USB无实物，长会话/硬件/交互权限未全量验收；未完成任务保持未勾选。详见run evidence和coverage-matrix。

## 已确认的交付方式修订（download-runtime-assets-on-demand）

用户已确认主题与 API 配套资源改为资产库按需下载，取代本文件此前内嵌主题/CLI/deb 的交付要求。Android API 宿主保留 APK；Linux 下随 App 构建配套包与是否打入 APK 分开处理。最终要求及验收场景以 download-runtime-assets-on-demand 的四项规划为准，历史发布与验证记录保留原意，不声称旧 APK 已外置。
