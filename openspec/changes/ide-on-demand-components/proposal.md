# IDE 重型组件按需下载（已批准）

## 目标
保持设置中的 IDE 开关、标签、欢迎页和编辑器可直接使用，将 Gradle tooling 等重型运行组件从主 APK 移到现有 jiaxinchen-max/termux-app-packages 组件仓库。当前 tooling JAR 的 APK 压缩贡献约 15.6 MiB；这是估计可减少量，最终以标准 APK 实测为准。

## 范围
首批仅拆出已有独立 JVM 进程的 Gradle tooling。主 APK 保留 Sora 和界面；Java/XML 服务继续原批准的功能移植，其分发边界在完成宿主适配后沿用组件协议，不声称现已可全部拆分。整个 IDE 独立插件 APK 作为备选，不属于本方案。

## 文档及授权
新增下载行为属于本次新增规划，用户已确认实施。没有 HTTP 服务端合同变更，api_docs=N/A；product_docs=Required，验收后更新 docs/product/android-development.md。组件上传、代码推送和 APK 安装分别遵守原授权边界；不自动安装 APK。
