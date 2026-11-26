# 设计

采用固定上游源码及可复现依赖，先闭合 Sora、编译器、Tree-sitter 和 tooling 依赖，再接入宿主。保留 GPL 许可和版权。宿主最低 API21 不变，IDE API26 门禁不变；不靠 overrideLibrary 掩盖运行时兼容性。

复用现有欢迎页、会话、任务服务和原生 Termux。编辑器替换为 Sora，保留未保存文本、UTF16 光标、标签和旋转恢复。语言请求携带项目及文档版本，过期结果丢弃，项目模型提供实际类路径，禁止用词表假装语义补全。

原日志 IPC 必须适配包名、校验调用者及会话并限制日志量；不得自动开启统计遥测。XML 上游未实现的定义、引用、签名和诊断，以及 Kotlin 语言服务、翻译器、Asset Studio、完整 NDK 支持均明确为上游缺口。

受保护构建路径：settings.gradle、各模块 build.gradle 和必要的 Manifest。影响仅限新增 IDE 依赖及连接；通过 revert 本次变更回滚。验证包含依赖解析、模块编译、宿主 API21/API26 兼容、原有 IDE 测试和人工安装后设备验证。用户已批准完整移植所需构建适配，不自动安装 APK。

## 依赖与宿主适配核对

Sora 原 snapshot 元数据返回404，采用相同 ce8de8e 完整源码并在 preBuild 校验284个上游文件；宿主 Gradle 直接依赖模块，不依赖本地预发布二进制。六个 Tree-sitter 4.3.1 组件的 Maven Central POM 可获取；尚不能仅凭 POM 判定运行验证通过。

原 core/common 的 BaseApplication.onCreate 会调用 ToolsManager.init，不能让 TermuxApplication 直接继承它并执行原初始化。迁移时以宿主上下文适配公共服务，复用现有 Termux 环境和 IdeToolchain 的自定义 JDK/SDK 设置；不得覆盖已有运行环境。原核心路径 Environment.init 使用 context.filesDir，但默认 JDK 为 usr/opt/openjdk、SDK 为 home/android-sdk，需要接入宿主实际配置。

上游独立构建使用已由本仓库源码生成的 Sora AAR 替换失效 snapshot，仅用于验证语言服务依赖闭合；该本地验证目录不成为宿主运行或发布依赖。独立构建的缺失 Git tag 版本解析通过其原生构建版本配置解决，不改变 termux-app 版本或 APK 命名。
