#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Isolated probes for experimental guest Zink; never claim success from an ICD alone."""
import argparse
import ctypes as c
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import stat
import subprocess
import tarfile
import uuid

ICD = '/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json'
SOFTWARE = ('llvmpipe', 'lavapipe', 'softpipe', 'swiftshader', 'software rasterizer')


def require_hardware(renderer, require_zink=False):
    value = renderer.lower()
    if not value or any(name in value for name in SOFTWARE) or 'adreno' not in value:
        raise RuntimeError('Expected an Adreno hardware renderer, got: ' + renderer)
    if require_zink and 'zink' not in value:
        raise RuntimeError('Expected Zink over hardware Turnip, got: ' + renderer)


def validate_host(report):
    if not report['kgsl_access']:
        raise RuntimeError('Adreno/KGSL device /dev/kgsl-3d0 is unavailable; select default container mode')
    if not report['dma_buf_import']:
        raise RuntimeError('Host System EGL cannot import Linux dma-buf; select default container mode')
    if report['dma_buf_format_query'] and not report['drm_formats']:
        raise RuntimeError('Host System EGL exposes no importable dma-buf formats; select default container mode')


def host_probe():
    egl = c.CDLL('/system/lib64/libEGL.so')
    p, i, u = c.c_void_p, c.c_int, c.c_uint
    def bind(name, result, args):
        f = getattr(egl, name); f.restype = result; f.argtypes = args
        return f
    display = bind('eglGetDisplay', p, [p])(None)
    if not bind('eglInitialize', u, [p, c.POINTER(i), c.POINTER(i)])(display, None, None):
        raise RuntimeError('Host System EGL initialization failed')
    try:
        extensions = (bind('eglQueryString', c.c_char_p, [p, i])(display, 0x3055) or b'').decode().split()
        query_supported = 'EGL_EXT_image_dma_buf_import_modifiers' in extensions
        formats = []
        if query_supported:
            address = bind('eglGetProcAddress', p, [c.c_char_p])(b'eglQueryDmaBufFormatsEXT')
            if not address:
                raise RuntimeError('Host EGL advertises format query without an entry point')
            query = c.CFUNCTYPE(u, p, i, c.POINTER(i), c.POINTER(i))(address)
            count = i()
            if not query(display, 0, None, c.byref(count)) or not 0 <= count.value <= 4096:
                raise RuntimeError('Host EGL dma-buf format query failed')
            values = (i * count.value)()
            capacity = count.value
            if capacity and (not query(display, capacity, values, c.byref(count)) or count.value > capacity):
                raise RuntimeError('Host EGL dma-buf format enumeration failed')
            formats = [hex(v & 0xffffffff) for v in values[:count.value]]
        return dict(kgsl_access=os.access('/dev/kgsl-3d0', os.R_OK | os.W_OK),
                    dma_buf_import='EGL_EXT_image_dma_buf_import' in extensions,
                    dma_buf_format_query=query_supported, drm_formats=formats)
    finally:
        bind('eglTerminate', u, [p])(display)


def guest_probe(report_path):
    # The selected ICD must identify an actual Adreno GPU before testing a Wayland GL window.
    result = subprocess.run(['vulkaninfo', '--summary'], capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Turnip Vulkan probe failed: ' + result.stderr[-2000:])
    require_hardware(result.stdout)
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk, GLib, Gdk
    Gtk.init([])
    if Gdk.Display.get_default() is None:
        raise RuntimeError('Container Wayland connection failed')
    if 'Wayland' not in type(Gdk.Display.get_default()).__name__:
        raise RuntimeError('GPU test must use the actual Wayland display')
    GLib.set_prgname('hyprland-gpu-check')
    window = Gtk.Window(title='Hyprland GPU check')
    window.set_default_size(320, 180)
    area = Gtk.GLArea()
    area.set_use_es(True)
    area.set_required_version(3, 0)
    window.add(area)
    report = dict(vulkan_summary=result.stdout, status='Pending', frames=0)
    def render(widget, context):
        try:
            widget.make_current()
            if widget.get_error():
                raise RuntimeError(str(widget.get_error()))
            # Use libepoxy so calls target the GL implementation of the current GTK context.
            lib = c.CDLL('libepoxy.so.0')
            get_address = c.c_void_p.in_dll(lib, 'epoxy_glGetString').value
            renderer = c.CFUNCTYPE(c.c_char_p, c.c_uint)(get_address)(0x1F01).decode()
            require_hardware(renderer, require_zink=True)
            clear_color = c.CFUNCTYPE(None, c.c_float, c.c_float, c.c_float, c.c_float)(c.c_void_p.in_dll(lib, 'epoxy_glClearColor').value)
            clear = c.CFUNCTYPE(None, c.c_uint)(c.c_void_p.in_dll(lib, 'epoxy_glClear').value)
            clear_color(.20, .30, .65, 1.0); clear(0x4000)
            c.CFUNCTYPE(None)(c.c_void_p.in_dll(lib, 'epoxy_glFinish').value)()
            error = c.CFUNCTYPE(c.c_uint)(c.c_void_p.in_dll(lib, 'epoxy_glGetError').value)()
            if error:
                raise RuntimeError('GPU frame rendering failed with GL error ' + hex(error))
            report.update(renderer=renderer, status='Rendered', frames=report['frames'] + 1)
        except Exception as error:
            report.update(status='Failed', error=str(error))
        report_path.write_text(json.dumps(report) + '\n')
        return True
    area.connect('render', render)
    window.connect('destroy', lambda *_: Gtk.main_quit())
    window.show_all()
    # Keep the real client visible until the host observes it. The supervisor owns its process.
    def acknowledged():
        if report_path.with_suffix('.ack').exists():
            window.destroy()
            return False
        return True
    GLib.timeout_add(200, acknowledged)
    GLib.timeout_add_seconds(15, lambda: (Gtk.main_quit(), False)[1])
    Gtk.main()
    if report['status'] != 'Rendered':
        raise RuntimeError('Wayland GPU window failed: ' + report.get('error', 'no rendered frame'))


def backup_mesa(archive, rootfs, destination):
    """Snapshot only validated overlay targets, without following container symlinks."""
    m = runpy.run_path(str(Path(__file__).with_name('desktop_install_mesa.py')))
    entries = m['decode_archive'](m['read_archive'](str(archive)))
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Directory descriptors and O_NOFOLLOW also reject symlinks in ancestors/receipts.
    dest = m['open_directory'](str(destination))
    try:
        try:
            identity = json.loads(m['read_regular'](dest, 'files-before.json', 1024 * 1024))
        except FileNotFoundError:
            identity = None
        if identity is not None:
            data = m['read_regular'](dest, 'files-before.tar.gz', 128 * 1024 * 1024)
            if identity['archive_sha256'] != m['ARCHIVE_SHA256'] or identity['backup_sha256'] != hashlib.sha256(data).hexdigest():
                raise RuntimeError('Existing Mesa backup identity mismatch')
            return
        _write_mesa_backup(m, entries, rootfs, dest)
    finally:
        os.close(dest)


def _write_mesa_backup(m, entries, rootfs, dest):
    temporary = '.mesa-backup-' + uuid.uuid4().hex
    total = 0
    root = m['open_directory'](str(rootfs))
    originals = {}
    try:
        m['validate_rootfs'](root)
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=dest)
        with os.fdopen(fd, 'wb') as output, tarfile.open(fileobj=output, mode='w:gz') as tar:
            for name, (kind, _, _) in entries.items():
                if kind == 'directory':
                    continue
                try:
                    parent = m['parent_directory'](root, name)
                except FileNotFoundError:
                    originals[name] = dict(kind='absent'); continue
                try:
                    leaf = name.rsplit('/', 1)[-1]
                    try:
                        info = os.stat(leaf, dir_fd=parent, follow_symlinks=False)
                    except FileNotFoundError:
                        originals[name] = dict(kind='absent'); continue
                    member = tarfile.TarInfo(name); member.mode = stat.S_IMODE(info.st_mode)
                    if stat.S_ISLNK(info.st_mode):
                        member.type = tarfile.SYMTYPE; member.linkname = os.readlink(leaf, dir_fd=parent)
                        tar.addfile(member); originals[name] = dict(kind='symlink', target=member.linkname)
                    elif stat.S_ISREG(info.st_mode):
                        data = m['read_regular'](root, name, m['EXPANDED_LIMIT'])
                        total += len(data)
                        if total > m['EXPANDED_LIMIT']:
                            raise RuntimeError('Mesa backup exceeds total size limit')
                        member.size = len(data); tar.addfile(member, io.BytesIO(data))
                        originals[name] = dict(kind='file', sha256=hashlib.sha256(data).hexdigest(), mode=member.mode)
                    else:
                        raise RuntimeError('Unsafe Mesa backup target: ' + name)
                finally:
                    os.close(parent)
        sync_fd = os.open(temporary, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=dest)
        try:
            os.fsync(sync_fd)
        finally:
            os.close(sync_fd)
        os.replace(temporary, 'files-before.tar.gz', src_dir_fd=dest, dst_dir_fd=dest)
        os.fsync(dest)
        data = m['read_regular'](dest, 'files-before.tar.gz', 128 * 1024 * 1024)
        identity = dict(archive_sha256=m['ARCHIVE_SHA256'], backup_sha256=hashlib.sha256(data).hexdigest(), files=originals)
        m['install_one'](dest, 'files-before.json', 'file', (json.dumps(identity, indent=2) + '\n').encode(), 0o600)
    finally:
        os.close(root)
        try:
            os.unlink(temporary, dir_fd=dest)
        except FileNotFoundError:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    for name in ['host-check', 'guest-check']:
        p = sub.add_parser(name); p.add_argument('--report', type=Path, required=True)
    p = sub.add_parser('backup-mesa')
    p.add_argument('--archive', type=Path, required=True); p.add_argument('--rootfs', type=Path, required=True)
    p.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    if args.action == 'backup-mesa':
        backup_mesa(args.archive, args.rootfs, args.destination)
        return
    try:
        if args.action == 'host-check':
            report = host_probe()
            try:
                validate_host(report)
                report['status'] = 'PassedCapabilities'
            except Exception as error:
                report.update(status='Failed', error=str(error))
                raise
            finally:
                args.report.write_text(json.dumps(report, indent=2) + '\n')
        else:
            guest_probe(args.report)
    except Exception as error:
        if args.action != 'host-check' or not args.report.exists():
            args.report.write_text(json.dumps(dict(status='Failed', error=str(error))) + '\n')
        raise


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('Hyprland experimental Zink: ' + str(error))
