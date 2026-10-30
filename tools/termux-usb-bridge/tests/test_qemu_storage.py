# SPDX-License-Identifier: MIT
"""Boot an isolated Linux VM, exercise real usbfs/BOT I/O, then verify persistence."""
import argparse
import gzip
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("kernel",type=Path);parser.add_argument("initramfs",type=Path)
parser.add_argument("guest",type=Path);parser.add_argument("library")
parser.add_argument("--qemu",default="qemu-system-x86_64")
args=parser.parse_args()
root=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location("filesystem",root/"filesystem.py")
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def cpio(entries):
    result=bytearray()
    for index,(name,mode,content) in enumerate([*entries,("TRAILER!!!",0,b"")],1):
        path=name.encode()+b"\0"
        fields=[index,mode,0,0,1,0,len(content),0,0,0,0,len(path),0]
        result.extend(b"070701"+b"".join(f"{v:08x}".encode() for v in fields)+path)
        result.extend(b"\0"*((-len(result))%4));result.extend(content)
        result.extend(b"\0"*((-len(result))%4))
    return bytes(result)


with tempfile.TemporaryDirectory(prefix="tusb-qemu-") as directory:
    image=Path(directory)/"disk.img"
    fs=module.Filesystem(library=args.library)
    fs.check(fs.lib.tusb_fs_create_image(os.fsencode(image),64*1024*1024,0));fs.close()
    init=b'''#!/bin/busybox sh
export PATH=/usr/bin:/bin:/usr/sbin:/sbin
/bin/busybox mount -t proc proc /proc
/bin/busybox mount -t sysfs sysfs /sys
/bin/busybox mount -t devtmpfs devtmpfs /dev
/bin/busybox modprobe xhci-pci
/bin/busybox sleep 1
exec /tusb-storage-test
'''
    overlay=cpio([("init",stat.S_IFREG|0o755,init),
                  ("tusb-storage-test",stat.S_IFREG|0o755,args.guest.read_bytes())])
    initrd=Path(directory)/"initrd"
    initrd.write_bytes(args.initramfs.read_bytes()+gzip.compress(overlay,mtime=0))
    command=[shutil.which(args.qemu) or args.qemu,"-machine","q35","-accel","tcg","-m","512",
             "-display","none","-serial","stdio","-monitor","none","-no-reboot",
             "-kernel",str(args.kernel),"-initrd",str(initrd),
             "-append","console=ttyS0 quiet panic=-1 rdinit=/init tusb_usb_storage_test=1",
             "-device","qemu-xhci,id=xhci",
             "-drive",f"if=none,id=usbdisk,file={image},format=raw",
             "-device","usb-storage,bus=xhci.0,drive=usbdisk"]
    result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90,text=True,errors="replace")
    print(result.stdout)
    if result.returncode or "TUSB_QEMU_STORAGE_PASS" not in result.stdout:
        raise RuntimeError("isolated USB BOT/SCSI guest test failed")
    fs=module.Filesystem(image=str(image),library=args.library)
    try:
        expected=bytes((i*17+3)&255 for i in range(131123))
        actual=fs.read("/qemu-usb/moved.bin",len(expected))
        if actual!=expected:raise RuntimeError("USB writes did not persist to the disk image")
        print("PASS usbfs -> BOT/SCSI -> FAT -> 131123 bytes + rename + persistent host readback")
    finally:fs.close()
