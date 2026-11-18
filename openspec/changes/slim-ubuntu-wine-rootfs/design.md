# 技术设计

## 证据与决策

前轮发布manifest和原归档哈希已核验；115份重复来自官方硬链接在生产/还原过程展开。现有还原器拒绝硬链接，Rust多调用程序不能用普通软链接共享。采用已核对的官方 coreutils-from-gnu 0.0.0~ubuntu25（all），Pre-Depends 为现有 gnu-coreutils 9.7-3ubuntu2.1。标准命令由官方链接映射，不手工猜测 Rust 到 GNU 名称，不删除现存命令后留下断链。Rust独有扩展不作为游戏功能依赖，须静态扫描确认没有消费者。

## 构建与回滚

更新构建用完整包锁，用 GNU provider 替换 coreutils-from-uutils，去掉 rust-coreutils，固定新deb来源、大小和SHA。现有OCI含 Protected Rust provider，APT替换需要 allow-remove-essential；仅私有构建根可执行。执行前模拟相同安装请求，拒绝两项旧provider/payload以外的任何移除；全包版本锁、dpkg audit及安装后包表比较继续阻止静默依赖漂移。不使用自动清理，不改系统宿主或共享根。

候选仍为现有安全manifest协议；构建包名沿用现有 rootfs-ubuntu2604-hangover-pdb2-minimal-v1.tar.zst，位于私有工作目录；验证通过后按用户授权原地替换，更新描述、索引及prefix身份。新增构建检查确保GNU provider生效、关键标准命令实际指向GNU且无Cargo coreutils载荷。保留原输入/日志，失败丢弃私有候选后重建，原制品与共享根保持可用。

## Vulkan裁剪

扩展现有精确规则，只匹配 asahi/intel/powervr_mesa 库与同名 *_icd.json。继续使用完整快照、受保护文件、ELF闭包与存活链接检查、隔离回收及回滚。freedreno/lvp/gfxstream/virtio、LLVM/Gallium不列入新增裁剪。

## 验证与限制

单元测试覆盖允许/禁止的APT移除、未知依赖、GNU标准命令校验、真实GPU命名、裁剪回滚。核对官方deb及发布镜像中的GNU目标，生成候选时重新生成manifest，比较压缩和展开大小，不能用上轮无效过滤流冒充可安装镜像。运行验收需分别记录命令、三类Wine图形/媒体和退出；本地字节/依赖检查不能替代Android真实会话验收。

Harness四个引导文件、strict脚本、OpenSpec CLI和产品基线缺失，人工规划和直接源码自审仅作替代证据，不宣称完整门禁通过。未发现保护路径注册表；构建脚本权限影响已在本设计明确，授权来自用户对前轮优化建议的批准。

## 第二轮：运行附加内容精确裁剪

新增独立 runtime-extras 批次并纳入构建合并策略。复用离线规则的 Perl .h/.pod 精确谓词；Pebble 只匹配 usr/bin/pebble；Highway 只匹配 ARM64 目录中 libhwy_contrib/libhwy_test 的数字版本 so 文件与链接，核心 libhwy 始终保留。许可材料、前缀保护、外部Wine依赖和存活链接沿用原门禁。

删除Highway库时，将 etc/ld.so.cache 和 var/cache/ldconfig/aux-cache（若存在）同时列入事务隔离区。使用目标架构的 ldconfig -X -i 在外置独立阶段生成新主缓存；不创建/修改库软链接，不复用旧辅助缓存。设备实测Ubuntu的 -i 仍会生成新辅助缓存；新主缓存写入 etc，新辅助缓存（若生成）写入 var/cache/ldconfig，两者均先持久化日志再安装。合并批次同时有字体缓存时，两组生成文件必须累加而非覆盖。失败恢复所有原缓存、库、链接；修改过的生成缓存阻止破坏性回滚。缓存差额与119项有效载荷分别计量。无Highway删除时缓存不变。

设备验证只复制逐SHA匹配的现有文件到私有临时根，检查 Perl 解释器/核心模块/Unicode、Highway与JPEG XL加载及像素解码，使用实际 ldconfig 验证重建缓存；宿主执行生产 clone/plan/apply/rollback 和完整ELF闭包，逐SHA/模式/mtime/链接比对回滚。缓存生成在宿主不可执行ARM64时采用Android隔离执行并返回生成物，保留真实命令日志。上述不等价于完整候选构建或三类Wine GUI/媒体验收。

## 原地更新策略

按最新授权，不新增v2或其它版本名，保留现有Release tag、资产名和URL。完整构建与运行验证通过后，先备份线上资产和描述/索引，再替换原名资产并匿名下载校验，最后切换对应下载元数据。保留原始来源记录；新构建使用独立build-inputs并按内容摘要区分，不能以相同文件名冒充旧来源。资产替换和元数据更新期间可能有短暂下载校验失败，失败时恢复备份并保留原始错误。

打包器读取当前裁剪策略的 BUILD_BATCHES，要求回执批次逐项一致，替代旧的固定五项计数；拒绝不完整、重复、未知或顺序不符的回执，不能仅扩大计数绕过资格检查。安装还原协议不变，历史已发布归档不受新的构建资格检查影响。

## 独立完整重建

当前默认构建容器保留了已裁剪旧根，不能原地复用进行APT重建。新增可选 --build-container-name，默认名称不变，只接受 wine-ubuntu2604-build 及其受限小写字母/数字后缀；沿用祖先软链接拒绝和显式复用检查。新候选使用独立名称从锁定OCI新建，旧根和共享根不动。此参数只选择私有构建目录，不改变发布名称或URL。

## 完整构建的OCI缓存读取

当前PRoot-distro在缓存manifest对象中加入内部_ct媒体类型字段，直接与原始锁比较会误拒绝正确输入。从缓存documents读取锁定digest对应的原始base64文档，先校验原字节SHA256与manifestDigest，再严格核对解析后全部字段；有效manifest缓存对象只允许移除与mediaType相同的_ct标记，其他额外字段、篡改、引用或架构不符均拒绝。不会修改缓存或锁。

## 私有名称在裁剪阶段的贯通

完整构建发现裁剪器仍固定两个名称，在写入marker或删除文件前拒绝新建私有根。裁剪入口改用与构建器相同的受限名称规则，同时继续要求位于精确containers父目录、rootfs叶目录、真实路径无别名并且无运行会话。保留tmpl-build兼容；新增完整裁剪回执集成及共享根/异常后缀/嵌套目录/软链接拒绝用例。失败发生在裁剪前，不重跑APT；保存原失败证据后只更新实际build-inputs中的裁剪器摘要及派生recipe，从未经裁剪的同一私有新根继续。


## 2026-11-18 用户明确调整验收范围

用户最新指示“先忽略gstream”，本轮原地发布排除三类Wine的32/64位PCM及WMV2共12项GStreamer媒体检查；保留原始失败、超时和GTK诊断，不将排除项标记为通过。其余6项图形/Unicode/中文字体/WinMM检查及3项800×600虚拟桌面检查须满足真实功能报告、180秒限时和清理完成。Proton64采用已执行explorer桌面内原始smoke64成功报告补证，原plain CLI失败继续保留；同次执行支持图形和桌面两项能力，不伪称9次独立执行。CLI137沿用既有已批准诊断口径，不改变生产退出/JIT。

继续执行此前批准的四份原名Release资产和索引原地更新，不新增tag/文件名/URL。更新必要绑定元数据、运行Java身份测试并构建普通原名arm64 debug APK，testOnly=false。先核备份及候选SHA，后上传与匿名完整下载复核，最后CAS更新索引。App界面及硬件GPU验收保持Pending；产品文档一致性与归档继续待最终验收。本轮未新增主应用Git推送或APK安装授权。
