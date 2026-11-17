# 实施审查

结论：本轮实现无待处理 MUST_FIX。直接检查本轮相对 source-before 的差异、新增测试与实际调用链。

- 受支持桌面菜单统一，活动会话不置灰；退役 GNOME 无重装入口，旧 Install 未恢复。取消不提交；确认连接实际安装控制台。
- 后端在操作锁内重新读取当前库存，先保护所有活动安装和归档，再逐一 forceStop，全部成功才覆盖规格并提交。复用的 RuntimeProcessKiller 按私有路径和 UID 冻结/杀死进程树并扫描确认退出；真实顽固进程测试通过，其他作用域进程保留。停止失败不写新规格、不提交安装。
- 共享宿主包涉及所有受管理桌面，确认文案明确会先结束这些会话。仅显式重装设置 0/1 标记，普通安装/prepare/USB/主题分支保留。
- Native 原有 apt --reinstall 保留，PRoot XFCE/Plasma/Hyprland 包与 Hyprland 宿主依赖显式重新安装；已验证运行时也走校验归档和原子替换。未删除/重建容器；ID、设置、用户文件保留。
- 40 项 Java、26 项 Python 测试通过。Ubuntu GNOME 另一个存量测试夹具遗漏 desktop_software_center.py，原源码回放复现同样 7 项失败；未改动该夹具或将失败算成通过。
- 原名 arm64 APK、同签名、77 项源码资源一致性验证；设备只检查已安装的上版固定入口，本轮未覆盖安装、未执行真实桌面重装。

限制：Harness bootstrap/verify 缺失，strict gate 为 Unavailable；产品一致性和最终用户验收 Pending。不执行 Git 发布、设备部署或归档。回滚使用 source-before 与交付目录 .previous/before-desktop-reinstall-menu/ 同名 APK，保留容器文件。
