#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Add the opt-in broker backend to an extracted libusb 1.0.30 source tree."""
import argparse
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    target = args.source / "libusb/os/linux_usbfs.c"
    source = target.read_text()
    def copy_helpers():
        destination = target.parent
        shutil.copyfile(root / "src/client.c", destination / "tusb_client.c")
        shutil.copyfile(root / "include/termux_usb_bridge.h", destination / "termux_usb_bridge.h")
        shutil.copyfile(root / "libusb/bridge_backend.h", destination / "bridge_backend.h")
    if '#include "bridge_backend.h"' in source:
        copy_helpers()
        parser.exit(0, "broker backend is already applied; helpers refreshed\n")
    replacements = [
        ('static int get_usbfs_fd(struct libusb_device *dev, int access_mode, int silent)\n{',
         '#include "bridge_backend.h"\n\nstatic int get_usbfs_fd(struct libusb_device *dev, int access_mode, int silent)\n{\n\tif (tusb_enabled()) return tusb_backend_open(dev);'),
        ('\tif (sysfs_available == -1) {', '\tif (tusb_enabled()) sysfs_available = 0;\n\n\tif (sysfs_available == -1) {'),
        ('r = linux_start_event_monitor();', 'r = tusb_enabled() ? tusb_backend_start() : linux_start_event_monitor();'),
        ('linux_stop_event_monitor();', '(tusb_enabled() ? tusb_backend_stop() : linux_stop_event_monitor());'),
        ('#if defined(HAVE_LIBUDEV)\n\tret = linux_udev_scan_devices(ctx);',
         'if (tusb_enabled()) {\n\t\tret = tusb_backend_scan(ctx);\n\t} else\n#if defined(HAVE_LIBUDEV)\n\tret = linux_udev_scan_devices(ctx);'),
        ('\tlinux_hotplug_poll();', '\tif (!tusb_enabled()) linux_hotplug_poll();'),
    ]
    # Validate every anchor before writing: fail closed on upstream changes.
    for before, after in replacements:
        expected = 2 if before == 'linux_stop_event_monitor();' else 1
        if source.count(before) != expected:
            parser.error(f"unexpected libusb source: {before!r}; expected {expected} occurrence(s)")
        source = source.replace(before, after)
    copy_helpers()
    target.write_text(source)
    print(f"patched {target}")


if __name__ == "__main__":
    main()
