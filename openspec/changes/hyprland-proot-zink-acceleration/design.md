# 设计

## 已核对事实
KWin在宿主运行合成器并将Wayland运行目录绑定给容器。其容器Zink选项安装完整mesa-for-android-container组件，使用Turnip Vulkan ICD，Qt Quick设置QSG_RHI_BACKEND=vulkan；软件模式显式指定llvmpipe。只换Turnip可能因EGL/GBM不匹配导致Wayland WSI崩溃，因此必须使用匹配的完整Mesa。
现有Hyprland PRoot启动包装器只配置Wayland和音频。既有模拟器探测记录release-r7/fp16-capabilities.json明确dma_buf_import=false、dma_buf_format_query=false。Aquamarine输出AHardwareBuffer不冒充Linux dma-buf；客户端缓冲导入与最终输出是不同能力，不能由输出缓冲类型直接推断输入能力。

## 模式与界面
复用现有DesktopGlMode.ZINK，仅在PRoot Hyprland开放；Native Hyprland仍只允许SYSTEM。对Hyprland PRoot使用专用标签和提示，明确Zink为容器应用实验选项，宿主固定System EGL。旧SYSTEM记录保持原行为，不做自动迁移。创建/设置/安装/启动使用一致模式校验，不开放Hyprland LLVMpipe枚举；现有SYSTEM客户端行为不强制改为llvmpipe。

## 安装与组件身份
复用KWin完整Mesa/Turnip思路，使用现有desktop_install_mesa.py的固定trixie ARM64组件SHA256与严格路径/文件核验，复用GNOME已有的固定下载核验流程，抽出共享安装函数并保持原调用语义。为Zink容器补齐Vulkan loader、Mesa依赖和诊断工具，检查ldd及ICD，锁定被覆盖的Mesa包。仅Zink选项安装此覆盖；默认模式不额外注入驱动。不得使用KWin旧流程的未校验curl解包替代已有严格安装器。

## 启动与环境边界
DesktopLaunchSpec单独输出Hyprland宿主system与容器default/zink契约。desktop_start.sh和desktop_install.sh在同一规则下允许PRoot Zink并拒绝Native Zink，宿主实际渲染继续system。desktop_hyprland.py为当前容器会话生成受控包装器：清理Bionic LD_LIBRARY_PATH/LD_PRELOAD、旧Vulkan/Mesa/Qt渲染变量和宿主XDG路径；使用容器内ICD；GL程序显式选择Zink，Qt Quick使用Vulkan并保持Wayland。所有容器应用继承同一策略，不能只设置桌面进程。

## 验证与失败语义
在隔离诊断中核验KGSL设备访问、ICD真实加载、物理GPU身份和合成器实际导入扩展/格式。仅文件存在不等于硬件能力。Zink路径需运行真实Wayland EGL/OpenGL或Vulkan窗口，并记录renderer；zink(llvmpipe)/lavapipe不得算硬件通过。客户端GPU渲染与向Hyprland显示窗口必须分别验证。不支持时给出具体日志与切回原模式建议，不自动降级。显示链路检查应按实际能力区分EXT_image_dma_buf_import和modifier查询，不仅凭缺少DRM render node拒绝KGSL设备。

## Activity/Service及其他边界
现有Activity/Service会话监督和输入机制保持；不引入新的Android Service、X11显示服务或HTTP接口。音频继续宿主PulseAudio，桌面持久目录与短路径Wayland socket保持隔离。所有新诊断/客户端进程携带当前会话token，停止时一并回收。用户hyprland.lua、主题文件与容器业务数据不覆盖。X11/PRoot/KWin/GNOME旧路径回归覆盖。

## 受保护路径与回滚
策略来源：/home/user/workspace/AGENTS.md的人类检查点与业务代码实施约束；本checkout无可读取的额外policy id。
涉及desktop_install.sh、desktop_start.sh、desktop_hyprland.py、DesktopRendererPolicy.java、DesktopLaunchSpec.java、DesktopEnvironment.java、创建/设置标签及所需测试。共享Mesa函数必须验证GNOME旧调用不变；KWin既有流程保持。
回滚：设置切回SYSTEM、停止并重开；代码恢复本轮变更前快照；容器Mesa替换记录原文件身份和包hold状态，不能通过盲目apt升级恢复。正向真机未通过时交付标注实验性，不宣称已解决硬件加速。

## 流程与交付
Stage 1/2规划确认后实施。本地最小测试覆盖模式矩阵、启动环境、安装失败无成功标记、共享安装兼容性、配置保留和清理。构建ARM64 APK，保存签名与哈希，检查打包源码一致性。模拟器只完成负向能力与默认路径回归；真机GPU正向验证Pending。HTTP文档N/A；产品文档Required/Pending，目标docs/product/desktops.md。严格Harness工具缺失，记录Unavailable。
