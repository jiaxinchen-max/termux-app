# 设计：安装完成前准备 Discover 应用目录

## 候选机制
当前 desktop_install.sh 在安装 Plasma 前刷新索引，此时最小 Ubuntu 尚不具备完整 AppStream 索引配置；桌面依赖安装结束后没有专门的目录准备步骤。Discover 首次启动可先加载空池，再由 PackageKit 发布目录；Ubuntu 6.6.6 的刷新回调未重新加载同一池。重开后的真实首页与搜索恢复，支持该机制，但仍需隔离空目录测试补全精确复现。

## 拟实施顺序
1. 保持现有批准的 CA、HTTPS、APT 配置及包安装流程；在 Ubuntu kwin_plasma 容器包清单显式包含发行版 appstream。
2. 在桌面包安装成功后、initialize_desktop 与 de_marker 成功写入前，使用独立可验证的 helper 执行 apt-get update --error-on=any。沿用现有可信源和 statx 运行约定，不安装或升级额外业务软件。
3. 使用发行版 appstreamcli refresh --force --source=os 建立应用目录，命令失败立即传播；避免用固定组件数或固定应用名称作为生产成功条件。
4. 仅安装/重新安装执行；普通会话启动保持现有路径。现有设备只需重开已刷新目录的 Discover 作为临时绕行。

## 验证与回滚
隔离测试模拟桌面包安装前后配置差异，检查最终索引与目录建立的顺序；任一阶段失败不得初始化或写成功标记。Debian、GNOME、XFCE 和 native 路径不增加此步骤。
模拟器测试前 force-stop com.termux，确认活跃残留已结束，从默认 UiLauncher 进入目标桌面；采集首次 Discover 的真实首页、KCalc 搜索、PackageKit 刷新与存活状态。不得使用 Discover --test 验收，它强制 dummy-backend 并启用测试路径。
回滚恢复本轮 helper/安装调用，不删除容器或已有 AppStream 缓存。产品文档验收前保持 Pending。

## 环境与剩余问题
openspec CLI、Harness bootstrap/strict-run 及 docs/product 基线缺失，人工记录规划与实际命令证据，不声称严格门禁通过。未取得 Update Issue 弹窗技术详情，不能把成功 RefreshCache 视为所有 Discover 后端成功；普通首页恢复不等于软件安装事务验收通过。
