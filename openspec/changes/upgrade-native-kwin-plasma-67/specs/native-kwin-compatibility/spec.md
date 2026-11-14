## ADDED Requirements

### Requirement: 自有合成器候选可追溯

升级候选 MUST 来自固定的自有 KWin 6.7.5 基线及记录的 Android 补丁，并使用同一次构建产生的匹配组件；包版本和实际二进制版本 SHALL 如实表达源码版本。

#### Scenario: 生成候选原生包

- **WHEN** 指定编译机完成候选构建
- **THEN** 应交付 Bionic aarch64 deb、固定源码提交/补丁、配方、构建日志和 SHA256，并核实 kwin_wayland、libkwin 与插件相互匹配

### Requirement: 新旧虚拟桌面客户端协议兼容

候选 MUST 提供 Plasma 6.7 所需的虚拟桌面 v4 功能，同时通过协议协商和 since-version 保护保留较旧客户端的有效行为。

#### Scenario: Debian 按屏幕切换

- **WHEN** 原 Debian Plasma 6.7.4 的真实 Pager 组件在有效输出上请求切换到另一虚拟桌面
- **THEN** 目标桌面 SHALL 实际激活，客户端 SHALL 保持连接且不出现请求至少需要 v4 的版本错误，普通桌面切换也 SHALL 继续成功

#### Scenario: Ubuntu 较旧客户端连接

- **WHEN** Ubuntu 26.04 Plasma 6.6.6 客户端连接候选宿主
- **THEN** 宿主 SHALL 按双方支持版本协商，不发送超过客户端绑定版本的事件，不因候选升级强制容器换包

### Requirement: 显式渲染与 Android 输入保持有效

升级 MUST 保留当前显式渲染策略、真实 renderer 检查和 Android 输入行为，不能重引入自动换 renderer、触摸时间戳恒为 0 或断开后遗留触摸状态。

#### Scenario: 指定驱动不可用

- **WHEN** 明确选择 Zink 但指定 ICD 缺失或实际 renderer 不符合要求
- **THEN** 启动 SHALL 给出失败原因并返回失败，不通过其他 ICD、System EGL 或软件模式接受该次请求

#### Scenario: 正常交互及停止

- **WHEN** 使用已明确支持的模式启动窗口并进行点击、原生触摸，再停止桌面
- **THEN** 输入 SHALL 正常传递且时间戳有效，停止 SHALL 清理输入和渲染资源，不重引入原有退出崩溃

### Requirement: 桌面兼容结论以实际可见功能为准

候选的 Ubuntu/Kubuntu Plasma 兼容性验收 MUST 检查真实桌面功能，不能仅由包版本、socket 出现或服务响应推导。

#### Scenario: 从正常 UI 干净启动

- **WHEN** 每轮测试前 force-stop App、核验归属残留并从保留的 UI 默认入口启动指定容器
- **THEN** 验证 SHALL 分别记录壁纸、面板、Dolphin 窗口、输入、停止和再次启动的真实结果，缺少任一目标功能应如实标记失败或未验证

#### Scenario: 独立故障干扰启动

- **WHEN** 音频监督、PRoot 或 D-Bus 访问出现错误
- **THEN** 记录 SHALL 区分具体故障与升级结果，不把协议修复等同于启动慢或面板问题已经解决

### Requirement: 候选可回滚且资产保持外置

测试 MUST 保留匹配的旧包、相关依赖及配置备份；合成器和主题等大资产 SHALL 保持外置，不要求重新打包进 APK。

#### Scenario: 候选验收失败

- **WHEN** 候选无法正常启动、交互或停止并需回滚
- **THEN** 应停止候选并恢复匹配的 r28 组件及配置，保留两个现有容器和用户 UI 默认模式，核验原二进制版本和入口

#### Scenario: 未获发布授权

- **WHEN** 候选仍处于验证阶段且未获用户发布授权
- **THEN** 正式组件下载 URL 和 App 默认资产指向 SHALL 保持原值，报告 SHALL 明确候选状态与实际测试范围

#### Scenario: 用户授权发布候选

- **WHEN** 用户在候选交付后明确授权发布
- **THEN** 组件 SHALL 以独立 prerelease 发布并同步 App 的 tag、文件名与 SHA256，包含完整固定依赖及真实补丁源码，保留旧 release；发布说明 SHALL 明确设备与兼容性尚未验收，不将公开发布等同于功能验收

### Requirement: Native组件安装闭包完整

Native/System EGL安装 MUST 交付对应版本的libkwin和全部Qt AHB客户端插件、System EGL shim及renderer探针，App必需文件清单 SHALL 与最终deb一致，不能用旧库路径或忽略缺件检查掩盖打包遗漏。

#### Scenario: 全新安装Native Plasma

- **WHEN** 用户通过正常App安装入口安装新的Native/System EGL Plasma组件
- **THEN** 正常包管理安装与最终必需文件检查 SHALL 通过，Qt客户端环境配置 SHALL 找到同次构建的插件和shim；缺失任何必需组件的负向校验 SHALL 失败


### Requirement: Android System GLES缺少原生BGRA读回时保留正确颜色

支持GLES3的Android/System路径在仅缺少GL_EXT_read_format_bgra时SHALL使用经验证的RGBA兼容读回，不谎报原生扩展、不自动切换renderer、不跳过其他基本图形要求。

#### Scenario: 模拟器启动及录屏读回

- **WHEN** 当前模拟器能够创建System GLES3上下文但不提供BGRA读回扩展
- **THEN** KWin SHALL通过其余图形能力检查并启动；QImage录屏路径SHALL使用RGBA后转换为目标格式，保持红蓝、alpha、行距和外部缓冲边界正确，恢复GL状态

#### Scenario: 其他图形要求失败

- **WHEN** 上下文创建、make-current、必要图形能力或shader校验失败
- **THEN** SHALL输出具体阶段原因并返回失败，不将RGBA兼容策略作为通用跳过检查开关

### Requirement: Android模拟器System GLES纹理复用正确

Native/System GLES MUST 在反复绘制不同窗口纹理后继续采样各自内容，不得将最近绘制的窗口拉伸复用到其他窗口。已验证的Android Emulator兼容处理仅重提当前纹理既有过滤参数，不改变其他后端能力和渲染选择。模糊着色器必须能在实际使用的GLSL ES版本编译。

#### Scenario: 红蓝纹理反复采样与桌面画面验证

- **WHEN** 生产GLTexture上传红、蓝图像并按红、蓝、红顺序绘制
- **THEN** 每次绘制结果与对应源纹理一致，源图像不串用，GL无新增错误
- **AND** 干净进程正常UI启动后分别检查桌面、面板和Dolphin的实际画面，不能仅凭进程/启动日志判定成功

### Requirement: Native Qt System EGL身份可运行校验

私有System EGL包装库 MUST 在最终动态符号表导出身份函数，AHB客户端 MUST 从实际装载句柄解析并验证其值，不得移除校验或隐式回退到Mesa。

#### Scenario: Plasma延迟加载图形特效

- **WHEN** 全新Native/System EGL桌面加载Qt5Compat GraphicalEffects并创建Qt OpenGL上下文
- **THEN** 私有EGL身份函数 SHALL 可解析，Qt客户端正常创建上下文，不因undefined symbol主动终止plasmashell；真实旧r3包的符号检查 SHALL 失败

#### Scenario: AHB窗口缓冲导出协议一致

- **WHEN** 实际Qt5Compat窗口向合成器导出AHB缓冲
- **THEN** 客户端 SHALL 使用服务端同一XML生成的接口，发送完整id/socket/flags参数，flags=0保留alpha和原生通道；连接与进程正常退出，不出现invalid arguments

### Requirement: App 创建 Ubuntu 26.04 Plasma 容器

App 新建 PRoot Plasma SHALL 使用 Ubuntu 26.04 resolute 底包及 Plasma 客户端，实例命名、安装、初始化和归档发行版判断 MUST 一致，不得继续传入或接受 Debian forky 作为本配置。Native 和其他桌面的默认底包 MUST 保持各自配置。

#### Scenario: 全新创建并初始化 Plasma

- **WHEN** 用户在新安装 App 中创建 PRoot Plasma
- **THEN** 安装请求 SHALL 为 ubuntu:resolute，别名以 ubuntu-kwin-plasma- 开头，实际 rootfs 的 ID 和 VERSION_CODENAME SHALL 为 ubuntu/resolute
- **AND** Debian、Ubuntu noble 或错误发行版 SHALL 在初始化和归档恢复校验时被拒绝，不得写入安装完成状态

#### Scenario: 交付可正常安装的修复 APK

- **WHEN** 构建修复 APK
- **THEN** APK SHALL 保留原签名、arm64-v8a、testOnly=false 和约43MiB外置资产体积，并包含实际Ubuntu Plasma配置
