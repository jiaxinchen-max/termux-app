# 设计

上游v0.2.8提交8545d0505f274554561ca30e1839027dd7be783a，GCC14.2 AArch64 glibc Release，ARM64 dynarec，strip，LOAD对齐64KiB。PRoot解释器为/lib/ld-linux-aarch64.so.1，禁止Native Termux RUNPATH。归档包含bin/box64及已有PRoot0.4.5的lib/x86_64-linux-gnu支持库，独立资产rootfs_box64_0.2.8.tar.xz发布原组件仓库。

目录新增rootfs_box64_0.2.8，真实版本0.2.8、修订号1；ProotBox64Versions集中识别新ID，PRoot绑定和prefix维护脚本白名单同步。沿用独立安装、卸载、重装以及保存具体ID的路径，运行程序与支持库均取自所选组件目录。默认0.4.5保持不变。

风险为加载器混用、UI可选但脚本拒绝及库路径仍指向默认版本；通过ELF/QEMU、选择绑定和真实启动脚本验证。回滚仅撤销本次候选和识别分支，保留其他变更，不修改设备数据。源仓库保护规则缺失，绑定和启动脚本按关键运行路径审阅。用户已授权此次编译及沿用原仓库资产交付，Git和设备操作未授权。
