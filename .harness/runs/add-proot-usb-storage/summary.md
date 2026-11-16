# 外接 USB 调研与候选规划

用户请求容器支持外部 USB，设备类别尚待异步澄清。已调研内置 USB API、桥接、四类桌面入口、安装及会话边界，并提出四类 PRoot 的 USB 存储只读候选方案。Plasma 采用配套 Thunar 的专门入口，不承诺 Dolphin 原生设备栏。当前仅有调研与 OpenSpec 资料，无业务代码修改、部署或组件发布。

本次需要需求确认，依据 workspace AGENTS.md 的“Requirement confirmation after Stage 1/2”和“Do not edit business code before proposal/design/specs/tasks are confirmed.”。此前应用中心等改动的批准不等于本次设备范围批准；此前任务结束推送进度的授权继续适用。

四份规划位于 `openspec/changes/add-proot-usb-storage/`，产品资料目标及证据路径已登记，一致性 Pending。缺失 Harness bootstrap、strict 脚本和 openspec CLI，不能执行完整严格门禁，不能声称实现就绪或硬件验证完成。无实物 USB 验证；镜像/模拟器证据不能替代 OTG。
