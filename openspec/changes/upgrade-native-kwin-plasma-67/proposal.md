## Why

当前自有 KWin r28 的实际源码版本为 6.6.7。此前已用原 Debian Plasma 6.7.4 的真实 Pager 组件复现按屏幕切换虚拟桌面时 v3/v4 请求错误；用户希望升级自有合成器并验证 Ubuntu/Kubuntu Plasma 兼容性。Ubuntu 26.04 测试容器的 Plasma 为 6.6.6，尚无证据证明其面板缺失或启动慢由 KWin 小版本不同引起。

## What Changes

- 以自有仓库 `Plasma/6.7` 分支的 6.7.5、固定提交 `a73f115af613b880dc1b11059f62b252cb94b2af` 为候选基线，迁移当前 r28 必要 Android 修复，再构建真实版本一致的 Bionic aarch64 KWin 候选包。
- 验证宿主提供虚拟桌面协议 v4，Debian 6.7.4 真实组件的普通及按屏幕切换均正常；同时验证 Ubuntu 26.04 Plasma 6.6.6 客户端能够协商旧协议、显示窗口及使用桌面。完整 Kubuntu 镜像未安装，不将 Ubuntu Plasma 测试扩大为完整 Kubuntu 全功能认证。
- 保留显式宿主 System EGL/Zink 与容器 LLVMpipe/Zink 的独立策略、真实渲染器检查、输入通道、原生触摸时间戳和退出清理修复。不得直接使用已出现回归的未迁移 6.7 分支。
- 每轮功能验证前 force-stop App、核验残留，再从用户保留的 UI 默认入口启动；记录面板、Dolphin、切换、输入与停止的实际结果。启动慢和 D-Bus 会话访问问题单独判定，不将升级版本视为修复证明。
- 本轮交付源码/配方差异、候选 deb、依赖和验证记录、可恢复的 r28 备份。正式发布和 App 资产 URL/哈希切换在候选验收后处理；不将 deb、主题或其他大资产加入 APK。

非目标：本轮不升级容器发行版、不重装或删除现有容器，不扩大共享整条宿主 D-Bus，不伪造缺失服务，不更换 Qt 主版本，不承诺升级自动修复音频监督、PRoot 断言或桌面启动速度。没有获准的 Git 提交、推送、PR 或正式发布。

## Capabilities

### New Capabilities

- `native-kwin-compatibility`：自有 Android KWin 候选升级、跨 Plasma 6.6/6.7 的协议兼容、显式渲染与输入保留、候选验证和回滚。当前 `openspec/specs/` 没有已归档基线，因此新增变更内需求；已有活动变更的渲染规则保持有效。

### Modified Capabilities

无已归档基线规格变更。

## Impact

主要涉及外部 `jiaxinchen-max/kwin` 的 Android 后端、Wayland 版本保护与包装脚本，以及指定编译机原 KWin 编译链中的候选配方。当前 App 工作区已有未提交业务修改，必须原样保留；APK 本身不是合成器升级的必然交付物。

无 HTTP 合约变更，`api_docs=N/A`。这是用户可见兼容性变化，`product_docs=Required`，目标为 `docs/product/desktops.md`；该目录目前不存在，最终用户验收后创建/维护，规划阶段不写为已支持。主要风险是 6.7 分支遗漏 r28 修复、原生 ABI/依赖变化、旧客户端收到不支持事件，以及测试中的音频/临时目录干扰。回滚需恢复完整匹配的 r28 包与配置，不能仅替换单个 libkwin。


## 2026-11-14 发布授权补充

用户在候选交付后明确回复“发布”，授权将当前 6.7.5-1 aarch64 候选发布至 jiaxinchen-max/kwin，并同步 App 的下载配置。此次发布使用独立 tag termux-hwaccel-6.7-r1、真实补丁源码提交及 GitHub prerelease；源码 tag 推送属于该组件发布范围，App 仅同步当前工作树配置及对应验证夹具，不推送其他未提交工作。所有原 r28 共享依赖按原哈希复制到新 release，增加 libei，旧 release 保留。

发布核验包括源码对应、全部 App DEBS 的公开 URL/大小/SHA256、安装脚本回归及元数据检查。公开发布不等于设备验收：不安装模拟器，不宣称解决启动慢或通过 Kubuntu/Ubuntu 桌面验收；产品文档仍 Pending，不归档。若发布失败，保持草稿并修复；若需撤回 App 指向，可恢复旧 tag、旧 KWin 包和原 DEBS 清单。


## 2026-11-14 全新Native安装修复授权

用户在两项故障及修复范围说明后明确回复“修复”，授权修复旧libkwin路径和遗漏的Qt AHB/System EGL/renderer probe交付组件。沿用本变更已批准的指定Linux编译机、原KWin build-package.sh、既定组件发布与APK交付流程；新包使用6.7.5-2与独立termux-hwaccel-6.7-r2 prerelease，保留r1历史资产，不覆盖已发布二进制。修复只补齐本来就要求的Native/System EGL能力，不增加Ubuntu/Kubuntu创建入口，不兼容旧数据。

实现将tools/termux-qt-ahb-plugin作为显式可选CMake子目录纳入原构建，原配方开启该选项并固定完整源码及SHA。同步App HOST_FILES到最终deb实际文件及哈希，保留缺件失败检查，为libei运行库补充必要项。新增包级真实文件校验覆盖错误库版本和缺失Qt辅助文件，避免仅靠模拟夹具放行。若编译发现Qt私有API或依赖差异，仅按实际报错适配且留证，不能把旧版二进制拷入新包。

设备验证范围为现有模拟器上用户已失败的native/system桌面：保留配置和已安装包记录，修复文件通过模拟器下载后标准安装；验证每轮先force-stop、核验归属残留、从正常UI重新进入。先验证安装完成及System EGL初始化，不把这次修复写为容器兼容性或启动速度验收通过。不清数据、不删容器、不卸载App。APK构建保持既定arm64文件名、原签名、testOnly=false和资产外置。

保护路径影响为KWin构建入口、既有配方和App安装清单。无实际保护路径policy可用，Harness脚本仍缺失。回滚保留r1/旧r28包和旧配方/源码归档，用户设备当前包及配置先备份；源与App未提交改动不覆盖。产品文档保持Pending，不归档。


## 2026-11-15 已批准的Native GLES读回兼容修复

用户在已验证BGRA扩展缺失及RGBA读回可行的具体方案后回复“修复”，批准KWin OpenGL核心能力检查、必要读回转换和诊断日志修改，以及原编译链新revision、当前模拟器干净UI验证；沿用既有组件发布/App同步/APK交付授权，不覆盖r2二进制，不推送App其他工作。根因与真实像素证据见 /home/user/workspace/artifacts/native-plasma-desktop-exit/bug-candidate.md。

修复保持真实能力报告：新增原生BGRA读回能力查询，仅Android/System GLES且GLES>=3的既定路径允许RGBA兼容读回，其他后端原有必需能力检查保持。审计现有BGRA调用点：DRM CPU导入属于未放宽的DRM后端；Android可能经过的录屏QImage读回使用RGBA临时图像，按原目标格式转换并逐行拷贝，保留外部目标缓冲和行距、预乘alpha。截图等既有RGBA路径不变。转换时保存并恢复GL pack状态/PBO绑定，临时像素分配失败时明确失败，不读取或覆盖缓冲边界，不伪造扩展。

补充上下文创建、make-current、基本能力及shader校验失败日志。验证使用原编译链编译的独立原生工具调用真实EglContext和实际生产读回函数，覆盖RGBA颜色、BGRA替代转换、外部带padding目标及边界哨兵、alpha、翻转/GL状态恢复；原生工具仅用于取证，不安装进正式deb或APK。还需实际Native/System EGL UI的桌面/面板/Dolphin/截图与重启验证。仅编译成功不记为运行通过。

保护路径影响为OpenGL核心和录屏辅助路径，已由本次明确“修复”确认。回滚保留完整r2包、源码、配方及设备配置/包清单；不清数据、不换用户渲染模式。无HTTP合约，api_docs保持N/A；用户可见兼容性product_docs保持Required/Pending，验收前不归档。Harness与OpenSpec CLI均缺失，记录Unavailable，不宣称strict gate通过。

## 2026-11-15 r3交付后黑屏的同范围修复

用户报告App存活但桌面黑屏，继续既有Native/System EGL可用性修复授权，不增加产品能力。现场新UID10202，KWin12409存活，plasmashell退出；两份Android崩溃记录显示Qt5Compat GraphicalEffects延迟加载进入AHB插件后SIGABRT。实际r3 shim动态符号表缺少termux_system_egl_dispatch，独立ctypes按正确LD_LIBRARY_PATH加载后可复现undefined symbol。该函数未显式导出，继承顶层隐藏可见性，EGLAPI导出的eglGetDisplay仍存在。此前可见桌面通过不覆盖实际AHB特效延迟加载，完整Native功能继续视为未验收。

仅为身份函数增加default visibility，保留显式renderer和identity检查、不允许失败回退；包门禁读取实际ELF动态符号表，使用旧r3负向证明；验证真实Qt客户端和Qt5Compat特效加载，并做干净UI启动及延迟稳定性检查。沿用原编译机build-package.sh生成6.7.5-4与独立r4 release，同步App固定文件/SHA和同名原签名APK。保留r3旧包/配置回滚，不覆盖历史资产、不推送App脏树。api_docs N/A，product_docs Required/Pending；Harness门禁Unavailable。

### 实际Qt特效验证追加协议修复

身份导出修复后，真实QML GraphicalEffects渲染触发Wayland invalid arguments：手写客户端create_buffer签名nh，而同包服务端XML为nhu，缺少flags。候选未发布。移除重复手写协议描述，利用现有ECM/WaylandScanner从服务端同一份XML生成私有客户端代码，发送明确flags=0（保留alpha、原生通道顺序）。仍按v1绑定，不扩大事件能力；保持既有AHB缓冲生命周期和渲染模式。Qt5Compat真实窗口测试必须覆盖完整缓冲导出和正常退出，定时器打印成功不能覆盖Wayland连接失败。该修复仍属于既定Native/System EGL桌面可用性范围。

## 已批准的 App Ubuntu Plasma 底包修复

用户指出全新安装仍为Debian，随后明确“修复”，批准将App新建PRoot Plasma正式入口改为Ubuntu 26.04（resolute）+ Plasma。此追加授权取代此前“不改变默认底包”的范围限制；Native Plasma、其他桌面、原生KWin r4资产保持现状。同步创建目录、启动规格、真实os-release校验、初始化及容器归档分发校验，不能仅改显示名称。保留容器内独立总线与宿主合成器边界，不考虑旧数据迁移，不删除已有容器。提供同名、原签名、testOnly=false的arm64 APK供全新安装。api_docs为N/A；product_docs继续Required/Pending，设备完整Kubuntu兼容性不由构建成功推导。
