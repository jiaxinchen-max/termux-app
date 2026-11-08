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


### 普通 Wine / Proton 预生成前缀（v12）

`runtime-rootfs-v12/prefix-wine10-wow64` 与 `prefix-proton11-wow64` 使用各自
在构建端完成初始化的 x86_64/WoW64 参考骨架，分别绑定普通 Wine 10.0 和 Proton 11.0。
差量包包含注册表、目录及生成配置，系统 DLL 与字体从已下载组件/共享镜像恢复。
Proton 固定使用 Windows 用户 `steamuser`，离线保留其账号、Desktop及Shell注册信息；
设备首次准备不执行完整初始化。
`manifest.json` 的 `sourceRoot=wine` 从该游戏固定版本的外置 Wine 组件读取 DLL/NLS，
`sourceRoot=rootfs` 从共享镜像读取中文字体。不会把 Hangover ARM64EC DLL 放进普通
Wine 前缀。旧 Hangover schemaVersion=1 格式继续使用原校验。

普通 Wine 的 schemaVersion=2 额外固定组件版本/源包 SHA256、Wine loader/server、
32/64位 ntdll、翻译器与PE架构。组件安装凭据、全部来源文件长度/摘要、路径范围、
空间、归档及目标目录均验证通过后，才发布独立可写 C 盘。原始组件下载和挂载布局
不变；这两个组合不再启动维护 X server 或在设备生成模板。自定义 Wine 保留原流程，
不因同名架构而自动加入受支持模板目录。

维护步骤：在独立 x86_64 构建目录用已固定的普通 Wine、root 用户身份、win64前缀和
`LANG=zh_CN.UTF-8`、`LC_ALL=zh_CN.UTF-8`（构建主机须实际具备该locale）和
`WINEDLLOVERRIDES=mscoree,mshtml=d` 完成一次初始化；等待命令及 `wineserver -w`
自然退出，保存日志与退出证据。不使用游戏前缀。证据JSON包含 `exitCode=0`、
`serverExitCode=0`、`user=root`、`locale=zh_CN.UTF-8`、参考组件 `sourceComponentSha256`。打包工具校验
发布压缩包及选定组件文件，拒绝未完成注册表、宿主私有路径和错误PE架构：

```sh
python3 distribution/termux-app-packages/package-standard-wine-prefix.py \
  --prefix /build/clean-reference-prefix \
  --reference /build/wine-10.0-amd64-wow64 \
  --wine /build/selected-portable-wine \
  --archive /build/selected-component.tar.xz \
  --component rootfs_wine_10.0 --version 1 --sha256 <component-sha256> \
  --rootfs /build/shared-rootfs \
  --recipe termux-ui/src/main/assets/local-games/runtime-rootfs-v12 \
  --evidence /build/reference-result.json \
  --output /build/prefix-wine10-wow64
python3 distribution/termux-app-packages/test_standard_wine_prefix.py
python3 termux-ui/scripts/test-prebuilt-prefix.py
```

Proton 使用对应组件在构建端离线完成的独立参考前缀、参考组件和退出证据。
其 winsxs 的 comctl32_v6.dll 在安装后名为 comctl32.dll，须按摘要定位真实源，
不能仅凭同名 DLL 替换。设备上仍只恢复预生成资产。
新增模板必须经过目标环境的32/64位、中文及图形验证后再声明支持；构建主机原生
Wine smoke 不代表 Android/Box64 的实际App验收。最终验收证据由对应Harness运行记录
追踪；不修改已发布组件字节，不自动发布新版本。

### 外置 Wine/Proton 的 PRoot 依赖门禁

`audit-external-wine.py` 补充树内 `audit-rootfs-elf.py`：显式输入所有受支持的
x86_64 Unix 运行时、固定 Box64 组件和经复核的包装映射。映射锁包含 Box64
精确提交、二进制摘要、library_list 与包装源摘要、原生库名称及源码声明的别名。
ARM64 库存在不代表 Box64 能包装它；无包装映射时必须由固定组件提供正确的
x86_64 模拟库。Wine 私有 ntdll/win32u 只在其自己的命名空间解析。

```sh
python3 distribution/termux-app-packages/audit-external-wine.py \
  --root /build/private-rootfs \
  --runtime /build/proton-candidate \
  --runtime /build/supported-wine \
  --baseline-runtime /build/unchanged-proton \
  --box64 /build/fixed-box64 \
  --wrapper-lock /build/box64-wrapper-lock.json \
  --output /build/external-wine-audit.json --seal
```

候选与基线比较作用于第一个 `--runtime`。模块缺失、直接依赖消失或库加载名称
消失都触发复核；现代 glibc 合并的 pthread/dl/rt 单独变化不触发。
库名称比较仅用于阻止未经复核的能力减少，不作为运行能力等价的证明。
缺库、架构不匹配、跨 Native Termux glibc 路径或未经确认的包装映射均失败。
门禁失败不写保留凭据，也不允许继续设备运行验收或交付可用包。

仅在闭包检查与能力对比均通过后，`--seal` 为独立构建树写入
`etc/games-external-wine-dependencies.json`。精简工具会保留其中每个原生文件和
客体链接，重检内容摘要及链接目标；打包工具也会将这些文件纳入保护清单。
分发目录与 APK 内的精简工具副本保持一致，设备仍只消费固定预构建镜像。
这项检查覆盖 ELF 直接依赖，符号版本、实际媒体加载、驱动和 syscall 语义须另验；
编译成功或模块文件存在均不能替代运行验收。

本次候选的联合审计未通过：旧组件仍有 FFmpeg 等依赖缺口，新候选的构建配置
自动关闭了部分原有能力。没有写入成功凭据、生成可用的新镜像或替换正式引用。

外置审计同时检查 Wine Unix 模块内的字面 `dlopen` 库名。未被固定 Box64 包装的库需要
真实 x86_64 提供者；客体标准目录及其插件均递归检查，原生 ARM64 同名文件不能替代。
原生树审计默认继续拒绝 x86_64；只有 `--external-wine-audit <report>` 指向同一候选、
无失败且逐文件摘要仍一致的外置报告时，才接受其中声明的模拟对象。客体 `/lib` 的
usrmerge 别名在封存前规范化为实际路径，保留真实链接链；不支持的路径仍失败。
这些静态结果不能替代 Box64 包装器、媒体加载及游戏运行验收。

## 独立 prefix 资产

当前 APK 只携带三套 prefix 的 bundle.json 校验描述。完整构建输入位于 prefix-assets/；旧 v11 的离线测试输入位于 legacy-prefix-assets/，不进入 APK。所选 Wine 的隐藏 runtime_support 依赖复用现有下载、安全安装和缓存流程，准备时仅复制受校验资源到对应配方目录，再从预生成骨架恢复。不会执行完整 wineboot 或切换后端。

生成独立资源归档：

```sh
python3 distribution/termux-app-packages/package-prefix-assets.py \
  --source distribution/termux-app-packages/prefix-assets/prefix-wine10-wow64 \
  --output /path/to/prefix-wine10-wow64-v1.tar.gz
```

工具检查 bundle 绑定的两项数据摘要，确定性打包并逐字节验证归档；不执行 Wine 或上传。版本、归档大小/SHA-256/URL 固定在 App 组件索引；只允许发布实际对应的产物。资源未发布时，不将 APK 宣称为可完成在线首次准备。

### 2026-11-08 已交付结果

以上联合审计失败段落记录早期候选，不代表最终交付状态。最终采用已验证的精确 Proton + SUD 构建，并在原精简镜像仅补充 ARM64 客体 GstGL/GUdev；未引入 LLVM 或重建其他发行版。当前索引引用的五份资源已发布并完成公开下载字节数及 SHA-256 校验。

用户安装当前 APK 后，通过实际 App 界面完成 Wine、Proton、Hangover 各 PE32/PE64 共六项测试：独立 prefix 自动下载恢复，配置准备 6.683–8.668 秒，无完整 wineboot 或游戏准备时镜像重建；动态 version.dll、中文文件写入和可见 Win32 窗口通过。通过 App 退出后无运行时进程或锁残留。此计时不含首次镜像下载及 Wine 服务启动；具体游戏、3D、音频和硬件兼容性未覆盖，用户最终接受仍待确认。


## Native 预构建 prefix（本地候选，待 App 验收）

Native 复用已验证 PRoot WoW64 的注册表和目录骨架，但逐文件重新绑定所选 Native Wine 的 PE DLL/NLS、字体及既有附加程序；不复制 PRoot 的 Unix 动态库。五个固定组件分别制备独立资源：Wine 8.18-staging、9.0-staging、9.3-vanilla、10.0 和 Proton 11.0。构建输入位于 `native-prefix-assets/<winePackage>/`；APK 仅保留对应 `bundle.json`，准备时按需使用现有下载/缓存/安全安装链路。资源为内部依赖，不激活到主机 GLIBC 目录，也不增加用户配置项。

```sh
python3 distribution/termux-app-packages/package-native-wine-prefix.py \
  --skeleton distribution/termux-app-packages/prefix-assets/prefix-wine10-wow64 \
  --wine-archive /build/glibc_wine_10.0.tar.xz \
  --component glibc_wine_10.0 \
  --fonts-archive /build/glibc_fonts_1.tar.xz \
  --scripts-archive /build/glibc_scripts_29.tar.xz \
  --apps-archive /build/glibc_prefix-apps_3.tar.xz \
  --index termux-ui/src/main/assets/termux-app-packages/index-v1.json \
  --output /build/native-prefix-glibc_wine_10.0
python3 distribution/termux-app-packages/package-prefix-assets.py \
  --source /build/native-prefix-glibc_wine_10.0 \
  --output /build/native_prefix_wine_10.0-v1.tar.gz
```

Proton 使用独立的 `prefix-proton11-wow64` 骨架；未知或自定义 Wine 无已验证资产时明确失败。恢复器要求 APK 固定 bundle 摘要、所选组件凭据与每个来源文件摘要一致，检查空间、路径、非空目标和取消，再原子发布。Native Python 恢复工具由 Termux 工具链提供；GLIBC 自动准备包含此依赖。

Native 可写路径为 `front/containers/<containerId>/native-prefixes/<winePackage>`，同容器同版本保留共享语义，切换 Wine 选择另一个独立 prefix。旧单 prefix 不迁移或删除；不通过 wineboot 升级，不覆盖非空数据。用户名、字体注册及默认注册表离线处理，盘符在恢复时绑定实际主机路径；图形/DirectX 仍按原选择应用。

2026-11-08 本地候选完成 22 项 Native 恢复测试、33 项 PRoot 恢复回归、44 项定向 JVM 测试及 Native 启动脚本集成检查；五套实际归档均完成首次/第二配置恢复及逐文件 SHA-256 校验。这些验证没有运行 Native Wine，不能替代 Android App 的 PE32/PE64、中文、服务与退出验收。五份外部资源每份约 3.7–4.0 MiB，尚未发布；发布后才能验收在线首次准备。正常原名 arm64 APK 为 45647375 字节（43.53 MiB），签名有效且 testOnly=false，不含 prefix payload。严格 Harness 脚本在此 checkout 缺失，状态为 Unavailable。产品最终接受继续待确认。

2026-11-08 发布状态更新：用户明确授权后，五份 Native prefix 资源已发布至 `jiaxinchen-max/termux-app-packages`；逐份匿名公开下载核验大小/SHA-256，与当前 APK 索引一致。APK 已提供原名下载，等待用户手动安装及真实 App 界面运行验收。上文“尚未发布”描述为发布前本地候选状态。

2026-11-08 App 验证更新：Native 基础准备先核验主机 glibc、Python、7z 与必要图形库，缺失时走既有官方包安装链路，并重新核验后继续；7z 由官方 `7zip` 提供。用户手动安装最新正常 APK 后，经 App 保存准备自动补装 7zip 并复用已恢复 prefix，图形解压通过。首次准备含中文 locale 生成约85.075秒；同一 Wine10 的第二份配置直接复用，仅0.154秒。Native Wine10 PE64/PE32 图形测试均显示动态 version.dll、中文文件写入和中文 GUI PASS；通过 App 退出返回游戏库，最终无运行时进程和启动锁残留，无wineboot或prefix重建。日志保留 `libXrandr.so.2` 缺失警告，动态分辨率及相关扩展未验证；其余四个 Native 版本、具体游戏、3D、音频及硬件兼容性未由此覆盖。用户最终接受仍待确认。
