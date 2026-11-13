# 验证与交付记录

用户补充“不用考虑兼容旧数据”；仅实现当前格式，不增加旧数据迁移。

本地验证：103 项桌面 JVM、22 项初始化、4 项跨桌面渲染策略、10 项非交互 dpkg、57 项 Hyprland Python 和 7 项共享容器 apt 测试通过；Python AST、五个 shell 语法、OpenSpec strict 和 git diff --check 通过。不是全仓测试通过：早期桌面脚本有夹具/前置环境失败，见下方说明。Harness strict-run 缺失，状态 Unavailable。

| 宿主 / 容器 | 当前策略、按层命令和失败状态 | 模拟器真实窗口/输入 |
| --- | --- | --- |
| System EGL / LLVMpipe | 通过 | 未执行 |
| System EGL / Zink | 通过 | 未执行 |
| Zink / LLVMpipe | 通过 | 未执行 |
| Zink / Zink | 通过 | 未执行 |

设备只读探测确认宿主 Zink over Lavapipe 离屏可用，明确为 CPU 软件 Vulkan。容器 EGL 存在旧覆盖与 LLVM ABI 不匹配；发行版 Mesa 重装流程已在代码及命令路由测试覆盖，尚未在设备修改包。设备安装与窗口验收保持 Pending。

APK：termux-app_apt-android-7-debug_arm64-v8a.apk，45,354,395 字节（43.253 MiB），SHA256 c20bae8b1ab753fa2b617a4f44b1ef5cb4a4c1d22abfa8ae5f6bafa1a8c253e5。验证只有 ARM64、无 testOnly 限制、签名与上一版相同；主题及 API deb 未重新嵌入。已完整 HTTP 下载回读并比对 SHA256。构建复用已有原生库，没有重编本地原生包。

2026-11-13 用户授权提交、推送本次会话代码；没有授权归档或设备部署。产品文档一致性仍 Pending。

# 其他桌面的同类检查

本次只实现 KWin PRoot 修复；用户补充“不用考虑兼容旧数据”，不增加旧数据迁移或兼容路径。以下为源码与自动化检查，不能替代各桌面在模拟器上的图像、输入验收。

| 桌面 | 当前选项与执行约束 | 结果与边界 |
| --- | --- | --- |
| KWin PRoot / Debian forky | 宿主 System EGL/Zink 与容器 LLVMpipe/Zink 独立四组合；新建 System EGL/LLVMpipe | 本次修复。模拟器 Zink 明确采用 CPU Lavapipe，原容器内恢复匹配发行版 Mesa；逐层探针失败不写成功状态。 |
| Debian GNOME PRoot | System/LLVMpipe，或 Zink/Zink；Xlorie 分别使用 native、dma-buf 缓冲 | 两组限制涉及显示缓冲，不直接复制 KWin 四组合。Zink 初始化同样要求 Freedreno/Adreno，因此模拟器的软件 Vulkan 路径仍不受支持；可选择已有 System/LLVMpipe。 |
| Ubuntu noble GNOME PRoot | 固定 System/LLVMpipe，使用独立 Ubuntu 会话/扩展验证 | 未发现 KWin 式“只剩 Zink”的选项问题。没有扩大选项白名单。 |
| 原生 GNOME / Ubuntu 外观 | 固定 Android System EGL；无容器渲染层 | 没有宿主与容器配对问题。 |
| Hyprland r7/r8 | 宿主固定 System EGL；PRoot 可选默认或实验 Zink 客户端 | 实验路径检查 KGSL、Adreno 和 dma-buf 导入能力，属于已有硬件能力约束；模拟器不能据此宣称支持。57 项相关 Python 回归通过。 |
| XFCE native / PRoot | 宿主 System/Zink/LLVMpipe；PRoot 容器默认渲染 | 同样发现 Zink 初始化固定 Freedreno/Adreno；安装器未按 XFCE 的宿主 Zink 选择补齐完整 Vulkan 包。这是尚未解决的独立能力/依赖缺口，不能宣称模拟器 Zink 可用。本次未扩大其范围，已有 System 选项保留。 |
| 原生 KWin | 宿主 System/Zink/LLVMpipe，无容器层 | 没有独立双层选项问题；原有 Zink 仍要求 Freedreno/Adreno，未在本次 PRoot 修复中放宽。 |

源码证据：`DesktopRendererPolicy.java` 的 `hostModes/guestModes/supports`；`desktop_rendering.sh` 的白名单；`desktop_initialize.py` 的 `initialize/check_egl/plasma_rendering`；`desktop_install.sh` 的宿主依赖选择；`desktop_gnome_launch.py` 的 X11 缓冲配置；`desktop_hyprland_gpu.py` 的 `validate_host/require_hardware`。

当前格式回归：103 项桌面 JVM、22 项初始化、4 项跨桌面渲染策略、10 项真实 dpkg conffile 非交互检查通过。较早的脚本回归有独立夹具缺陷：`test-desktop-scripts.sh` 仍模拟 bin/start-plasma，而 HEAD 已调用 libexec/termux-desktop/start-plasma；恢复脚本模拟器未接受 HEAD 已存在的 prepare-plasma 调用；GNOME/Ubuntu 夹具未准备当前安装路径/外置 API；native Ubuntu 缺少测试 bundle 环境变量。原始失败日志保留，未将这些套件记为通过，也没有为其添加旧数据兼容。
