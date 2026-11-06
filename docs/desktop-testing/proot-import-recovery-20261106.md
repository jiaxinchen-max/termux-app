# Pixel Fold Proot 导入与损坏保护复测

设备：Pixel_Fold_API_35 / emulator-5556，userdata 20 GiB。APK 本地构建，保留数据升级。
最终 APK SHA-256：`01ab7f93acd36c6ab7139e1a71bf3a60491fb7f7cca2c689e5d05313c2c503c4`。

## 原始故障

- 2026-11-06 01:25 的 `setup-15f038ec-db95-443e-b4b7-c2fcc15fc485` 向 `/opt/box64-proton` 复制文件时报告 `No space left on device`，终态 `rootfs_guest_setup_failed`。
- 初始 /data 剩约 1.7 GiB，存在约 3.6 GiB 的 `tmpl-build`。共享 RootFS 不存在，旧配方记录仍在。
- Android 拒绝组件硬链接（`Permission denied`），旧逻辑随后复制全部组件，增加峰值空间占用。
- 普通保存会因共享镜像不完整或配方不匹配而自动构建、替换共享环境；缺失标记的独立 C 盘也可能被模板覆盖。
- 从零安装另外遇到 Debian snapshot 下载 adduser 时返回 HTTP 503。旧 guest 脚本在失败后执行 `apt-get -f install`，吞掉下载失败后继续复制组件，最终才因 Wine 缺失失败。

## 修改

- 使用显式 `proot-distro --bind` 绑定已安装组件，取消硬链接失败后完整复制组件的路径。
- 普通保存只允许首次安装或复用匹配的完整环境。发现损坏、安装残留、已安装环境丢失或配方不匹配时，保留数据并报 `rootfs_rebuild_required`；重建须从设置显式发起。
- 非空 C 盘缺少运行身份、drive_c 或有效注册表时，保留文件并报 `rootfs_prefix_recovery_required`，禁止模板覆盖。
- 显式恢复 C 盘备份可在发布前补齐丢失/空身份标记；已有正常标记保留。此恢复依据归档 Wine 身份校验，不适用于普通保存。
- 共享环境损坏或有失败构建/安装记录时显示 Incomplete。
- 基础环境构建检查约 9 GiB 峰值预算，扣除可续建 scratch 占用；新建 C 盘检查至少 3 GiB 空闲。这是支持配方的预估，不保证任意组件大小或并发写入下都不会耗尽空间。
- 删除 guest 的 apt/dpkg 失败修复回退，让原始错误直接终止安装。
- 修正显式构建会话使用错误任务仓库及进程退出/任务落盘竞态，避免失败标题停在 BUILDING。恢复和空间不足提示提供中英文。

## 自动检查

- `test-rootfs-staging.py`：8 场景通过，覆盖组件直接绑定、首次安装、复用、显式构建、损坏/缺失/中断/配方变化拦截及空间不足。
- `test-rootfs-prefix-recovery.py`：5 场景通过，4 类损坏均保留文件，新建 C 盘空间不足时在写入前失败。
- `RuntimeEnvironmentStatusTest`：9 项通过。
- `WineCDriveArchiveTest`：15 项通过，包括显式恢复补齐丢失/空标记、保留正常标记。
- `test-rootfs-maintenance-environment.sh`：通过。
- `:app:assembleDebug`、shell 语法和改动空白检查通过。

## 正式 UI 验证

全程由 App 界面执行导入、保存、构建、启动、退出及删除测试配置。ADB 仅用于部署、读日志、构造并撤销本轮损坏夹具、转存和恢复组件。

| 场景 | 结果 |
| --- | --- |
| Proot 页面手动选择现有 Winfile.exe | 创建独立 Proot 配置，未修改同一 EXE 的原 Native 配置 |
| 原共享环境缺失、存在构建残留时保存 | 明确失败并指引设置中重建；原 scratch 未删除 |
| 容量不足时显式构建 | 在构建前明确失败，未清除已有环境 |
| 设置 → 核心组件 → Proot → 显式重建 | SUCCEEDED，三个 Wine 模板完成；配方 SHA 为 `09250aa746c45cef963f7e3187c82f79e240edbc729f216c8293fa7e921176b7` |
| 编辑页 Save and prepare | SUCCEEDED，约 10.8 秒克隆 Hangover 模板并准备独立 C 盘，未重建共享环境 |
| 启动 Winfile | 正式运行页显示可见的中文文件管理器窗口；实际执行 rootfs_proot / Hangover / llvmpipe |
| 从会话抽屉退出并确认 | 运行任务 CANCELLED，显示连接断开 |
| 临时移走本轮 prefix 的运行身份标记后保存 | FAILED / rootfs_prefix_recovery_required；三个注册表与 C 盘哨兵文件 SHA-256 均未改变 |
| 还原测试标记后重新保存 | SUCCEEDED，约 1.5 秒；未重新克隆或重建 |
| 从库菜单删除本轮配置 | 独占测试容器删除；原 Native 配置、default 容器和原 EXE 文件夹保留 |
| 恢复 Native 后重新进入设置 | Native 显示 Ready |

关键任务：

- 基础环境成功：`setup-39b6ee30-19d1-4d8b-92f0-a83065226278`。
- 导入保存成功：`setup-ba270ba4-c4fc-4ef9-9848-8df479e955c2`。
- 实际运行与正式退出：`launch-e665cc58-0c2b-4337-ba28-33dc130a4cd2`。
- 损坏拒绝：`setup-361f9b85-49ce-4d6e-9833-81227ba0b410`。
- 标记还原后复用成功：`setup-e6a550de-430b-4b42-aa72-6a7cac3097df`。

首次运行曾被原有的 1 GiB 启动预检拦截；清理约 47 MiB 镜像下载缓存后通过，未降低或绕过启动门槛。

## 数据恢复与容量

- 未清除 App 数据。原 Native 游戏及 profile 与初始快照逐字节一致，原 default 容器约 655 MiB 保留。
- 用户明确授权后临时移除 Native 运行组件。测试结束已恢复原路径，24621 个普通文件 SHA-256 全部一致，并核对原始符号链接与就绪标记。
- Native 归档：`/tmp/proot-import-retest-20261106/native-components-held.tar.gz`，SHA-256：`11267f45b8d474254f8517e13cba9a2f3a7f1fa31741ac95b7b4cf6ced27c06c`。恢复校验日志为同目录 `native-restore.log`。
- 前轮测试导出的 C 盘 ZIP 和桌面 TAR 已转存本机证据目录并核对 SHA-256，设备副本清理以腾出约 1.1 GiB。两份归档仍保留在本机原文件名下。
- 本轮新生成的失败 RootFS/临时模板已清理，再从 App 显式构建成功；成功的共享基础环境和三个模板保留。
- 最终 /data 可用 `1119116 KiB`（约 1.07 GiB）。容量限制仍存在：后续新建独立 C 盘需要先腾出至少 3 GiB 空闲。

证据目录：`/tmp/proot-import-retest-20261106/`。
关键截图：`fold-recovery-message.png`、`fold-space-preflight-failed.png`、`fold-base-build-success.png`、`fold-save-prepared.png`、`fold-winfile-running.png`、`fold-damaged-prefix-preserved.png`、`fold-native-restored.png`。
