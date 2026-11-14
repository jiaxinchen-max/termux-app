# Proposal

## Why

用户已要求“换一下 ubuntu 底包测试一下”。需要在同一台模拟器上验证 Ubuntu 26.04 的 Plasma 6.6 与宿主 KWin 6.6 的组合，判断当前 Debian forky 下的显示和组件等待是否随底包变化。

## What Changes

- 创建独立 Ubuntu 26.04 ARM64 测试容器，固定已核实的官方镜像摘要；保留现有 Debian 容器。
- 优先保持现有桌面包选择、宿主 KWin、System EGL/LLVMpipe、1280×720@30 与会话脚本一致，记录依赖差异；不同时通过禁用组件制造提速。
- 复用受控 apt/dpkg statx 配置，验证软件包和 EGL 后运行临时测试会话，采集服务响应、可视画面与 Dolphin 启动证据。
- 本轮是设备对照实验，不改变 App 发行版目录、默认底包或 APK；后续产品化另行规划。不运行生态 deb 编译。

## Capabilities

### New Capabilities

无。本次不改变 App 的规范级产品行为，使用 skip_specs 明确跳过产品增量规范。

### Modified Capabilities

无。

## Impact

影响仅为已授权模拟器的独立容器、测试目录、临时桌面会话和采集文件。风险为下载/解压占用存储、Ubuntu 包的 PRoot 配置失败、显示协议不兼容、共享显示桥被测试占用；启动前检查已有会话，避免并行启动两个合成器。回滚为停止测试会话并使用原 Debian 桌面；不删除现有容器。无 HTTP API，api_docs 为 N/A；无发布能力或产品兼容性承诺，product_docs 为 N/A。
