# Games RootFS runtime

生产路径不生成或发布预装 Games 运行时的完整 RootFS 组件。

设备内构建资产位于：

- `games/src/main/assets/local-games/runtime-rootfs/`
- `games/src/main/assets/local-games/provision_rootfs_runtime.sh`

Games 先下载并校验上游源组件，再通过 Termux AppShell 调用 `pkg install proot-distro` 和 `proot-distro install` 下载 Debian 基础容器。随后使用 `proot-distro login` 在容器内执行 `provision-container.sh`，由容器内 `apt/dpkg` 安装 Hangover 与运行依赖。整个流程不使用 Dockerfile、Docker daemon 或 pip 版 PRoot-Distro。

容器名包含 recipe SHA-256。新容器在 Games `active.properties` 切换前均视为 staging；失败不会覆盖旧 active/previous receipt。
