# 完整移植 AndroidIDE

用户已在会话批准执行。以 AndroidIDE dev 的 77ee1a315f34b9ed74a9da94f94a0dc276f72ff6 为固定基线，将已实现能力移植到 Termux native 环境。保持 Advanced 默认关闭和 IDE 标签开关，不新增第二套 Termux 运行时，不使用 PRoot。

范围包括编辑器、Java 语义服务、XML 补全、Gradle 项目模型、资源索引、布局预览与已有设计器、项目管理、构建和运行日志。安装 APK 仍由用户手动执行。未实现的上游功能不冒充支持。

无 HTTP API 合同，api_docs 为 N/A。用户能力发生变化，product_docs 为 Required，目标 docs/product/android-development.md；验收前一致性为 Pending。
