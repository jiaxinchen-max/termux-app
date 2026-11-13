# KWin PRoot 渲染选择修复提案

## Why

模拟器上的 KWin Plasma PRoot 已完成包安装，却在初始化时因宿主被默认设为 Zink、且固定要求不存在的 Freedreno ICD 而失败。当前策略只接受 Zink/Zink 与 LLVMpipe/LLVMpipe，宿主 System EGL 缺失，和用户要求的分层独立选择不符。

## What Changes

- KWin PRoot 新增宿主 System EGL，并支持宿主 System EGL/Zink 与容器 LLVMpipe/Zink 的独立组合；现有显式 LLVMpipe 宿主组合暂保留，不静默改写已保存值。
- 新建 KWin PRoot 使用宿主 System EGL、容器 LLVMpipe，设置页面分别展示两层作用及实际后端。
- 去除 KWin 路径把 Zink 等同于 Adreno/Freedreno 的假设；初始化选择并记录明确 ICD，验证实际渲染和窗口链路。模拟器软件 Vulkan 只能标为软件后端，不能冒充硬件加速。
- 驱动不可用明确失败、不自动换渲染器；保留现有容器与配置，重试不重建容器。
- 交付文件仍为 termux-app_apt-android-7-debug_arm64-v8a.apk，必须无 testOnly 安装限制且保持资产外置。

## Capabilities

### New Capabilities

- `desktop-rendering`：补充 KWin PRoot 独立组合、模拟器后端诊断和初始化失败恢复。仓库尚无 baseline specs，沿用活动变更 separate-desktop-host-guest-rendering 的能力名。

### Modified Capabilities

无已归档基线；原活动变更的 Plasma 同配对限制仅在本方案确认后同步取代关系。

## Impact

范围为 DesktopRendererPolicy、创建/设置文案及默认值、desktop_rendering.sh、desktop_initialize.py、安装/启动与容器会话的显式环境交接、相关测试。Activity 展示和初始化任务失败提示变化；Service 监督、X11/输入协议、配置 schema、USB 和资产下载身份不变。没有 HTTP 合约变化，api_docs=N/A；产品文档 docs/product/desktops.md 为 Required/Pending，最终验收后维护。

主要风险是 Vulkan ICD ABI/架构错误、软件被误报硬件、共享策略影响其他桌面。回滚只撤销本变更并恢复原 APK，不清空 prefix 或容器。未经确认不修改业务代码；不自动安装 APK、推送、发布或归档。
