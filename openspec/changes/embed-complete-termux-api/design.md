# 设计

## 固定上游与当前资产
上游 Android 实现固定 fc26ce17e3badf85d4df191af364fcf7798047f1（版本 0.53.0），CLI 固定 9e7f1531e1aa4a9c1e261a2dbde4de93653c366e。45 个 receiver 分发项与 57 个脚本记录在 upstream-inventory.json。实施前保留并核对上游许可证、来源及本地差异。

当前 EmbeddedUsbApi 通过同 UID Unix socket 提供 USB，已从 TermuxApplication 初始化。现有上游设置页仅保存偏好。不得删除 USB 现有工作链路或把现有设置页误判为已实现功能。

## Android 实现与入口
以独立 Android library 模块承载上游 API 实现及其 Activity/Service/资源，适配本地 termux-shared、资源命名、Application 初始化和当前 SDK。主 APK 保留唯一 Application 与现有启动入口，不引入第二个应用或 sharedUserId 依赖。上游独立插件生命周期转换为主应用内模块生命周期。

设置与授权入口使用主包名，按功能请求运行时权限和特殊访问。危险或高权限 API 保留上游操作语义与 Android 系统限制。USB 标准方法转接已有 broker，验证 FD 所有权和退出关闭行为。

统一命令入口显式路由到主 APK。构建配套 CLI 组件补丁，替换硬编码外部接收端并匹配 APK 实现；通过组件版本和保存/初始化阶段安装，不在桌面启动时写脚本或重复预检。注册接收端与连接 socket 均校验调用来源、UID、方法、参数、消息大小；不暴露可被任意应用调用的敏感接口。

## 响应、超时与清理
首批打通 BatteryStatus 的真实 Android BatteryManager/电池广播结果，保持上游 JSON 字段。IPC 连接和服务不可用应有限期失败并关闭请求；权限拒绝、应用退出和组件丢失均不得留下无限 accept/recv 等待及孤儿子进程。传感器、录音、定位、持续媒体等长会话与连接握手使用各自生命周期，不用一个短的总超时截断全部功能。

同时在 UPower 的 Android 后端为电池命令设置有界等待与进程回收，避免 Android API 接收端异常再次阻塞服务注册；刷新不阻塞主线程。静态可用性检查仅在保存/初始化阶段执行。组件内部超时属于实际调用的故障处理。

## 验证
清单逐项标记：实现映射、CLI 路由、权限/硬件、自动或手动证据与设备限制。覆盖普通返回、无权限、无设备、宿主不可达、取消及长会话。不得执行实际拨号、发送短信或通知回复等有外部副作用的操作来代替无副作用测试；这些操作的真实验证需要专门授权。

先复现现存 25 秒 UPower 等待，再验证修复后 BatteryStatus 有界返回、UPower 成功注册、Dolphin 打开不再等待该超时。保留用户已有桌面、已有 USB 和 native/proot 调用回归。普通 arm64 debug APK 保持精确文件名 termux-app_apt-android-7-debug_arm64-v8a.apk，testOnly=false，记录体积增量和签名。

## 受保护路径、回滚与工具限制
受保护：app/src/main/AndroidManifest.xml、Application 初始化、settings.gradle、app/build.gradle、新模块 Manifest/Gradle、IPC native 代码及相关组件打包/安装引用。影响包括权限声明、后台组件、资源合并、依赖体积及 CLI 路由；逐项审查导出组件和授权边界。回滚本变更模块/入口/依赖和配套组件版本，保留既有 USB 及其他脏工作树变更。

OpenSpec CLI、仓库级 Harness 引导文件、strict gate 脚本及 baseline specs 缺失；当前手工生成规划材料，未运行任何不存在的工具，也不标记检查通过。确认后仍须记录可执行测试、构建及代码审查；严格门禁记 Unavailable。

## 已确认的交付方式修订（download-runtime-assets-on-demand）

用户已确认主题与 API 配套资源改为资产库按需下载，取代本文件此前内嵌主题/CLI/deb 的交付要求。Android API 宿主保留 APK；Linux 下随 App 构建配套包与是否打入 APK 分开处理。最终要求及验收场景以 download-runtime-assets-on-demand 的四项规划为准，历史发布与验证记录保留原意，不声称旧 APK 已外置。
