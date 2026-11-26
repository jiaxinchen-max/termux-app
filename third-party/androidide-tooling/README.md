# AndroidIDE Gradle tooling 源码移植

来源：https://github.com/jiaxinchen-max/AndroidIDE ，提交 77ee1a315f34b9ed74a9da94f94a0dc276f72ff6。
`upstream-lock.json` 固定十个模块的 2316 个原始源文件与 SHA256；原文件不修改。
保留 GPLv3 及各文件自己的版权和许可声明。

构建适配位于本目录 build.gradle 和 jaxp/build.gradle：

- 将 tooling/api/model/events/impl/builder-model-impl、utilities/shared、logging/logger、xml/dom
  合并编译为独立 JVM 服务，JAXP 源码单独按 Java8 编译。
- BuildInfo 从原始模板生成，版本是移植来源信息，不改变宿主 APK 版本。
- JVM 服务使用原版 logback 1.5.3，而非 Android 专用 logback-core；未引入第二个 Application、
  原 ToolsManager、原 Termux 或 idestats。
- `:ide-tooling:serverJar` 输出 `androidide-tooling.jar`，由 `:ide-tooling:componentBundle` 生成按需下载组件。主 APK 仅保留固定 SHA256/大小/协议/URL 的清单和许可证，不包含 JAR。
  下载后在私有目录原子安装，该 jar 只由 Termux native JDK 运行，不进入 ART/D8，不提高宿主 minSdk21。
- 类路径、SDK、变体、资源等来自原 Gradle 模型接口，不解析 build.gradle 猜测结果。
- 宿主负责有界 stdio、客户端回调、任务日志、取消、shutdown 和私有任务进程组回收。
- 不依赖 artifacts 目录、本地 Maven、独立 AndroidIDE checkout 或预编译组件。

此组件接入仅完成项目模型同步的基础链路，不等于 Java/XML 语言服务及设计器完整移植。

组件仓库发布标签：`jiaxinchen-max/termux-app-packages/releases/tag/ide-components`。
重建并发布组件时，应同步 `termux-ui/src/main/assets/ide/tooling/component.properties`，
校验公开下载内容与清单一致，再构建/发布宿主。普通 APK 构建不会上传组件或从远端获取 JAR。
