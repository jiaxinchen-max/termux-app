# 2026-11-26 实施进度

基于 master-x11-submodule-tmp 的 997ed195 继续开发，用户此前已批准完整移植。已恢复上游58模块清单和批准记录。

## 本轮已完成

- 固定 AndroidIDE 77ee1a3 与其 Sora ce8de8e 原始源码。
- 将原 EditText 编辑器替换为 Sora 内核，接入宿主主题、固定行号、选择、撤销及重做。
- 以文档缓冲区保留标签切换和旋转期间的撤销记录；释放旧编辑器的监听器。
- 保留输入长度限制并处理 emoji 边界、UTF16 光标、异步布局后的滚动恢复。
- 接入临时词法颜色，兼容 CRLF、CR、LF；未冒充项目语义能力。
- 保留上游许可、增加工具页许可入口和编译时 SHA256 校验。
- 正常 assembleDebug 已通过；39 项宿主测试及16项 Sora 上游测试通过。设备测试代码编译通过，未安装测试 APK、未执行设备测试。

验证命令与结果见 evidence/sora-verification.json。产物保持 termux-app_apt-android-7-debug_arm64-v8a.apk，47,962,926 字节，非 testOnly，最低 API21。标准 CMake 自动应用的七处嵌套子模块源码补丁已在构建后恢复，没有留下嵌套源码改动。

## 正在继续

固定上游 Java 语言服务与 Gradle tooling 的独立构建已通过（357项构建任务），包括定制编译器和资源/索引/模型模块。stdio 元数据及初始化状态请求成功返回；EOF 后进程未在10秒内退出，测试子进程已清理，宿主必须实现明确的关闭与超时回收策略。完整语义模块尚未接入 App，见 evidence/upstream-dependency-build.json。上游初始化适配须避免自动执行 ToolsManager，遵循本变更的 native 环境约定。

## 尚未完成

完整 AndroidIDE 编辑器动作与设置、真实 Gradle 项目模型、平台/资源索引、Java 补全/签名/定义/引用/诊断/重构、XML 语义服务、布局预览和已有设计器、项目管理的缺失能力及运行日志仍待移植。不得将当前 Sora 内核接入描述为完整移植完成。

仓库缺少严格 Harness 脚本，未宣称严格门禁通过。产品文档一致性仍 Pending，未归档。

## 后续实施：真实 Gradle 模型同步

- 从固定提交导入 tooling/api/model/events/impl/builder-model-impl、utilities/shared/build-info、logging/logger、xml/dom 和 JAXP 共2316个上游文件，构建时校验原始 SHA256。
- 原 JVM tooling 服务已进入宿主构建和 APK assets，附 SHA256，按需原子提取至私有目录；不进入 ART、不执行原 AndroidIDE 环境初始化、不修改现有 Termux 工具链。
- 工作区更多菜单新增“同步 Gradle 项目”，通过原有前台任务服务运行项目自己的 Wrapper，保存真实模型，包含模块、任务、源码、依赖和 Android 变体/SDK 引导类路径。
- 接通原服务客户端回调、日志轮转、取消与 shutdown；超时后只回收经 runner 身份检查的任务私有进程组。
- 修复实测发现的接口命名前缀和枚举线路格式差异；补齐 JAXP 包内解析资源，避免发生 MissingResourceException。
- 46项宿主相关测试及1项 JAXP 资源测试通过；其中宿主模型适配器直接启动真实上游服务，验证两模块项目，不以模拟结果代替项目模型。独立 Android 模型测试也通过，取到 debug 变体、引导类路径、源码集、依赖库和变体产物。
- 标准 assembleDebug 和设备测试代码编译通过，未安装任何 APK。模拟器确认 IDE 标签及欢迎页显示、native JDK17运行正常；后续 UI及组件传输连续超时，完整 native 同步链路尚未验证。未完成的临时组件传输目标已清理并确认不存在。

详细记录见 evidence/tooling-verification.json 与 evidence/tooling-review.yaml。用户安装的是前一版 Sora APK；本次新增的同步入口需要安装新产物才能在界面验证。完整移植仍为 InProgress，Java/XML语义服务、索引、完整编辑器动作/设置及设计器未完成，不标记整体完成。

## 项目模型持久化与组件分发规划

用户已安装 tooling 版本，继续原批准范围。同步成功时额外保存应用私有的项目模型；键使用规范化项目路径摘要，跨任务保留，避免任务历史清理影响后续索引。采用进程级锁和 AtomicFile，读取验证模型身份及缓存协议，限制16MiB。尚未接通语言服务消费或 Gradle 配置变更检测，不据此宣称语义功能可用。

4项定向测试通过：2项持久化测试和2项原有 tooling 模型测试（包含真实服务）。设备在线但 UI dump、shell 命令间歇超时，任务状态只读到旧 build 成功记录，不能作为新同步通过证据。未安装 APK。

新增按需组件请求的独立规划在 ide-on-demand-components，状态待确认；没有移除现有内置 JAR，没有上传或发布组件，也没有代码推送。

## tooling组件化交付

新增范围ide-on-demand-components已获批准并完成代码与主机测试，标准APK46,335,908字节，相较原tooling版减少15.65MiB；组件及源码已发布并重新下载校验。详细记录见相邻变更的progress.md和evidence。语言服务完整移植仍未完成，设备ANR和同步回归待继续，未归档。
