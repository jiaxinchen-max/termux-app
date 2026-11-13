# KWin PRoot 渲染修复设计

## Context

设备 120.24.191.156:8057 已授权。只读证据位于 artifacts/kwin-plasma-proot-investigation/device-desktop-logs.txt、device-renderer-state.txt、device-kwin-rendering-env.txt。已安装 forky Plasma 6.7.4，当前配置 Zink/Zink。最终失败是宿主固定 Freedreno ICD 不存在，宿主实际只有 lvp ICD；不是 apt 安装 Plasma 包失败。容器自身同时有 Freedreno 与 lvp 目录文件，但文件存在不能证明对应 GPU 可用。

DesktopRendererPolicy 与 desktop_rendering.sh 同时限制 Zink/Zink、LLVMpipe/LLVMpipe；创建默认 Zink/Zink，文案也宣称必须匹配。安装/启动已有独立宿主和容器字段，数据模型无需变更。固定 r28 宿主脚本默认 Turnip，并以硬件 Adreno 为 Zink 探针判据；仅删除 Java 校验不能解决问题。

## Goals / Non-Goals

目标为新增 System EGL/Zink 宿主与 LLVMpipe/Zink 容器的四组合，按实际能力验证模拟器路径；保留已有显式宿主 LLVMpipe 组合，避免改写配置。新建使用 System EGL/LLVMpipe，创建和设置仍可显式改选，无联动改值。

不修改 Service 生命周期、X11/输入协议、schema、归档身份、USB、API 外置资产、其他桌面硬件约束。不用自动回退或跳过初始化伪造成功。未授权设备写入、安装 APK、启动会话、推送、发布与归档。

## Decisions

1. Java 策略与 shell 白名单同步，创建/设置选项与文案同源语义；默认改为 System EGL/LLVMpipe。按宿主安装依赖/选择 Bionic EGL/ICD，按容器安装依赖/选择 glibc EGL/ICD。禁止由 DESKTOP_COMPONENT_RENDERER 隐式覆盖另一层。
2. 初始化区分 KWin 的通用渲染验证与 Hyprland 的 Adreno 专属实验约束，不放宽其他桌面的既有硬件要求。System EGL 路径不要求 Freedreno；Zink 验证先固定实际后端、ABI/架构和库路径，再验证 EGL 与真实窗口结果。
3. 模拟器 Zink 首先评估其现有 Lavapipe 后端；显式选择 Zink 仍是 Zink，但诊断必须记录 Vulkan 使用 CPU。真机 Turnip 硬件路径保持明确身份与验证。不能因 lvp 文件存在就标记支持，也不能把缺少 Freedreno 一律当缺包自动安装。没有可运行后端时给出具体错误，并保留用户选择。
4. 运行时传递已初始化且明确选择的 ICD，宿主 Bionic 与容器 glibc 不能混用。若需适配 r28 helper，只在 App 管理的派生脚本以固定源 SHA 和精确变换实现，初始化执行探针，启动复用结果；不修改原 deb 文件，不重编本地原生包。如果证据表明需要原生补丁，另列范围确认后到指定编译机编译。
5. 初始化失败保持无成功 marker，既有 prefix、容器和用户配置保留。修复后通过原有显式重试入口继续，不强制重装整套 Debian。

## Risks / Trade-offs

- 软件误报硬件：日志记录 EGL renderer、ICD 与 CPU/GPU 属性，软件 Zink 不使用硬件加速措辞。
- 跨 ABI 或错误 ICD：限定受信任系统/已安装包身份，校验 ELF 架构与库路径；坏身份失败。
- 两层变量污染：分别清理环境并按层注入，测试四组合及已有 LLVMpipe 配对，拒绝容器 System。
- 共享策略回归：GNOME/XFCE/Hyprland 既有测试必须通过，不改变其能力白名单。
- 模拟器窗口链路尚未验证：静态和离屏测试不能冒充图像/输入验收；授权部署后才验证。

## 受保护范围、验证和回滚

工作区 AGENTS.md 要求 Requirement confirmation after Stage 1/2 及 Protected path approval before modifying high-risk files。仓库无保护策略清单，policy id 不可用，保守纳入 DesktopRendererPolicy、DesktopCreateActivity、DesktopSettingsDialog、desktop_rendering.sh、desktop_initialize.py、desktop_install.sh、desktop_start.sh、desktop_container_session.sh 及中英文文案/相关测试。影响为选项、渲染环境、初始化状态。回滚撤销本变更增量，恢复旧 APK，不清空 prefix/容器，保留其他已批准未提交改动。

验证覆盖四组合保存/规格/启动环境、实际后端身份、缺 ICD/错误 ABI/渲染失败、容器不重建和失败 marker、既有桌面回归。构建需显式 android.injected.testOnly=false，最终 APK 校验普通可安装标记、证书、ARM64 和资产外置；安装及图形/输入测试另外需要设备动作授权。Harness strict-run 和引导文件缺失，状态 Unavailable；OpenSpec 校验可用。API 文档 N/A；产品文档 Required/Pending，验收后才维护 canonical 声明。
