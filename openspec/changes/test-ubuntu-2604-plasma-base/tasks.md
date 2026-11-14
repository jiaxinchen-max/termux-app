# Tasks

## 1. 隔离安装

- [x] 1.1 核对设备版本、官方 ARM64 镜像摘要与 Ubuntu Plasma 软件源，以 ubuntu-plasma-research 原始结果为证。
- [x] 1.2 检查空间并创建独立容器；验收为 os-release 显示 Ubuntu resolute、架构为 arm64，Debian 容器仍在。
- [x] 1.3 按现有包选择安装 Plasma 及运行依赖；验收为 apt 成功、dpkg --audit 无输出、版本清单与缓存记录已保存。

## 2. 会话验证

- [x] 2.1 创建隔离测试入口和日志目录，验证 ELF/动态库/EGL、KDE libexec 路径与宿主音频；记录所有实验适配差异。
- [x] 2.2 在无运行桌面时启动同一宿主 KWin 下的 Ubuntu Plasma，采集实际画面、阶段耗时和 D-Bus 请求；验收为给出兼容性结论或带日志的具体失败点。
- [ ] 2.3 测量 Dolphin 首次打开、冷/热启动差异与稳定性，输出有证据边界的 Debian/Ubuntu 比较；不能测出的项目明确标为未验证。

## 3. 交付

- [x] 3.1 提供测试结论、原始日志和可复用入口；验证原 Debian 容器仍保留，原 App 默认配置没有被更改。

## 执行证据与限制

- 安装 1217 个包成功，最终 install/prepare/experiment 退出码均为 0，dpkg 审计无输出；Plasma 6.6.6、Dolphin 25.12.3、Mesa 26.0.8，宿主 System EGL 和客体 LLVMpipe 校验通过。
- 2.1 的音频仅初始探针通过，周期健康检查在正常 App 启动中失败；不能描述为音频稳定性通过。
- 2.2 以具体失败点交付：正常策略音频超时；隔离周期音频检查后 60 秒截止；临时延至 180 秒后 PRoot 断言退出。
- 2.3 暂无可见 Dolphin 窗口证据，冷/热启动性能结论未验证。

- Debian 对照：正常 App 启动服务响应 4664ms，Dolphin Home 窗口和壁纸有截图证据；面板完整性、热启动和跨发行版性能对比仍未验证。
- 3.1 原 Debian 容器存在，配置与原部署监督器哈希均与基线相同；所有桌面 stopped/exit。原始日志和报告位于 `/home/user/workspace/artifacts/ubuntu-plasma-test/`。

## 4. 不存在能力的规避实验

- [x] 4.1 每轮 force-stop 并核验残留，按系统 Launcher 从当前 UI 默认入口启动，记录原配置冷启动基线。
- [x] 4.2 根据 Qt 官方支持的开关，在独立 Ubuntu 容器做 portal 单因素对照，保留原部署监督策略；记录面板/文件窗口可视证据和功能损失。
- [x] 4.3 回滚实验配置，检查原 runtime 和 Debian 配置哈希；交付可规避项、不可配置项及仍待定位的失败点。

## 后续冷启动与版本核查证据

- 三轮独立冷启动已完成：原策略、Qt portal opt-out、恢复 portal 后单独排除 systemtray。每轮均 force-stop、核验真实残留并从 UI 启动。portal 轮 Dolphin 窗口可见；两种排除都未恢复可见面板，不能据服务响应毫秒数宣称提速。
- portal、profile、applets 配置已逐字节恢复，原 Debian 与部署 supervisor 哈希不变，实际进程已清空。报告为 `/home/user/workspace/artifacts/ubuntu-plasma-test/cold-start-workarounds.md`。
- 用户追加版本核查已完成：宿主真实 KWin 6.6.7、Ubuntu 6.6.6、Debian 6.7.4。冷启动证实宿主服务 owner 存在而 guest bus 不存在；关键 Plasma Wayland 协议可枚举。Debian 虚拟桌面协议 v4 高于宿主 v3，仅标记按屏幕功能风险，未断言是启动根因。报告为 `/home/user/workspace/artifacts/ubuntu-plasma-test/kwin-version-study/report.md`。
- 2.3 仍未完成：尚无严格相同观测窗口的 Dolphin 冷热性能统计与发行版比较。当前不具备完整桌面成功结论。

- 追加功能验证：匹配真实 Android UID 的容器诊断客户端可读取宿主 KWin 接口；原 Debian 的真实 Pager 组件协商 v3、读取与普通桌面切换通过，按屏幕 request_enter_output 已复现 v3 至少需要 v4 的致命客户端错误。未据此推断启动慢根因。测试后停止全部会话并逐字节恢复 native kwinrc，原 profile/supervisor/applets 哈希通过。证据为 `/home/user/workspace/artifacts/ubuntu-plasma-test/kwin-version-study/verification.md`。
