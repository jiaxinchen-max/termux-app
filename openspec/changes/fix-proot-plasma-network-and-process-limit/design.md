# 设计

## 已确认方案
本轮承接 artifacts/proot-network-check 中的两个诊断候选，用户已授权修复。软件源转换放在独立 shell helper 中，由既有容器 apt helper 在 update 前调用；只处理 Ubuntu 官方源，逐文件备份、原子替换，重复执行无变化，第三方源与 Debian 源保持不变。源格式覆盖 Deb822 与传统 list。错误立即返回。

系统总线改用运行目录内显式服务目录，只链接真实安装的 PackageKit/PolicyKit 服务文件，不复制或修改发行版服务文件，不伪造接口。缺失硬件服务应立即返回 ServiceUnknown，避免长时间激活等待与无效进程。私有会话总线不变，USB 桌面其他路径不变。

模拟器设置通过 adb shell settings put global settings_enable_monitor_phantom_procs false 调整；先备份，读回设置并通过 dumpsys 检查实际监控状态。若此版本不支持则核对实现后采用 activity_manager max_phantom_processes 设置，不盲目堆叠无效键。仅用于已指定测试设备，App 不执行 ADB 或获得额外权限。普通设备需要开发者设置，不承诺 App 自行解除限制。

## 验证与回滚
先保存系统设置和 ubuntu.sources 原件；每次功能测试 force-stop com.termux，检查残留，再从用户默认 UI 正常启动。核对屏幕、Plasma/Dolphin/Discover/PackageKit、APT 完整刷新与图标 HTTPS；观察超过原先约 6 分钟退出窗口，检查无新增 phantom trimming。源可恢复 .desktop-http.bak，系统设置 null 原值通过 settings delete 恢复，代码可逐文件还原；不删容器。

现有状态解析在启动 PID 消失且 status=starting 时已返回 FAILED，需用既有测试确认。SIGKILL 无法保证状态文件自行刷新，不将旧文本误报为 UI 当前状态。必要时另据失败测试收敛修复，避免写入竞态。

## 环境限制
缺少 openspec CLI、Harness bootstrap 与 strict-run 脚本，使用仓库现有规范格式手工记录；实际验证与不可执行门禁分别记录，不声称严格流程通过。
