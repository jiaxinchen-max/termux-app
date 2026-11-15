# 设计

## 发行版校验
让 DesktopArchiveTasks 的真实 rootfs 身份检查使用 DesktopCreationCatalog 中当前桌面对应的 containerBase/containerCodename，避免入口仍固定 Debian。继续限制受支持的归档桌面组合，保留原有 os-release 大小限制、受控符号链接解析及容器根目录边界检查。元数据和引擎继续验证桌面类型、发行版代号、渲染参数及实际归档内容；不同层的校验必须一致，不能仅凭容器别名推断发行版。

## UI 存储流程
在默认 UI 可交互且宿主可用时触发一次检查；区分权限缺失、目录链接缺失、实际读写失败。采用现有 PermissionUtils 的旧版权限及新版管理所有文件权限流程，不绕过 Android 授权。授权/设置页面返回后重新探测，再建立必要链接并验证共享下载目录可写；文件探测与链接操作在工作线程执行。
通过 LocalGamesHost 增加窄接口连接 app 端存储初始化能力，复用 termux-setup-storage 的授权和链接逻辑语义，不依赖可见终端的广播接收者，不创建终端会话。现有 TermuxInstaller.setupStorageSymlinks 会清空 ~/storage，UI 自动修复不得原样调用该清空操作；只创建或修复受控标准链接，对同名普通文件/目录保留并提示冲突，拒绝跟随用户链接递归清理。
状态需防止 onResume、权限回调和重复点击并发启动或重复弹窗；旋转或重建不应持续强制授权。拒绝后私有目录功能保持可用，外部操作明确提示并提供重试。已有备份/导出权限回调应复用检查结果，避免待执行操作被吞掉或执行两次。

## 影响、回退与验证
可能修改范围为 DesktopArchiveTasks、必要的纯 Java 身份解析/校验辅助类及测试，LocalGamesActivity、LocalGamesHost、TermuxLocalGamesHostFactory 和 app 存储辅助类，以及必要的提示资源。无现存受保护路径列表，若实现发现新增保护规则，先记录并请求对应批准。
回退仅撤销本变更的源代码；不删除容器、不撤销用户的 Android 权限、不删除既有外部文件或链接。测试设备先强制停止 app、清理本次测试拥有的残留进程并确认干净后，从 UiLauncher 启动。涉及真实数据的导入测试使用独立测试容器，禁止覆盖现有容器。
仓库缺失 OpenSpec CLI、基线 specs、产品文档及指定 Harness 脚本，手工建立规划并运行可用的聚焦验证，不能宣称严格门禁通过。

## 授权后的统一入口细化
以模块内非导出的透明授权 Activity 承接系统权限回调，避免各页面重复处理；同一轮请求合并，授权完成并验证真实读写和链接后，仅执行各等待操作一次。链接能力由 termux-ui 的本地存储辅助类提供，无需扩张宿主桥接接口。后台命令以明确的通知授权入口恢复，DocumentsProvider 只验证既有权限，不主动拉起界面。

## 实机回归修复：snapd 空挂载目录
将 rootfs/var/lib/snapd/void 加入现有 runtime-v1 的精确目录排除列表，保留目录节点、0111 权限，不读取运行时内容；导入同样拒绝该目录下的归档内容。其他位置不可读仍失败，并记录容器内相对路径，避免只有错误码无法定位。禁止 chmod 源目录或扩大到整个 var/lib/snapd。验证使用真实 0111 权限的回归、归档往返及恶意导入；补做设备源目录只读扫描。

## 恢复可用性补全
保留原容器数据及已装包，不调用完整安装器。归档校验和解包后、桌面记录发布前，使用独立 restore-initialize 操作运行本地初始化，按新 alias、已选宿主/容器渲染模式重新验证并生成凭据，复用现有 EGL/组件验证，不执行 apt/pkg/dpkg、下载或刷新软件目录。宿主预检仅在缺失/损坏必要组件时安装，已满足时不联网。
恢复时将指向排除运行时 /run 的 resolv.conf 转为当前宿主 DNS 配置；保留有效普通用户 DNS 配置，拒绝跟随不受支持的链接或符号链接父目录写入宿主。新 alias 渲染状态若初始化失败/取消则清理本任务独占文件；桌面只在离线初始化成功且 DesktopEnvironment 判为已安装后发布。扩展任务日志区分检查宿主、还原容器和本地初始化。
回退只撤销新增恢复初始化调用及辅助逻辑；已有备份格式不变，绝不删除用户原容器/备份。验证同时覆盖 Ubuntu Plasma 与 Debian XFCE 恢复、DNS 运行时链接、保留自定义 DNS、不调用软件包安装、失败不发布、恢复后不再进入完整安装。

## 全新宿主验证工具依赖
仅当 DESKTOP_HOST_GL_MODE 为 zink/llvmpipe 时要求可执行的 $PREFIX/bin/eglinfo。缺失或不可执行时把 mesa-demos 加入宿主标准依赖，经原有安装路径补齐后重新校验；安装后仍缺失则失败，不进入解包。System EGL 不需要此诊断工具，不能因此触发额外安装。覆盖 Plasma 与 XFCE，保留已满足依赖时不联网的约束。沿用原有补齐必要宿主依赖授权，不修改 guest、归档解析或真实设备文件。

## API 入口最终链接的事务修复
只对清单内 libexec/termux-api 的最终路径允许链接存在；matches 对链接返回不匹配，不读取其指向内容。所有父目录及 receipt、其他组件路径继续严格拒绝链接。安装时通过 rename 将链接本身移入任务备份，再发布已验证 ELF；计划记录存在性时识别悬空链接。失败/进程退出回滚按同一父目录约束移回链接，不解引用；任务目录清理只 unlink 链接本身，禁止递归跟随。覆盖相对上游链接、外部/悬空链接、安装失败、进程退出后恢复、原有恶意父目录及恶意归档回归。既有 .deb 宿主包版本不决定 APK 接口匹配，继续以清单哈希为准。

现场验证先保存日志，再 force-stop App；优先在独立测试 prefix 复现真实 API 安装器，然后在现有全新安装状态验证实际初始化派发。禁止清除 App 数据或覆盖原备份。诊断根因及验证范围同步 docs/desktop-testing/fresh-install-restore-progress-20261115.md；产品文档仍待用户验收。

## 2026-11-15 定制宿主组件登记补全（用户已授权“完善”）
恢复预检与完整安装统一在最后一次宿主 apt 操作后用 dpkg 登记 APK 匹配的 termux-api，已安装 UPower 时同样重装定制包；核对 DEB 与已安装版本并 hold，防止普通升级覆盖。仅改宿主包管理，不重装容器或复制容器。失败阻止成功标记，重试可恢复；回退撤销辅助函数并显式 unhold 对应包。无 HTTP 契约变更，api_docs=N/A；product_docs 沿用 Required/Pending，等待用户验收。

## 导入后宿主 KWin 主题初始化崩溃回归
用户报告导入容器无法启动，沿用恢复后可启动的修复授权。现场导入 SUCCEEDED，宿主 KWin 在 Qt QKdeTheme::createKdeTheme/handleThemeChanged 空指针崩溃；受控 A/B 仅设置 QT_QPA_PLATFORMTHEME=generic 即越过该崩溃。仅为 PRoot Plasma 的宿主合成器构造独立环境，保留传给容器的原主题变量和父环境，不更改容器文件、主题、渲染模式或 Native 桌面流程。回退撤销合成器环境副本。api_docs=N/A（无 HTTP 契约），product_docs 继续 Required/Pending，完整验收前不归档。
