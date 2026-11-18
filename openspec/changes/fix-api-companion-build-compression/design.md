# API 配套包可复现构建设计

## 原因与决策

可信目录为整个 ZIP 和内部文件登记大小及 SHA，ZIP 内的 termux-api.deb 又由宿主 dpkg-deb 生成。脚本只设定 SOURCE_DATE_EPOCH=0，无法控制发行版的默认压缩选择。Ubuntu 官方工具产生 control.tar.zst/data.tar.zst，而当前目录对应 control.tar.xz/data.tar.xz。

在现有 dpkg-deb 调用中显式增加 -Zxz 和 -z6，锁定已发布格式及等级。保持 root-owner-group、时间戳、构建顺序、架构名、ZIP 参数和目录比较原样；不改变 Termux 的 x86_64 架构命名。公开 ZIP 和目录继续沿用现有名称、大小和哈希。

## 模块边界与保护路径

修改仅在工具脚本一处命令及注释；不涉及 Activity、Service、运行时任务状态、shell 入口、X11、输入、存储或组件下载。termux-api/build.gradle 保持原样，仍由 generateApiCli 调用脚本并在目录不匹配时失败。

仓库未提供 protected paths 注册表及 policy id。影响为构建机输出一致性，回滚为撤销新增参数；不触及 APK 签名、权限、运行时激活、镜像或线上发布。此前已准备并验证仓库外补丁；用户现已确认应用及推送，目标脚本只落实同一补丁。

## 验证与限制

保留失败 Actions 的真实日志和官方 Ubuntu dpkg 包大小/SHA。先在 Ubuntu 26.04 dpkg 上复现原脚本失败，再对候选在 Debian、Ubuntu 24.04、Ubuntu 26.04 打包器各构建四 ABI；必须与原可信目录整体字节一致。故意篡改候选副本中的目录，应仍然失败且不能自动改写目录。候选阶段 git apply --check 已通过，正式源文件在应用前保持原始哈希。用户确认后应用同一补丁，正式脚本与已通过矩阵验证的候选字节一致；可信目录和 Workflow 继续保持原始哈希。正式仓库使用 Ubuntu 26.04 官方 dpkg-deb 执行 Gradle generateApiCli，四 ABI 目录和 ZIP 摘要全部通过。

本地候选验证不能等同真实 Actions 全流程成功。已知独立故障是 apt-android-5 拉取 libxtrans 时远端返回 HTTP 500；本轮不改子模块地址或提交。Harness 引导文件、strict gate 和 OpenSpec CLI 缺失按 Unavailable 记录，不宣称严格门禁通过。
