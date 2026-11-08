# Termux App Package Repository

Publishing and migration tooling for `jiaxinchen-max/termux-app-packages`.
Package archives are GitHub Release assets and are not committed to Git.
The App bundles `termux-ui/src/main/assets/termux-app-packages/index-v1.json`.

`migrate-app-release-assets.py` copies historical runtime assets using explicit `--source`, `--target`, and `--work-dir` arguments. It preserves names and contents, verifies uploaded SHA-256 values, and retains source assets.

`verify-catalog.py --catalog catalog-v1.json --output repository/index-v1.json` verifies every exact payload URL, size, and SHA-256 before publishing an unchanged catalog. The matching manually dispatched workflow is deployed in the package repository alongside `tools/verify-catalog.py`.

`migrate-mobox-packages.sh` is the historical Mobox import tool; it is not used to publish the current mixed-backend catalog.

## Hangover debug splitting (local candidates)

`split-hangover-debug.py` creates a new, isolated candidate from an exact
digest-pinned Hangover source tar. It does not install packages, execute their
maintainer scripts, change the App catalog, or publish Release assets.

```sh
python3 distribution/termux-app-packages/split-hangover-debug.py \
  --input /path/to/rootfs_hangover_11.16.tar \
  --sha256 b5493f5903ab3c05f78bf4c03b06ac0005eb26f732437748c1592fefa45e861a \
  --output /path/to/new-candidate-directory \
  --objcopy llvm-objcopy-19 --readobj llvm-readobj-19
```

Requires Python 3.11+, `dpkg-deb`, and compatible LLVM tools (tested initially
with Debian LLVM 19.1.7). The output directory must not exist. Input hashes,
tool versions, per-binary section and content hashes, symbol mappings, candidate
package versions and output archive hashes are in `candidate-manifest.json`.
Use the same input, toolchain, revision and `--epoch` (default 0) for reproducible
archives. Debian package versions receive `+idap.debugsplit1` by default.

The candidate has two separate deliverables:

- `hangover-runtime-candidate.tar`: revised Debian packages plus unchanged
  non-deb source members, with runtime debug sections removed.
- `hangover-symbols.tar.gz`: matching debugging material for developer storage,
  **not** a device runtime dependency. Wine PE files retain their full original
  unstripped images, including DWARF and symbols; LLVM COFF `--only-keep-debug`
  cannot directly extract these Wine DWARF images as a conventional debug file.
  ELF files use separate `.debug` files. `symbols-manifest.json` maps runtime
  paths and hashes to the corresponding symbol file. Retaining the original PE
  image lets a debugger load its symbols without inventing a new PE debug format.

The read-only validator checks runtime section contents, RVAs, alignment, Wine
builtin DOS markers, data directories (including CodeView metadata), ELF load
segments and allocated sections. LLVM additionally decodes and compares PE
imports, exports, base relocations, load configuration and unwind metadata.
Optional COFF symbol labels in unwind dumps are normalized to their unchanged
addresses. LLVM may change executable tail padding **outside VirtualSize** from
zeroes to `0xcc`; only that precise change is allowed and recorded. Other
runtime byte, metadata, alignment or padding differences fail the build.

Unsupported images, invalid archives, tool failures, or failed checks leave no
published output directory. Source packages are never modified. Static checks
do not establish Wine compatibility: the manifest explicitly records
`StaticVerifiedNotRuntimeTested`. Before changing the public catalog, validate
wineboot, 32/64-bit programs, graphics and controllers in an approved isolated
environment, publish under a new verified component identity, and retain old
assets for rollback. Never strip the installed device runtime in place.

Run focused tests with:

```sh
python3 -m unittest discover -s distribution/termux-app-packages -p test_debug_split.py -v
```

## Hangover 11.16 source candidate (preferred)

The approved source route uses `hangover-source.lock.json` and upstream's
Debian 13 cross recipe. The builder can itself be a container: it needs
`CAP_SYS_CHROOT` and `CAP_MKNOD`, not nested Docker or mount namespaces.
The separate amd64 Debian directory isolates dependencies only; it is not a
security sandbox. Existing host Wine checkouts and installed runtimes are not
modified.

Prepare a dedicated workspace with `src/hangover` at the locked commit and its
Wine submodule at the locked gitlink. Before using newly cloned repositories,
read applicable instruction files. Fetch the two locked archives into
`downloads/debian-trixie-amd64.tar.gz` and `downloads/llvm-mingw.tar.xz`, verify
SHA-256, and copy the lock and both build scripts to that workspace. Then run:

```sh
http_proxy=http://PROXY_HOST:PORT https_proxy=http://PROXY_HOST:PORT \
  bash prepare-hangover-buildroot.sh /absolute/dedicated-workspace
```

The preparation script refuses an existing buildroot. Partial builds remain
available for diagnosis and deliberate resumption; it never deletes them.
LLVM host libraries are explicitly located because `$ORIGIN` resolution can
fail without `/proc/self/exe`; no `/proc` bind mount is required.
The cross build records installed dependency versions, compiler versions,
configuration and logs under `buildroot/work/`. The Debian root archive and
LLVM-MinGW are pinned by SHA-256; package repository resolution is recorded in
`identity/packages.tsv` and is not yet a fully pinned reproducible APT snapshot.
A successful build marker says `BuiltNotValidated` and must not be interpreted
as a runtime or publication approval.

`CROSSDEBUG=pdb` uses Wine's existing build support: CodeView goes into object
files and the linker writes separate PDB files. Runtime PE images do not carry
large DWARF sections. This avoids rewriting ARM64X images after linking and
retains usable matching symbols. Upstream Debian `dh_strip` still handles ELF
and its separate dbgsym package. PDB files stay on developer storage and are
not required by the device runtime.

After extracting the resulting Wine deb, run the read-only symbol gate:

```sh
python3 distribution/termux-app-packages/verify-hangover-pdb.py \
  --runtime /candidate/usr/lib/wine --pdb-root /build/work/hangover/wine \
  --pdbutil llvm-pdbutil --output /new/symbol-report.json
```

The gate rejects embedded DWARF, missing Wine builtin markers, missing required
i386/ARM64X architectures, invalid CodeView pointers, and PDB GUID/age mismatches.
ARM64X detection uses LLVM-decoded hybrid metadata and its ARM64EC view; the raw
PE Machine field alone may be ARM64 in both original and rebuilt images.
The gate records decoded imports, base relocations and load config. Its bounded
PE decoder checks native export slots, names, forwarders and ordinal-only tables;
the wrapper compares this capability inventory with the original Wine package.
LLVM also decodes exports for images with named exports. LLVM 22/23 rejects valid
ordinal-only tables with zero name-table RVAs; for those images hybrid-view
export coverage remains incomplete and is recorded as physical-only. This is
not a complete hybrid export/unwind verification claim.
It compares symbol identities, not just filenames. Its status is explicitly
`SymbolsVerifiedNotRuntimeTested`; loader/feature/ABI checks and isolated
wineboot, 32/64-bit, graphics and controller testing remain separate gates.
Unchanged FEX/Box64 debs may be reused from the original digest-pinned component;
the final manifest must identify them as reused, not rebuilt.
The build explicitly disables OpenCL and pcap, which the original package did
not ship. An initial exploratory build enabled those modules due to additional
development packages; keep that pdb1 build as evidence only. The wrapper now
rejects extra runtime paths, permission changes, dependency/control-contract
changes and maintainer-script changes before writing a candidate.

The earlier strip route remains experimental. LLVM 19 and 22 both fail to
decode unwind data in an original 11.16 ARM64X `advapi32.dll`. No successful full
candidate was published by that route; do not bypass its failure checks.

Wrap a completed source build with the unchanged emulator/DXVK members from the
original component:

```sh
python3 distribution/termux-app-packages/package-hangover-source.py \
  --original /inputs/rootfs_hangover_11.16.tar \
  --wine-deb /build/hangover-wine_11.16~trixie+idap.pdb2_arm64.deb \
  --dbgsym-deb /build/hangover-wine-dbgsym_11.16~trixie+idap.pdb2_arm64.deb \
  --pdb-root /build/work/hangover/wine --pdbutil llvm-pdbutil \
  --lock distribution/termux-app-packages/hangover-source.lock.json \
  --output /path/to/new-source-candidate
```

The wrapper checks package identities, installed runtime file/symlink coverage,
per-PE architecture/alignment/marker preservation, and PDB identities. It never
strips PE files. It writes `hangover-runtime-source-candidate.tar`, separate
`hangover-source-symbols.tar.gz`, and `candidate-manifest.json`. The symbols
archive includes only PDBs referenced by installed PE files and the ELF dbgsym
deb. ELF Build-ID mapping, full loader/ABI verification and device tests are
still separate gates. Attach the recorded dependency manifest, actual build
script, configuration and logs when handing off this candidate. Test fixtures
prove deterministic wrapping; a full repeated compiler build has not yet been
established as byte reproducible.


Verify the separately extracted ELF dbgsym package against the extracted Wine
runtime with the read-only Build-ID gate:

```sh
python3 distribution/termux-app-packages/verify-hangover-elf.py \
  --runtime /candidate --debug-root /extracted-dbgsym \
  --machine aarch64 --output /new/elf-debug-report.json
```

This gate requires AArch64 ELF files, matching GNU Build-IDs and allocated
section layouts, DWARF-free runtime files and nonempty separate debug files.
The layout check accounts for objcopy removing SHF_INFO_LINK from relocation
sections converted to NOBITS; all other compared flags and layout remain strict.
It reads notes directly because GNU readelf can diagnose a missing interpreter
on a valid only-keep-debug file. A successful mapping does not test ABI or
runtime loading. Bind the report to both input Debian package SHA-256 values.

## Published pdb2 test component

The bundled catalog now selects component `rootfs_hangover_11.16`, version 2,
from [the independent v2 release](https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/rootfs_hangover_11.16-v2).
The runtime asset keeps the name `rootfs_hangover_11.16.tar`: 100,485,120 bytes,
SHA-256 `ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1`.
The original v1 release remains available. This is the source-built pdb2
version, not the experimental post-build strip candidate. PDB and ELF symbols
remain separate from the runtime and APK. Source/build identity and subsequent
device results are attached as `candidate-manifest.json` and
`runtime-validation.json`; the former retains its historical build-time status.

Actual App preparation, a Chinese Winfile window, 32/64-bit smoke programs
with dynamic DLL loading, and safe exit passed on the test device. Full
ARM64X ABI/unwind/hybrid exports, physical controllers, full games and hardware
matrices remain pending. Fresh network preparation using the integrated APK
still requires manual APK installation and validation. Offline external-layout
backup/restore v2 is not implemented and remains explicitly blocked.

Changing the catalog version and digest causes the existing component preparer
to fetch the exact new source. `RootfsSetupAssetInstaller` hashes that digest
into the recipe; template identity then follows the new recipe automatically.
Do not relabel old receipts or templates. This testing-stage switch adds no
old-data migration. To roll back the source selection, restore the complete v1
descriptor and rebuild the APK; never replace v2 release bytes in place.

### 本地 APK 的预初始化前缀试用

当前 PRoot/aarch64/WoW64 Hangover pdb2 使用 APK 内的
`local-games/runtime-rootfs-v11/prefix-hangover-pdb2/`。`bundle.json` 绑定基础配方、
Wine loader/server、FEX/Box64 翻译 DLL 和子文件摘要；`manifest.json` 列出每个文件的
长度、权限、时间及 SHA256。带 `source` 的 DLL/字体从匹配 RootFS 独立复制，其余在
`delta.tgz`。不使用硬链接，不在准备阶段执行 Wine，不为文件还原启动 X server。

这份资产来自已经完成收尾的独立设备实验前缀，移除了 App 的运行就绪标记。
控制台 i386/x86_64 已验证；中文和图形仍须通过新 APK 的实际显示链路验收。
它是本地试用输入，不代表编译机可复现参考构建或新的组件 Release 已完成。
现有 v2 组件字节和下载 URL 不变。容器设施裁剪不在本次范围。

可从已校验的独立实验产物重新生成 APK 资产（输出目录必须不存在）：

```sh
python3 distribution/termux-app-packages/package-prefix-delta.py \
  --delta /path/to/clean-delta.tgz \
  --manifest /path/to/manifest.json \
  --runtime-guards /path/to/runtime-guards.json \
  --recipe termux-ui/src/main/assets/local-games/runtime-rootfs-v11 \
  --evidence /path/to/results.json \
  --output /path/to/prefix-hangover-pdb2
python3 termux-ui/scripts/test-prebuilt-prefix.py
```

打包命令只整理已有完成凭据，不执行 wineboot，也不能替代独立图形验收。禁止将用户
游戏 C 盘作为通用模板来源。新的正式初始化入口、编译机执行方案及组件版本发布仍
由 `prebuild-wine-prefix-archives` 未完成任务追踪。

准备时在目标的专用暂存目录还原，逐文件流式复制并核对 SHA，再原子发布。
危险路径/链接、损坏资产、运行库不匹配、空间不足和非空目标均失败；不回退到
设备全量初始化。SIGTERM 清理暂存，进程死亡后重试仅回收专用暂存，不删除游戏目录。
全局准备任务所有权及目标文件锁串行化发布。每游戏仍需约824 MiB独立可写空间。
APK 文件名保持 `termux-app_apt-android-7-debug_arm64-v8a.apk`，用户手动安装；
回滚时重新安装上一 APK，并显式准备与其匹配的新测试配置，不覆盖现有 C 盘。

APK 内 gzip 子资产使用 `.tgz` 扩展名，避免 Android Gradle AssetMerger 自动解压 `.gz` 并去掉文件后缀。最终 APK 内容必须逐字节核对，不能仅凭构建成功判定打包正确。

### 已安装 RootFS 的逐批精简实验

`minimize-rootfs.py` 支持逐批实验：`development`（Wine头文件/静态归档）、
`resources`（gettext及非许可文档）、`fonts`（未引用Serif与事务式缓存重建）、
`build-tools`（安装结束后的包管理/i18n生成设施）、`gpu-drivers`（四种非目标Vulkan ICD）。
实验批次必须依次通过真实运行验收；生产 `BUILD_BATCHES` 只纳入已通过的策略，
不能以工具测试代替依赖审计或设备验收。工具不改写已发布Hangover运行包。

输入须为停止写入、已安装 `11.16~trixie+idap.pdb2` 的基础环境，游戏挂载目标为空。
先独立复制，比较输入与候选的文件摘要、模式、链接和时间；空间不够或输入变化会失败。
候选有独立标记，实际共享环境路径不可作为删除目标。复制失败的候选保持 `Copying`
状态，不可继续裁剪；人工确认后可仅清理该失败候选再重试。

```sh
python minimize-rootfs.py clone --source /path/to/installed/rootfs --candidate /path/to/trial/candidate
python minimize-rootfs.py plan --root /path/to/trial/candidate \
  --assets /path/to/prefix-hangover-pdb2 --batch development \
  --output /path/to/trial/development-plan.json
python minimize-rootfs.py apply --root /path/to/trial/candidate \
  --assets /path/to/prefix-hangover-pdb2 --plan /path/to/trial/development-plan.json \
  --journal /path/to/trial/development-journal.json
```

计划绑定全树摘要、运行身份、公共来源保护集与逐路径删除理由；应用时重新计算，
计划过期、文件损坏、保护项相交或残留链接引用被删目标都会拒绝。
链接检查按客体语义追踪目录别名和中间链接，不能用多级链接绕过删除保护；
与本批无关的既有断链和循环不会被改写。
所有报告置于原始树及候选树之外。成功状态仅为 `AppliedPendingRuntimeValidation`，
必须另跑真实 locale、前缀、图形和32/64位程序验证，不代表可发布。

移除的叶子实际保存在候选同级的 `candidate.removed-development/`，用于精确恢复：

```sh
python minimize-rootfs.py rollback --root /path/to/trial/candidate \
  --journal /path/to/trial/development-journal.json
PYTHONDONTWRITEBYTECODE=1 python test_minimize_rootfs.py
```

失败/取消自动恢复；进程被强杀时可依持久日志恢复已移动文件，隔离内容被改动则拒绝。
原始输入一直保留；候选的减小不等于设备释放空间，隔离区和副本仍占空间。
开发资料高度可压缩，不能把实体节省直接当作下载节省；压缩收益需另行实测。

只读直接 ELF 依赖审计（在候选停止运行时执行）：

```sh
python audit-rootfs-elf.py --root /path/to/trial/candidate \
  --output /path/to/trial/development-elf-audit.json
PYTHONDONTWRITEBYTECODE=1 python test_rootfs_elf_audit.py
```

审计检查 ELF 解释器、DT_NEEDED、直接 RPATH/RUNPATH、客体绝对链接与目标架构；
缺库或不支持的搜索路径会失败。它使用固定 Debian aarch64 搜索目录，不执行客体代码，
不将直接依赖通过视为全部 dlopen、继承 RPATH、硬件或游戏功能通过。八项回归使用真实
C 编译器生成的 DSO 验证缺库拒绝、私有 `$ORIGIN` 路径及客体链接不会读取宿主文件。

回滚会校验被移除文件的字节、模式和mtime，仅清理已空的隔离目录；不删除意外出现的文件。再次尝试须使用新的计划/日志文件名，旧日志保留，应用摘要不冒充回滚后全树摘要。

Wine ntdll/win32u及systemd私有库按显式owner预加载上下文解析，要求提供者的SONAME、架构和ELF类别匹配，报告会逐边标注；不是把所有相邻文件当全局搜索路径，未知路径仍失败。systemd实际执行不在游戏验证声明内。

只有第一批运行验证通过，才在同一候选上为 `resources` 生成新计划和独立日志。
第二批保留 copyright/COPYING/LICENSE/NOTICE/AUTHORS 路径、目录别名及任何存活链接的
完整目标链，报告 `retainedByPolicy` 逐项说明原因。不删除 libc locale、GBK/gconv、
Wine NLS、X11 locale 或字体；Wine PE 内嵌中文资源不变。第二批仍须独立运行验收。


安装接入使用 `build --root <termux-files>/usr/var/lib/proot-distro/containers/tmpl-build/rootfs
--termux-files <termux-files> --assets <prefix-assets> --recipe <sha256> --report <outside-path>`，
只接受固定未发布scratch，合并已验证批次后执行ELF、locale、fontconfig和来源摘要检查。
报告含逐路径原因及净逻辑/分配收益；成功才写 `etc/games-rootfs-trim.json`，绑定工具字节、
批次、包身份和配方。`verify-build --root <root> --recipe <sha256>` 是只读凭据核验，
不等同再次全量运行验收。失败/取消不发布，原共享树和旧凭证保留；下次显式重建会丢弃
中断或已精简但策略/配方过期的scratch，从完整基础镜像重建。

字体批保留前缀引用的NotoSansCJK。旧缓存同字体隔离；新缓存在候选外PRoot绑定目录
生成、记录摘要后移入，回滚仅移除匹配本轮记录的新缓存，再还原旧文件；意外内容保留
并阻断成功。最终净收益扣除新缓存，不能把隔离缓存的全部大小当节省。

build-tools不会保留客体内通用包管理能力；dpkg status/updates仅用于来源/挂起事务
校验。变更环境采用新配方重建，不能执行apt原地修复。Desktop/Native使用不同根且不受影响。
保留Perl及扫描/相机/媒体动态链、libudev与hwdb/设备规则；这些存在嵌入或动态消费，
没有穷尽证明就不能删。GPU实验保留freedreno/Turnip、lvp、gfxstream、virtio、Vulkan
loader和LLVM/Gallium；软件探针不能作为硬件图形或物理输入/音频验证。

## 预装精简游戏 RootFS（v12）

`prebuilt_rootfs.py` 是完整预装镜像的生产/验证及设备还原工具。输入为已通过五批裁剪
和真实运行验收的无人写入共享根；不能输入游戏前缀或实验候选树。正式版本使用独立
镜像组件 `rootfs_debian13_hangover_pdb2_minimal`，Hangover运行选择值保持不变。

```sh
python3 prebuilt_rootfs.py pack --root /verified/rootfs \
  --assets /matching/prefix-hangover-pdb2 \
  --output rootfs-debian13-hangover-pdb2-minimal-v1.tar.zst \
  --descriptor image.json --zstd /host/bin/zstd
```

导出固定机器ID/hostname/hosts/resolv.conf，排除安装日志、`/run`、`/tmp`、`/var/tmp`、
随机种子和SSH主机密钥，拒绝用户home、非空游戏挂载目录和实验marker。运行文件清单、
版权资料和`.l2s`实体保留；setuid/setgid权限在Rootless镜像中移除。输入不原地改写。
输出含固定SHA、展开字节/成员预算、清单摘要及源Wine/裁剪记录。归档成员带固定时间戳。

App用组件框架验证压缩载荷，作为raw文件缓存；专用还原器只展开到私有暂存，禁止经
符号链接写文件，链接最后建立。逐文件流式核验后记录独立安装身份，关键运行入口在
复用时复核，完整复核可用`verify --full`。符号链接按客体语义解析，不放宽通用组件
解压器的规则。取消会终止解码进程并清理本任务目录；来源不明的旧scratch不自动删除。

设备只准备Termux宿主PRoot、Python、zstd、dbus/PulseAudio等依赖；不运行客体apt、
locale-gen、deb安装或再次裁剪，不回退到旧构建。基础环境就绪后仍需独立游戏前缀，
沿用APK小模板直接还原；可选Wine/Box64/DXVK按选择独立下载。完整游戏/硬件兼容需另验。

定向验证：`python3 termux-ui/scripts/test-prebuilt-rootfs.py`及
`python3 termux-ui/scripts/test-rootfs-v12-staging.py`。新Release资产必须使用不可变新版本，
核对实际URL/大小/SHA后才作为正式交付；本版本已于2026-11-08经用户授权发布并完成公开下载全文件校验。

### 预装精简镜像（v12，已发布）

`prebuilt_rootfs.py` 从已经完成五批裁剪和运行验收的发布根导出镜像，
不接受实验候选根。客户端将 `.tar.zst` 作为已固定 SHA-256 的 raw 组件保存，
只在任务私有暂存目录流式还原一次，再原子发布为共享 RootFS；不在设备执行
客体 apt、Hangover deb 安装、locale-gen 或再次裁剪。宿主仍需 Python、PRoot、
zstd、PulseAudio、dbus 和 libexpat。每个游戏继续从 APK 差量模板准备独立前缀。

本轮正式镜像：`rootfs-debian13-hangover-pdb2-minimal-v1.tar.zst`，
322915320 字节，展开普通文件 1526870401 字节，12698 个成员，SHA-256：
`22734affd772ccfc99b99d850c6bdefa22683827c4a7b9204ca1a2595c03fbce`。
正式目录项见 `prebuilt-rootfs-index-entry.json`，镜像描述见 APK 资产
`local-games/runtime-rootfs-v12/image.json`。Wine 来源 SHA 与镜像 SHA 独立，
前缀 manifest/delta/runtimeFiles 保持不变，仅重绑基础配方。

工具用法（绝对路径，zstd 可指定宿主可执行文件）：

```sh
python prebuilt_rootfs.py pack --root /path/to/verified-rootfs \
  --assets /path/to/verified-recipe/prefix-hangover-pdb2 \
  --output /path/to/rootfs-debian13-hangover-pdb2-minimal-v1.tar.zst \
  --descriptor /path/to/image.json --zstd zstd
python prebuilt_rootfs.py restore --archive /path/to/rootfs-debian13-hangover-pdb2-minimal-v1.tar.zst \
  --root /path/to/new-rootfs --descriptor /path/to/image.json \
  --recipe CLIENT_RECIPE_SHA256 --zstd zstd
python prebuilt_rootfs.py verify --root /path/to/new-rootfs \
  --descriptor /path/to/image.json --recipe CLIENT_RECIPE_SHA256 --full
```

`restore --reserve-bytes` 可额外保留本次游戏前缀所需空间；既有根和压缩缓存已占用
的空间自然从可用容量扣除。工具另计成员分配开销和余量。解压取消会等待解码器
结束并清理本次新根；既有目标不覆盖。客户端只回收有准确所有权标记的中断目录，
发布校验或 receipt 失败时恢复旧根和旧凭据，保留游戏 C 盘。

正式Release已发布并启用到生产 `index-v1.json`：
https://github.com/jiaxinchen-max/termux-app-packages/releases/tag/rootfs-debian13-hangover-pdb2-minimal-v1

三个公开下载资产均核对字节数和SHA；同名普通APK已重新编译，testOnly=false、
签名/arm64/完整资产核验通过。用户手动安装后已验证实际App服务冷初始化99.054秒、两份前缀6.549/6.561秒、
缓存重建13.710/14.720秒；两份前缀的PE32/PE64、中文界面/文件名、locale及软件图形/
模拟音频探针通过。逐成员对照仅6份Fontconfig缓存头部时间戳变化，1594保护文件匹配、
1401 ELF依赖检查通过；严格离线full快照比对不代表运行后缓存仍逐字节不变。
真实游戏/硬件兼容与最终用户接受另计。
不覆盖既有 Hangover v2 Release，不实施旧数据迁移；旧准备任务会明确拒绝并要求重建。
