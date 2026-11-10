# 设计

固定ptitSeb/box64 v0.4.4提交2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a。使用现有容器内编译环境GCC14.2，Release ARM64 dynarec，strip调试信息，LOAD对齐64KiB。解释器/lib/ld-linux-aarch64.so.1，无Native RUNPATH。使用上游正式版，不把旧0.4.5自定义补丁的描述沿用到新包。

包名rootfs_box64_0.4.4.tar.xz，包含bin/box64和原PRoot0.4.5包的lib/x86_64-linux-gnu支持库；发布到jiaxinchen-max/termux-app-packages同名release。版本名0.4.4，修订号1，不依靠递增修订号自动升级。目录43项总数保持不变，替换旧ID并同步默认路径、脚本白名单、绑定、依赖和维护停止逻辑。

风险为加载器/库架构混用、默认路径残留与版本选择失效。用ELF、QEMU静态系统调用和动态pthread/libc程序、实际启动脚本及Java测试核实；真实Android Wine游戏由用户验收。关键运行脚本按用户明确替换授权修改，回滚为撤销本次差异，不触碰设备或其他未提交变更。用户已授权沿用原资产仓库交付，本次未授权Git提交推送。
