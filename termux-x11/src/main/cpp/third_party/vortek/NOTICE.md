# Vortek renderer import

The `vortekrenderer/` and `winlator/` directories are imported from
[`brunodev85/winlator-app`](https://github.com/brunodev85/winlator-app),
revision `a030f552f452158a2db64fdb32b490fa19c0b48d`.

They retain their upstream LGPL-2.1 license.  The complete upstream source
for this exact revision remains available from that repository.

Local changes are limited to the X11 swapchain and resource-memory paths.
The Android hardware-buffer reference returned by the Termux:X11 callback is
retained for the Vulkan image lifetime and released during swapchain
destruction.  The private framework symbol `AHardwareBuffer_getFd` is removed:
host-visible allocations use public Vulkan external-FD/DMA-BUF mechanisms,
while AHardwareBuffer remains used for X11 swapchain presentation.
