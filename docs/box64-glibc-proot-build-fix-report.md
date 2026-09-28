# Box64 v0.4.4: post-link `patchelf` can break startup on Termux GLIBC

## Summary

A Box64 v0.4.4 binary built in `proot-distro` Ubuntu 24.04 (aarch64) did not start after changing its interpreter to the Termux GLIBC loader with `patchelf`.  It crashed before Box64 initialization with `SIGSEGV/SEGV_ACCERR`, so no `BOX64_LOG`, `BOX64_SHOWBT`, or Box64 banner was emitted.

The failure is not caused by Wine or Box64 runtime configuration.  Expanding `PT_INTERP` after linking produced overlapping `PT_LOAD` mappings: the page containing `.dynamic` became non-writable because it also belongs to an `R E` load segment.  The GLIBC loader then faults while starting the executable.

Building Box64 with the final Termux GLIBC interpreter and runpath embedded by the linker avoids the post-link ELF rewrite.  The resulting v0.4.4 binary runs Wine 9.3 and the tested game successfully.

## Environment

| Item | Value |
| --- | --- |
| Android package | `com.termux` |
| GLIBC runtime | Official Termux GLIBC, 2.44 |
| GLIBC loader | `/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1` |
| Wine | `wine-9.3-vanilla-wow64` |
| Build environment | `proot-distro` Ubuntu 24.04.4 LTS, aarch64 |
| Compiler / CMake | GCC 13.3.0 / CMake 3.28.3 |
| Box64 source | upstream tag `v0.4.4`, commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a` |

The application launcher performs this operation before each launch:

```sh
patchelf --force-rpath \
  --set-rpath "$PREFIX/glibc/lib" \
  --set-interpreter "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" \
  "$PREFIX/glibc/bin/box64"
```

## Reproduction

Build Box64 in the PRoot Ubuntu environment using the documented ARM64 options:

```sh
cmake .. \
  -DARM64=1 \
  -DBAD_SIGNAL=ON \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build . -j1
```

The unmodified output starts normally with Ubuntu's loader.  It initially has:

```text
INTERP         /lib/ld-linux-aarch64.so.1
```

Then patch only its interpreter for Termux GLIBC:

```sh
patchelf --set-interpreter \
  /data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1 \
  ./box64
```

The binary now immediately fails, including for the simplest command:

```text
$ ./box64 -v
Segmentation fault
```

`strace` shows that the fault happens in the GLIBC loader, before any Box64 output:

```text
execve(".../box64", [".../box64", "-v"], ...) = 0
newfstatat(AT_FDCWD, "/data/data/com.termux/files/usr/glibc/etc/ld.so.cache", ..., 0)
  = -1 ENOENT (No such file or directory)
brk(NULL) = 0x4c860000
mmap(NULL, 8192, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0)
  = 0x762ce86000
--- SIGSEGV {si_signo=SIGSEGV, si_code=SEGV_ACCERR, si_addr=0x34802660} ---
+++ killed by SIGSEGV +++
```

`--page-size 4096` did not avoid the failure.

## ELF evidence

After the post-link `patchelf` rewrite, relevant program headers are:

```text
LOAD    0x000000 0x00000000347e0000 ... RW  0x10000
LOAD    0x02028c 0x000000003480028c ... R E 0x10000
DYNAMIC 0x022558 0x0000000034802558 ... RW  0x8
```

The fault address, `0x34802660`, is inside the `.dynamic` region beginning at `0x34802558`.  The `RW` and `R E` `PT_LOAD` ranges overlap on the same mapped pages around that address.  Consequently the page which should contain writable dynamic data is protected as executable/read-only.

This is consistent with the dynamic loader failing while updating dynamic information (for example `DT_DEBUG`).  The exact loader write is an inference; the directly observed facts are the early `SEGV_ACCERR`, the fault address in `.dynamic`, and the conflicting load mappings.

Before `patchelf`, the same PRoot build has a non-overlapping layout:

```text
INTERP   /lib/ld-linux-aarch64.so.1
LOAD     0x0000000034800000 ... R E
LOAD     0x0000000035e9fa40 ... RW
DYNAMIC  0x000000003600e1e0 ... RW
```

Therefore this is an ELF-layout problem introduced by expanding `PT_INTERP` after linking, not a `BAD_SIGNAL`, Wine, or Box64 environment-variable issue.

## Working build

Embed the final Termux interpreter and runpath while linking, rather than changing the interpreter afterwards:

```sh
cmake .. \
  -DARM64=1 \
  -DBAD_SIGNAL=ON \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DCMAKE_EXE_LINKER_FLAGS='-Wl,-Ttext-segment,0 -Wl,--dynamic-linker,/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1 -Wl,-rpath,/data/data/com.termux/files/usr/glibc/lib'
cmake --build . -j1
```

The resulting executable has the intended loader before any `patchelf` invocation:

```text
INTERP   /data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1
RUNPATH  /data/data/com.termux/files/usr/glibc/lib
LOAD     0x0000000034800000 ... R E
LOAD     0x0000000035e9fa30 ... RW
DYNAMIC  0x000000003600e1d0 ... RW
```

The application's existing `patchelf --force-rpath --set-rpath --set-interpreter` command was also run against this already-correct binary.  It remained runnable and retained the non-overlapping load layout.

## Verification

```text
Box64 arm64 v0.4.4 2f130fa with Dynarec
box64 -v: exit 0
```

With the normal application launcher, Wine 9.3 successfully started:

```text
wine explorer /desktop=shell,1280x720 G:\wfm.exe
```

After launch, the following processes were present:

```text
start.exe
wineserver
services.exe
winedevice.exe
wine
wfm.exe
```

There was no `buffer overflow detected`, `Segmentation fault`, `Aborted`, or non-zero Wine exit status.

## Suggested packaging fix

For a Termux GLIBC package, do not rely on post-link expansion of a short Linux `PT_INTERP` path into the much longer Termux path.  Emit the final interpreter and runtime search path during the original link, or use a packaging procedure that preserves non-overlapping `PT_LOAD` mappings.

It may be useful to expose the interpreter and rpath as dedicated CMake/package-build options instead of hard-coding the Termux-specific path in Box64 itself.  This issue may also be relevant to `patchelf`, since the observed invalid mapping layout is introduced by its rewrite.

Related report: <https://github.com/termux-pacman/glibc-packages/issues/386>
