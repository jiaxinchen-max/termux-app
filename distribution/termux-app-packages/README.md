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
