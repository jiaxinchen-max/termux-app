# Hyprland PRoot复用Mesa/Turnip加速方案

## 背景与目标
用户要求“复用一下，万一真机他能用呢是吧”，希望复用现有KWin容器GPU方案，让支持的真机尝试Hyprland容器应用硬件加速。当前仅宿主合成器使用System EGL，容器只接入Wayland和音频，不能据此宣称应用已加速。

本轮增加显式选择的PRoot Zink实验模式，复用完整且匹配的glibc Mesa/Turnip组件及安装核验；宿主仍使用当前Android/System EGL合成器。原生桌面和现有PRoot默认模式保持兼容。对支持Adreno/KGSL且具有所需缓冲导入能力的设备提供可诊断的尝试入口，不承诺所有真机适用。

## 用户可见行为
PRoot Hyprland可选择实验性Zink（仅容器应用），说明需要兼容Adreno/KGSL和显示链路；选择后重新安装/补齐组件并停止重开桌面。现有System EGL选项继续表示宿主合成器，明确不保证容器应用GPU渲染。安装、驱动或缓冲能力不满足时明确报告具体失败，不偷偷改为软件模式或显示“已硬件加速”。切回原模式后可恢复原有客户端行为。

## 非目标
本轮不适配Mali、不实现VirGL、不强行注入Android Bionic EGL到glibc、不更换宿主合成器渲染器、不新增Hyprland/Aquamarine二进制后端。若真机System EGL无法导入所需缓冲，记录不支持；增加另一种宿主渲染路径需另行方案确认。不更改KWin既有行为，不覆盖用户主题和桌面配置。

## 风险与回滚
GPU驱动、dma-buf导入、格式和同步均可能限制兼容性；模拟器已知不支持dma-buf导入，不能当作Adreno正向验证。保留原模式可直接切回，不卸载容器用户应用；覆盖组件使用固定身份与严格路径核验，保留安装日志。已有七个dirty native子模块不修改。

## 文档与授权边界
本轮无HTTP API，api_docs为N/A。用户可见兼容性变化使product_docs为Required，目标docs/product/desktops.md，当前Pending，最终验收后维护。方案/设计/规格/任务进入Stage 1/2确认；确认后实施、构建和聚焦验证。不包含新的Git提交/推送、发布或设备部署授权。完整Harness与OpenSpec CLI缺失，strict gate保持Unavailable。
