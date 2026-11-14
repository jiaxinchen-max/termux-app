# Native System GLES 桌面退出修复 r3

KWin 6.7.5-3 修复模拟器缺少 BGRA 读回导致初始化退出、纹理再次使用时采到旧内容和 GLSL ES 300 模糊着色器兼容问题。模拟器 Native/System EGL 的壁纸、面板、Dolphin 实际窗口及 force-stop 后正常 UI 冷启动可视检查通过；31项真实GLES验证通过。Dolphin按现有会话环境启动，未验证触控入口。状态文件仍为starting，与实际画面和存活进程分开记录。

指定编译机原 build-package.sh 构建退出0；3326个源码文件与组件提交 e73633ef3f6ebc23f04735121861fcfb8de6f365 匹配。组件 prerelease：https://github.com/jiaxinchen-max/kwin/releases/tag/termux-hwaccel-6.7-r3 。App 使用r3与真实包SHA；真实包门禁、初始化23项、非交互安装10项、Hyprland6项通过。

设备证据与发布/验包校验位于 /home/user/workspace/artifacts/native-gles-readback-fix 。Kubuntu、真实硬件、输入、停止、回滚及完整回归未通过验收，未宣称启动速度改善。产品文档保持Pending；Harness strict因脚本缺失为Unavailable，未归档。App工作树未提交推送。
