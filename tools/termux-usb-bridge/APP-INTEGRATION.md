# App USB storage integration

This source snapshot was copied from `kwin/tools/termux-usb-bridge` in the local KWin workspace.
The containing repository was at `ef19d13142c95581b711313895b0c550f891b89d`, but the bridge directory
was untracked there; that commit does not contain these sources. `SOURCE-SNAPSHOT.sha256` identifies
this snapshot. Build outputs, dist packages, caches and screenshots are excluded.

The App currently uses the existing, pinned `termux-usb-bridge` 0.4.1 package. It does not replace
that package with a storage-only build. `desktop_usb_storage.py` loads the installed coordinator
module and fixes the App policy to embedded Android USB authorization, read-only storage, no camera,
no gamepad, and no test manifest. Package installation and payload checks are owned by the App's
single desktop installer.

Only GNOME Files and XFCE Thunar are supported. The volume is GIO/GVfs over authenticated local
DAV, not a block device or a kernel mount. Plasma Dolphin's Solid/UDisks2 sidebar is unsupported,
and the App rejects USB enablement for Plasma. The main APK now contains the `:termux-usb`
Android library and deploys `$PREFIX/libexec/termux-usb-app`. Every desktop explicitly selects
that client. It uses a same-UID abstract socket, foreground Android permission requests and
SCM_RIGHTS FD transfer; no independent Termux:API APK or external API fallback is used.
The original standalone bridge documentation still describes its separate Termux:API backend;
that backend is not selected by this App integration.

Each desktop uses `$PREFIX/tmp/tusb-<first 16 SHA256 hex digits of desktop ID>/desktop`.
Its coordinator and file services belong to that desktop's D-Bus session. The session launcher
must suppress the package's `tusb-desktop.desktop` XDG autostart and explicitly start the App helper.
It must stop the helper first, allow up to 120 seconds for GIO unmount and file-service shutdown,
check its exit code, and only then tear down the session D-Bus. Normal SIGTERM cleanup exits 0;
a clean-flush failure exits nonzero. Unclear/failed enumeration retains records and is reported
through the App-only `health` command, rather than presenting a successful empty list.

PRoot requires glibc binaries. The two GIO targets can be built in a separate **x86 Debian
trixie** environment with the ARM64 GCC cross compiler and multiarch ARM64 GLib development
packages; no compiler is required on the phone. The build script checks the Debian codename and
compiler target (`aarch64-linux-gnu`), not the host CPU architecture.

The actual build used an isolated x86 Debian trixie chroot, `libglib2.0-dev:arm64` installed through
APT, GCC `aarch64-linux-gnu-gcc-14` 14.2.0-19 and ARM64 GLib 2.84.4. Its explicit environment and
command were:

```sh
PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig \
PKG_CONFIG_PATH= \
python3 /src/scripts/build-gio-glibc.py \
  --source-root /src --output-root /gio-out \
  --cc /usr/bin/aarch64-linux-gnu-gcc --pkg-config /usr/bin/pkg-config \
  --codename trixie
```

`PKG_CONFIG_SYSROOT_DIR` was unset. The compiler path resolves to GCC 14. The emitted
`gio-build-manifest.json` records source/output hashes, GLib/compiler versions, fixed
`SOURCE_DATE_EPOCH` and complete compiler argv. Its include/library paths select
`/usr/lib/aarch64-linux-gnu`, rather than host x86 GLib libraries. A native aarch64 Debian trixie
build root can use the same script with an explicitly selected `/usr/bin/gcc`.

Install the verified `usr/local` payload into the selected container with the App's
`desktop_install_gio.py`; it checks archive contents, identities and hashes and uses no-follow
directory traversal and atomic file replacement. The desktop loads
`/usr/local/lib/termux-usb-bridge/gio/modules` and passes its exact glibc GIO helper argv to the
host coordinator. Bionic libraries cannot be copied into the container as a substitute.

The bundled Mutter recipe is now `48.1-24`. Its Android backend queues a stage redraw on a valid
`EVENT_SCREEN_SIZE` event even when the negotiated output size is unchanged. This addresses a
recreated App Surface having no frame until the next input/scene update; it keeps the fixed
negotiated AHardwareBuffer dimensions and coordinate mapping. The emulator App UI rotation verification of revision 24 is now complete.

Current App UI verification covers the Android 15 arm64 emulator's Native GNOME Shell and
Files navigation, automatic redraw on rotation, PRoot Debian trixie GNOME Files navigation with
real llvmpipe, and the selected 30 Hz surviving Surface reconstruction. With the independent
`com.termux.api` package disabled, the Native/PRoot GNOME and Native XFCE USB pages still report
the embedded module ready, a connected read-only service and a real empty USB device list.
Native Plasma also starts alongside the shared libandroid-stub 30-3 package.

No physical USB storage device was connected; hardware authorization, volume mounting, reads,
unmounting and hotplug have not been validated. This round's formal App UI checks were limited
to the local emulator. The new runtime assets are not publicly released, and a fresh device's
network release-download installation path has not been validated. See
`docs/desktop-testing/gnome-usb.md` in the accompanying App source snapshot for the final scope.

`termux-ui/scripts/test-desktop-usb-control.py` tests policy and health/error handling with a stub
coordinator. It does not validate a physical USB device, Android authorization, or real GVfs I/O.
Those require App UI operation with connected hardware.
