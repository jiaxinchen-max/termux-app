# Controller DLLs for the Hangover (arm64) Wine

The virtual gamepad reaches Wine through patched `xinput1_1/1_2/1_3/1_4/9_1_0/xinputuap` and `dinput/dinput8` DLLs that talk
UDP to the app's WinHandler. The x86_64/i386 builds in `wine/` of `wine.tar.gz` come from Wine 9.15 and only suit x86 Wine
builds. The arm64 Hangover Wine (`rootfs_hangover_*`, Wine 11.16) needs DLLs built from its own source, shipped as
`wine-hangover/aarch64-windows` and `wine-hangover/i386-windows` in the same archive.

## Source of the patch

`gamepad-wine-11.16.patch` is the gamepad patch of <https://github.com/jiaxinchen-max/Wine-Builds> (`gamepad.patch`, Wine 9.15)
ported to the Wine of `AndreRH/hangover` tag `hangover-11.16` (wine submodule `3ea3a14`):

* `dlls/dinput/gamepad.c` is new; `dinput.c` calls `gamepad_create_device` for `GUID_Joystick` and `gamepad_enum_device`
  after the HID joysticks; `Makefile.in` of `dinput`/`dinput8` list `gamepad.c` and import `ws2_32`;
* `dlls/xinput1_3/main.c` is the patched 9.15 file (the patch replaces the HID code with the UDP client); `xinput1_1`,
  `1_2`, `1_4`, `xinputuap` import `ws2_32`; `xinput9_1_0` is built from the `xinput1_3` sources (`PARENTSRC`).

Check it with `git apply --check` inside the `wine` submodule of the tag.

## Build

```sh
LLVM_MINGW=/path/to/llvm-mingw sh termux-ui/scripts/build-hangover-controller-dlls.sh
```

It clones the tag, applies the patch, configures with `--enable-archs=arm64ec,aarch64,i386 --with-mingw=clang`, builds the
eight DLLs for `aarch64-windows` and `i386-windows` and strips them into `$OUT` (default `$WORKDIR/out`). The aarch64 DLLs
come out with Hangover's section layout (64 KiB alignment, `.hexpthk`, `.a64xrm`) and the `Wine builtin DLL` marker.

Then repack: keep `wine/` as it is, add `wine-hangover/{aarch64-windows,i386-windows}`,
`tar --sort=name --owner=0 --group=0 --numeric-owner -cf - wine wine-hangover | gzip -9n > termux-x11/src/main/assets/wine.tar.gz`.

## How the launcher uses them

`install_games_controller_hangover` (controller_runtime.sh) copies the DLLs, marker intact, into the guest Wine
(`<rootfs>/usr/lib/wine/<arch>-windows`) and into the prefix (`system32` for aarch64, `syswow64` for i386); replaced files are
kept once as `*.stock`. Both places are needed: Wine 11 only loads a builtin DLL when a file of that name exists in
`system32`/`syswow64`. They must not be switched to `native` (Runtime > Libraries): native mode rejects the builtin marker,
which shows up as "Library xinput1_3.dll ... not found". The launcher therefore ignores that setting under Hangover.
