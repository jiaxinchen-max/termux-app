# 四类 PRoot USB 只读存储：当前进度

用户于 2026-11-16 已批准执行，范围为 XFCE、Plasma、Ubuntu GNOME、Hyprland 的 Ubuntu 26.04 PRoot；FAT/exFAT BOT/SCSI 只读存储，不是通用 USB 透传。Plasma 使用应用菜单“USB 文件”的 Thunar，其他桌面沿用 Thunar/Files。Native 仍只支持已有 XFCE 路径。

已完成支持矩阵、设置准备事务/失败回滚、四安装分支的 guest GIO 组件、短路径和 token 映射、Hyprland 共享 guest bus、Plasma USB 文件入口和会话监督。所有依赖/静态准备都在安装或保存阶段，运行期仅启动会话服务，不等待设备/授权、不自动补配。主机 PREFIX 在 rootfs 中仍保持 000。

15 个定向 Java 测试及 USB 控制、会话生命周期、八种 shell 配置、Hyprland、GNOME、Plasma 启动、渲染、安装器回归通过。最终源码 APK 已构建、原 key 签名验证、arm64/非 testOnly/74 个源码资源一致性验证通过；44,545,773 字节，约 42.5 MiB，未加入 bridge/GIO/主题 DEB。最终文件、SHA 和下载地址见 delivery.json。模拟器普通 termux 用户的真实 glibc GIO/GVfs exFAT 镜像测试通过：鉴权、中文目录、二进制读回、写入 403、GIO 卷、卸载、磁盘 SHA256 不变及 000 夹具保留；已清理大镜像和临时凭据。

没有新组件编译或发布，复用组件库的 bridge 0.4.1 和 Resolute glibc GIO 0.4.1；现有 XFCE/Plasma 桌面容器未写入测试内容。未通过 ADB 安装 APK。用户安装后停止桌面，进入“外接存储”启用 USB，等待准备任务成功，再启动桌面并在 App 前台授权；Plasma 从“USB 文件”进入。

产品文档一致性保持 Pending；物理 OTG、授权拒绝/热拔插、设备占用和四桌面完整 GUI 验收待用户实测。仓库缺少 Harness 严格门禁脚本和 OpenSpec CLI，不能声称 strict-run 通过；未归档。自审结果见 code-review.md 和 review-findings.yaml，当前无未解决 MUST_FIX。

实现代码已推送 `fbd0f1b7`；本次随后提交补充最终模拟器证据和分发记录。

## 最新进度：读写增量已交付待实物验收

用户批准后接入四类 PRoot 的 read-write，并保留只读/off、Native 仅 XFCE 只读的边界。读写不自动挂载，先在 Android 卸载可移动卷，再手动连接；系统占用/未知状态拒绝接管。刷盘失败不标记为安全弹出。新增 Android 查询只在设备连接动作执行，不放到桌面启动预检。

28 Java、92 Python 测试及模拟器真实 GIO/GVfs exFAT 镜像写入/重新打开验证通过。新 APK 为 44,549,869 字节，约 42.5 MiB；原签名、非 testOnly、组件继续在资产库。新交付详见 read-write-delivery.json，APK 未替用户安装。

用户验证步骤：停止桌面→外接存储选择读写并完成保存任务→启动桌面→Android 系统卸载可移动存储→App 前台授权并手动连接→文件管理器写入→成功断开/弹出后再拔出。其他挂载 SD/USB 也会被保守拒绝，界面有说明。实体 OTG/系统卸载/四桌面 GUI 尚待验证，产品一致性 Pending，严格 Harness 不可用，未归档。
