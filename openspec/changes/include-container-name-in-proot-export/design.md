# 设计

现有 DesktopArchiveFlow 在用户确认导出后用 ID 和秒级时间生成文件名，交给 ExternalBackupLocation 决定固定目录及重名后缀。本次仅在该生成位置加入 distro.getName() 的安全文件名版本，所有 PRoot 桌面共用。

名称保留中文、空格及正常 Unicode 字符；控制字符及 / \ : * ? " < > | 替换为下划线，前导点改为下划线，空名称回退 container。名称部分最多 80 个 UTF-8 字节，按 Unicode 码点截断；连同最多 128 字节 ID、时间和后缀小于常见 255 字节文件名限制，并为重名后缀留余量。

不更改容器 ID、原显示名称、元数据、存档格式、恢复去重、目录权限、安全检查及服务生命周期。仅触及 DesktopArchiveFlow，无已登记保护路径。风险为显示名转换；回滚恢复原生成表达式。运行现有存档及共享目录回归、编译、源码审查和原名 arm64 APK 校验；不为可逆文件名展示调整新增复述实现的测试。

Harness/OpenSpec CLI 在当前仓库缺失，严格门禁真实执行并记录 Unavailable，不伪造通过。产品文档 docs/product/desktop-container-archive.md 验收前不固化。
