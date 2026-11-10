# 设计

front/wine/<容器ID>.properties 保存容器配置，front/wine/<容器ID>/ 保存可写 prefix 与 HOME；front/wine/templates/ 保存共享 Proot prefix 压缩包、配套标记和暂存内容。

目录层级保持最少：容器直接放在 wine 下，不再增加 containers 子目录；共享模板直接放在 templates 下，不增加 proot 子目录。只保留用于隔离共享资产和容器私有数据的 templates 边界，不为减少层级混放两类数据。不借本次路径调整删除仍被引用的功能目录或资产。

统一修改 GameStoragePaths 的容器根与模板缓存路径，并同步 setup_rootfs_runtime.sh、start_rootfs_game.sh、backup_restore_rootfs.sh 的硬编码；检查所有调用者和其他脚本。孤儿扫描、容器仓库枚举、资产计量、单游戏卸载必须显式区分 templates，共享模板不重复计入每个游戏的私有数据，不因删除容器而删除。

不改变压缩包内容和预构建恢复逻辑，不新增 wineboot 重建或失败回退。只使用新路径，不读取或搬动旧数据。共享 RootFS 仍位于 usr/var/lib/proot-distro/games-shared-rootfs/rootfs。Native 组件仍由现有组件仓库管理。

验证路径契约、模板目录保护、备份恢复和脚本一致性；本地编译固定名称 arm64 普通 debug APK。用户自行下载安装并验证，不远程安装。风险是路径遗漏或共享缓存误删；以测试覆盖，回滚撤销代码与脚本修改，不自动处理设备旧数据。
