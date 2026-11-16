# 实现与提交前自审（2026-11-16）

按 expert-reviewer、stellios-code-review 检查，由主代理直接审查工作树和测试；未使用独立代理。用户“批准执行”授权范围为四类 Ubuntu 26.04 PRoot 的只读 USB 存储；此前任务完成后推送进度的指令仍适用。

## 审查结论

当前没有未解决的 MUST_FIX。实现具备本地验证证据，可以保存进度和提供 APK 供用户测试；物理 USB、四类桌面的完整 GUI 行为以及产品最终验收仍待完成，不能据此宣布硬件兼容性全部通过。

- Java policy、设置对话框、shell 安装/启动、launcher 的能力矩阵一致，Native 只保留 XFCE；独立 GNOME 继续废弃。
- USB 配置先快照托管启动文件，再部署候选并提交准备任务；只有相应任务成功且桌面安装结果有效才写入保存记录。提交失败/准备失败会恢复快照。快照限定 16 个小文件、每个不超过 1 MiB，拒绝路径越界与 symlink，未复制 rootfs 或 USB 凭据。
- 发现并修复配置任务误用完整安装的清理逻辑：`DESKTOP_USB_CONFIGURE_ONLY` 不删除已有桌面完成标记或 Plasma 渲染记录。四类各开/关共八种真实 shell 入口夹具验证该边界。
- Plasma 渲染准备会先撤销旧 receipt，故其精确 renderer pair 的 `.env` 也纳入失败快照；USB 文件入口同步恢复。
- 静态依赖、GIO cache、guest mount point、会话脚本、Plasma 菜单入口均在安装/保存阶段处理。普通启动只创建动态 IPC 和运行必要服务，无新安装或硬件/版本预检。
- GNOME/XFCE 口令文件由主机路径转 guest `/tmp/tusb-*`；只接受当前运行目录中的单个 hex `.token`，不把口令放入 URI/argv。新 guest mount helper 使用 fd-relative `O_NOFOLLOW` 遍历；保留 000 host PREFIX，不修改该目录。
- guest 文件管理器/helper 连接各自 glibc session bus；Hyprland USB 模式共用受监督 guest bus；Plasma 用现有 `/run/wl/user/bs/session.sock`。宿主 Bionic 模块没有作为 guest GIO 模块绑定。
- 服务 IPC 就绪与设备枚举分开，轮询不自动请求 Android 授权。设备错误按设备报告。必要服务死亡退出；先卸载卷/停止 USB，再停止 guest bus。进程组只包含本次拥有的服务，测试保留无关进程。
- 复用已发布 bridge 0.4.1 与 Resolute glibc GIO 0.4.1，无 DEB 构建、组件发布或大包入 APK；不改签名配置、凭据、CI 和七个已有 dirty C/C++ 子模块。

## 已纠正的验证夹具问题

新事务测试最初使用了不合约的 task ID，已改为 `desktop-install-*`；安装夹具补齐真实入口要求的 render socket 和 SHA 校验 stdin 协议。旧 Hyprland fixture 补上已有软件中心依赖；旧 XFCE catalog 断言更新为已存在的应用中心行为。修正后通过的最终日志记入 verification.json；没有把初次失败当作产品通过证据。

## 风险和回退

USB 盘需要 Android USB Host 支持、前台系统授权及 BOT/SCSI FAT/exFAT 协议匹配。Android 自己挂载的盘不保证能接管；其他 USB 类别与写盘不在范围。用户可停止桌面、关闭 USB 并等待保存完成后回退为 off；软件包保留，避免隐式卸载共享依赖。代码回退可反向撤销本次提交。

仓库缺少 Harness bootstrap/strict 脚本、OpenSpec CLI 及产品基线目录；严格门禁不可执行，未伪造通过。product_docs 一致性保持 Pending，未归档。

## 模拟器补充验证

独立测试容器内普通 termux UID 10216 的真实 exFAT→WebDAV→glibc GIO/GVfs 链路通过；PUT 403、内容读回、GIO 卷、卸载、整体镜像 SHA256 和 000 夹具均已验证，见 device-image-test.log。未接入实物 USB，不能将镜像结果扩展为 BOT/OTG 或四桌面完整 GUI 通过。最终 APK 脚本逐字节匹配已推送 fbd0f1b7。
