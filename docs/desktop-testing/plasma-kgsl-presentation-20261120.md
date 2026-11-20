# 小米 PRoot Plasma 硬件呈现修复

## 原因与变更

小米 Adreno 660 的容器 Turnip Vulkan 枚举正常，但 System EGL 宿主关闭 linux-dmabuf；容器日志有 `MESA-EGL: warning: egl: failed to create dri2 screen`。App 同时强制 Qt Quick 使用 OpenGL。KWin r5 源码 `packaging/container/plasma-container-session.sh` 已记录 KGSL 无 DRM render node 时 EGL-Wayland 可能通过 swrast/wl_shm 呈现黑帧的问题，组件原始方案使用 Vulkan RHI。

- 容器 Plasma GPU 模式恢复 Vulkan RHI，使用初始化指定的同一 Turnip ICD；普通 OpenGL 客户端仍使用 Zink。
- Java、Shell 和初始化校验统一要求容器 Zink 配宿主 Zink，提供 dma-buf 共享通路；拒绝 System + Zink 组合。容器 LLVMpipe 仍可搭配三种宿主模式。
- 更新中英文设置说明。旧配置仍可读取和编辑，不自动切换用户选择。

## 验证

通过：

- `python3 termux-ui/scripts/test-desktop-plasma-rendering.py`：3 项，执行真实容器环境配置，检查 Vulkan RHI、独立 ICD、OpenGL Zink 与软件分支。
- `python3 termux-ui/scripts/test-desktop-rendering.py`：5 项，枚举组合、非法组合拒绝及不停止既有会话。
- `python3 docs/desktop-testing/test-desktop-initialization.py -k plasma`：12 项。
- `python3 docs/desktop-testing/test-container-plasma-startup.py`：11 项，会话监督与清理。
- 独立 javac/JUnit 执行 `DesktopRendererPolicyTest`：5 项。
- 修改的 Shell 语法检查、`git diff --check`。

完整初始化脚本还有过期用例失败，包括旧 r4 安装夹具、旧发行版预期、缺失的 Hyprland 主题构建产物及已变化的 GNOME 接口；未将完整脚本计为通过。

真机读取确认容器 Turnip 可识别 Adreno 660、容器具有 Qt 所需的 `libvulkan.so`；宿主尚无 `mesa-vulkan-icd-freedreno`，直接宿主 Zink 探测失败。ADB 间歇报协议错误，本次尚未完成真机修复后的桌面出图与鼠标残影验证。

## 使用与回滚

停止桌面后，在新版 App 的 Plasma 设置中将宿主与容器都选为 Zink，保存并让安装流程补齐宿主 Turnip 依赖，再启动。无需删除容器。LLVMpipe 可作为用户明确选择的软件模式。

回滚代码可恢复此次提交；未修改设备上的容器、已安装组件或用户配置。
