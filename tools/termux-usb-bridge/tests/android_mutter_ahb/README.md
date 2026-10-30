# Native AHardwareBuffer client validation

This tests the private `termux_ahardware_buffer_manager_v1` protocol, not the
standard Linux DMA-BUF protocol. The client is built inside the Mutter package
by the production Termux toolchain; no on-device compiler is required.

Start the existing Android test surface from `../android_mutter_gpu/`, then:

```sh
python launch.py --backend android --gl-mode system --work "$HOME/ahb-test" --minimal-services
```

In a separate native Termux shell:

```sh
WAYLAND_DEBUG=client bash run-client.sh "$HOME/ahb-test" 1
WAYLAND_DEBUG=client bash run-client.sh "$HOME/ahb-test" 5
```

Formats 1 (RGBA8888) and 5 (BGRA8888) use two GPU framebuffer AHBs. A one-pixel
compositor sampling probe negotiates channel order before any surface is
attached. The 120-frame test has a red upper half, green lower half and a moving
blue stripe, then keeps the window visible for 90 seconds. Both the client and
the compositor print the actual GL renderer/import path. Inspect the real
Android screenshot; submission counters alone do not prove presentation.

The producer uses glFinish before commit; the compositor flushes Cogl and waits
for GPU completion before wl_buffer.release. The client never rewrites a busy
buffer. There is no client AHB CPU mapping, texture pixel upload or readback;
the optional v2 startup sampling probe explicitly reads one pixel on the server.
The existing RGBX display ABI requires a final GPU channel-conversion pass.

Negative tests (each should print `AHB_CLIENT_REJECT ... disconnected=1` while
other clients/the compositor remain usable):

```sh
bash run-client.sh "$HOME/ahb-test" 1 1 # invalid flags
bash run-client.sh "$HOME/ahb-test" 1 2 # non-socket fd
bash run-client.sh "$HOME/ahb-test" 1 3 # socket with no queued handle
```

`capabilities.py` runs in the native/Bionic Python environment with the installed
private Mutter driver namespace. It records EGL extensions and allocator handle
fds, and tests DMA_BUF_IOCTL_SYNC without assuming every AHB fd is a DMA-BUF.
Only operate the dedicated Pixel Fold (`adb -s emulator-5556`). Back up current
settings with checked `adb shell` commands and absolute paths. `adb exec-out`
can report success even when the remote command writes an error: never infer
file existence from its exit code alone. Keep the Termux APK unchanged and
restore temporary test settings/remove the instrumentation at the end.

Ordinary GTK/glibc clients do not gain this private AHB transport automatically.
Toolkit/EGL WSI integration is separate; the existing KWin AHB client protocol
has the same wire format, but its Qt/Xwayland adapters were not tested here.
