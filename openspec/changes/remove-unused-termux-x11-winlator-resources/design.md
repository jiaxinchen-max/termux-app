# 清理设计

## 引用判定

扫描根为 app、termux-ui、termux-shared、termux-usb 的编译类与源码、清单组件、XML 类型和 JNI 入口。Java 引用按 class 的 SourceFile 归属合并，避免把一个文件内的多个包级类型误判为不可达。LorieViewRuntimeSupport.java 内多个类型仍在使用，必须保留。

资源从活动 Java/Kotlin、外部模块 XML、模块清单与 preferences.xml 出发传递闭包，保留资源的全部限定符变体。动态 pref_*、notification_*、控制器图标与配置模板必须保留。移除资源前额外核对 R.id：InputControllerActivity 中 main_menu_containers 的唯一读取只赋给无人使用的局部变量，删除该空分支后再删除旧菜单。不能仅以文件名包含 Winlator 或没有字面引用作为删除理由。

## 最小代码修改

LorieViewRuntimeController 仅删除 Container/Shortcut 导入、字段、shortcut_path 解析和构造、getShortcut，以及随之闲置的 File 导入。保留 setupInputController 内触控、输入覆盖层、InputControlsManager 和 DownloadProgressDialog 初始化。

切断上述空链后删除 Container、Shortcut、EnvVars、KeyValueSet、WineInfo、WineThemeManager、WineRegistryEditor、MSBitmap 及 controller.xserver.ScreenInfo；另删除没有入边的 GLUtility。顶层 com.termux.x11.ScreenInfo 仍在使用，必须保留。

InputControllerActivity 只删除无效的旧菜单 else 分支，保留实际 InputControlsFragment 入口、横竖屏参数和配置选择行为。不扩大为活动页面重构或所有工具方法清理。

## 资产与原生边界

start-tfm 无启动入口，wfm.exe 仅被旧启动脚本引用；测试中的同名路径只是字符串样例，不读取该资产。删除两份资产，保留现有测试样例。controller_runtime.sh 只删除两行历史注释。

wine.tar.gz 是现有控制器 DLL 资产，支持 i386、x86_64 与 Hangover aarch64；wine-bridge.exe 是当前协议桥。两者及 tools/wine-bridge 全部保留。保留任务管理旧对话框：嵌入面板不可用时仍有实际调用。

四个 ABI 的现有 compile_commands 均没有 xrio 文件，CMake 配方没有 xrio 入口，源码没有 XrActivity 宿主，因此只删除其 11 份闲置源码。不修改 XR 清单特性或偏好项。lorie、XServer 及 Vortek 中 Winlator 来源的已编译共享内存/环形队列代码保持原样。

## 模块及保护范围

Activity 保持已有入口；Service、任务状态、下载和存储流程均不修改。shell 执行语句不修改。X11 渲染、输入协议、进程桥与原生构建配置不修改。没有 HTTP 变化，不需要 API 文档。纯内部清理，不生成新的产品能力说明。

仓库没有可读取的 protected-path policy，不能虚构 policy id 或声称保护门禁通过。候选中原生源码及运行时控制器的改动清单、影响、验证和回滚在此明示，用户已明确确认本清理方案；不修改构建入口、签名、组件包、第三方子模块或清单权限。用户追加授权对删除后失去消费者的代码、资源和构建依赖继续清理；执行前补录引用证据、精确清单与回滚备份。与本次清理无关的高风险内容保留并记录。

## 验证与回滚

1. 执行前检查 cleanup-manifest.json 的路径、源哈希与原有脏文件边界，备份将删除/编辑的文件。
2. 分组删除旧模型、闲置资源/资产与 xrio；每组复核跨模块、动态及 JNI 引用；不删除新增依赖异常的项目。
3. 执行 termux-x11 全部现有单元测试，编译 app 的 arm64 debug APK，验证完整资源合并和资源 ID 链接。保留原文件名 termux-app_apt-android-7-debug_arm64-v8a.apk、原签名，禁止 testOnly。
4. 运行现有控制器部署与 native/PRoot 启动脚本回归；酒桥纯协议测试按现有工具链执行。本次无需编写只断言文件消失的测试。
5. 对比保留资产哈希、APK 内容与前后大小，记录实际通过项目和无法执行的门禁；界面功能是否正常由用户手动验证，不使用 ADB 批量传输。
6. 如资源链接、编译或已有相关测试失败，定位并还原本次对应组，不绕过门禁、不引入运行时回退。

回滚从执行前备份仅恢复清单中的删除文件和原始内容，并撤销本次控制器/注释编辑，不使用 git reset 或覆盖现有 PRoot 修改。

## 第二轮闭包清理

第一轮完成后重建类引用与资源图，检查失去消费者的值定义、文件资源、动态入口以及工具方法。若某第三方依赖只被已删方法使用，先确认全仓及运行时无使用，再删除相关闲置方法及模块依赖，验证完整应用依赖解析和构建；保留其他活动消费者需要的依赖。第二轮失败时仅恢复对应文件，不引入替代功能。
