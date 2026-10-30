# 容器桌面归档核心：格式 v1

实现目录：`termux-ui/src/main/java/com/termux/ui/desktop/archive/`。

固定 gzip + POSIX tar，只含一个完整 proot containerDir 和桌面设置，不包含 Termux 支撑组件。归档首条为 `manifest.json`，随后为 `payload/container` 目录及其树，最后为 `integrity.json`。不探测或切换压缩格式。

manifest 使用严格规范 JSON，包含格式版本、App package、aarch64 架构、Debian codename、原桌面 ID/alias/name、DE、显式 GL 模式、分辨率、刷新率和 nullable 触控/方向 override。只支持 XFCE/trixie 和 Plasma/forky；Auto renderer、容器 Plasma/System、未知字段、未知模式、架构或 App package 不匹配均拒绝。

integrity 为固定大小 JSON：`entryCount`、`regularBytes`、`manifestSha256`、`entriesSha256`。后者是按归档顺序对每条规范记录滚动 SHA-256，记录包含 path/type/mode/size/mtime/link/hardlinkSource/contentSha256。因此内容和元数据均进入完整性验证，不在内存或磁盘另存完整逐项 JSON 索引。

payload 采用规范树序：目录先声明，兄弟名称严格递增，关闭的目录不可再次进入。祖先栈同时拒绝重复路径、文件或链接作为父目录、路径逃逸。tar 的 PAX 仅允许 `path`、`linkpath` 和本格式的 `TERMUX.hardlink=1`。首个多硬链接 regular 标记为 hardlink source；后续 hardlink 只能引用已声明的首 regular，mode/mtime 必须相同。该约束对应导出器实际输出，不接受前向硬链接或链式硬链接。

rootfs 绝对 symlink 合法，目标原样保存；所有链接在文件及完整归档校验成功后才创建，解包期间不会写穿它们。目录 mode 最后恢复，普通文件及目录的权限和 mtime 保留，symlink 保留目标且重新创建；UID/GID 不恢复，文件属于当前 Android App UID。

`runtime-v1` 保留 runtime/host bind 的目录节点及 mode，排除其内容：

- container `shm`；rootfs `proc/sys/dev/run/tmp`。
- rootfs `mnt/sdcard`、`sdcard`、`storage/emulated/0`、`storage/self/primary`。
- rootfs `data/app`、`data/dalvik-cache`、`data/misc/apexdata/com.android.art/dalvik-cache`。
- rootfs `data/data/<appPackage>` 和 `data/user/0/<appPackage>` 下的 `cache`、`files/apps`、`files/home`、`files/usr`。
- rootfs `apex/odm/product/system/system_ext/vendor`。

文件 bind 占位 `rootfs/linkerconfig/ld.config.txt`、`rootfs/linkerconfig/com.android.art/ld.config.txt`、`rootfs/plat_property_contexts`、`rootfs/property_contexts` 一律写为零字节 regular，保留 mode/mtime，不打开其源内容；导入非零、链接或其他类型均拒绝。socket/FIFO 在导出时排除，导入含这些 tar 类型直接拒绝。其余未知文件类型不静默跳过。

`readMetadata` 完整扫描验证但不解包，并把整个压缩文件 SHA-256、entryCount 和保守磁盘预算绑定到结果。磁盘预算为 regularBytes + entryCount * 4096。import 必须传入该检查结果；开始核对 manifest，结束核对完整压缩 SHA，防止支撑组件安装期间归档被更换。发布前也校验真实 rootfs/etc/os-release（支持指向 ../usr/lib/os-release）和对应 `.desktop-de-<DE>` regular 安装标记。

控制 JSON 上限独立于保留元数据预算。目录/延迟链接只在实际导入时保留；扫描和导出不保留这些列表。硬链接来源、导出 inode→path 映射及导入目录/链接 Item 共用固定 32 MiB 保留预算，超过即 `archive_retained_metadata_limit`，不依赖 OOM 或换实现。路径深度上限 256。归档条数、压缩字节、展开及分配余量上限由 caller 的 Limits 显式指定。

物理 tar framing 独立于 Commons Compress 验证：header checksum、已声明 body、零 padding、两个完整 EOF zero record、有限尾部零 padding。随后读完 gzip CRC/trailer，再要求压缩源物理 EOF；截断、提前 EOF、拼接 gzip member、尾随数据、稀疏及未知 PAX 均拒绝。

导出必须显式传入 `DesktopArchiveExportMode.DIRECT_EXCLUSIVE`，固定以 `O_CREAT|O_EXCL|O_NOFOLLOW` 创建最终目标，直接流式写入同一 FD；不使用 partial/rename，也无失败回退。已存在目标（含竞争创建或符号链接）直接失败。任何字节写入前通过 `createdOutput` 回调持久化创建 FD 的 device/inode；tar/gzip 完成、最终进度回调成功后 fsync，并且 close 成功才报告完成。失败仅清理 inode 匹配的自有输出，竞争替换文件保留。进程中断时可能留下未完整目标，manager 依据持久化身份清理。

导入仅创建 `containersRoot/.desktop-import-<newAlias>`，完整校验后原子 no-replace 发布到新 alias。原 ID/alias 不允许复用。Android 导入发布使用 `AtomicFilePublisher.moveNoReplace`，不通过预先 exists 检查来替代 no-replace syscall。

staging 创建后、写入任何 payload 前，通过 `createdImportStaging` 将 device/inode 持久化；原子发布后沿用相同身份。桌面记录也先独占创建临时文件并持久化身份，再原子 no-replace 发布。manager 仅清理身份匹配的 staging、发布目录和记录；身份缺失或变化则保留并明确失败。`discardImportedContainer` 另检查发布目录 inode 与随机 receipt。支撑组件检查安装、设置提交和进程停止由 manager 负责。

恢复前先完整检查归档，再按指定 DE/渲染参数核对固定 release 组件、包文件完整性、proot 和 proot-distro 5.10.3–5.x 的 containers/parser 契约。缺失或损坏项自动安装/修复，安装后再次检查；失败立即停止，不创建或修改容器。已满足时不更新包索引、无需联网。预检使用固定 Python 的隔离只读模块解析，不执行 proot-distro CLI。

进度 callback 每约 1 MiB 或 250 ms 触发；callback 可抛 IOException 取消。未运行桌面、调用 shell 或保存每个 chunk 的任务状态。JVM 回归文件为 `DesktopArchiveEngineTest.java`；正式 App UI 验证另行记录。
