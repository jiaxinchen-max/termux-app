# 完整内置 Termux:API 并修复电池查询阻塞

## 当前问题与目标
当前主 APK 仅实现独立的 EmbeddedUsbApi，包括 USB 枚举、授权与文件描述符传输。设置页中的 Termux:API 名称与偏好存储并不代表其服务实现已集成。没有 BatteryStatus 分发器及标准 TermuxApiReceiver；命令行客户端仍将广播发给 com.termux.api/.TermuxApiReceiver。

设备未安装外部插件。UPower 同步调用 termux-battery-status 后，termux-api BatteryStatus 等待不存在的接收端，UPower 无法注册服务，Dolphin 同步等待约 25 秒。原始调查见 /home/user/workspace/artifacts/dolphin-startup/diagnosis.md。

将上游当前固定版本全部 45 个接收方法、57 个命令脚本对应能力接入主 APK，命令可直接调用内置实现。保留已有 USB FD 传递和授权行为，补齐标准调用入口。以 BatteryStatus → UPower → Dolphin 为首个端到端验收路径；其他功能按完整清单逐项验收，不以仅注册方法或返回统一空值计为实现。

## 范围和边界
覆盖上游电池、音频、亮度、相机、剪贴板、联系人、通话与短信、对话框、下载、生物识别、红外、任务调度、密钥、定位、媒体、录音、NFC、通知、SAF、传感器、分享、存储、语音、USB、振动、音量、壁纸及 Wi-Fi。基线详见 upstream-inventory.json。

按需请求 Android 权限；没有硬件、权限被拒绝或系统禁止的功能应返回可识别错误。保持主 APK 的包名、签名、最低 Android 版本；不进行旧数据迁移。接口输入、输出及取消/停止行为参照固定版本上游。

包含命令行组件的配套源码修改、可复现构建和组件版本引用，避免只有 Java 实现而 CLI 仍发送到旧外部插件。不在启动桌面时重复检查或修复组件。

## 文档分类
api_docs 为 N/A：本次为 Android 本地 IPC 与 CLI 契约，没有 HTTP API 变化。product_docs 为 Required，验收后更新 docs/product/android-api.md、docs/product/desktops.md；当前目录缺失，一致性保持 Pending，未验收能力不得写为已实现。

## 审批与风险
Manifest 中敏感权限与组件、Gradle 模块依赖、进程初始化、同 UID IPC 和组件安装配置均列为受保护修改。需要确认本提案及 design/specs/tasks 后实施；不含 Git 提交、推送或组件发布授权。权限只在用户调用对应功能时申请。
