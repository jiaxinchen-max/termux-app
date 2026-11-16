#!/usr/bin/env python3
"""Build the external, data-only Ubuntu Resolute appearance for GNOME 50.

Run on the Linux builder with Python 3.11+, dpkg-deb and glib-compile-schemas.
Uses pinned Ubuntu data packages; no Ubuntu ELF or component enters the APK.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

PREFIX = '/data/data/com.termux/files/usr'
PROFILE = 'native-ubuntu-gnome-50-r1'
EXTENSIONS = ('ubuntu-dock@ubuntu.com', 'ubuntu-appindicators@ubuntu.com',
              'ding@rastersoft.com', 'tiling-assistant@ubuntu.com')
BASE = 'https://ports.ubuntu.com/ubuntu-ports/'
SOURCES = {
    'shell-theme': ('pool/main/y/yaru-theme/yaru-theme-gnome-shell_26.04.5.1ubuntu_all.deb',
                    '55131b0a1fd8408cbd6bf24fc27bce8e33071cc4894c215b87e5c3f16c06f4b8'),
    'gtk-theme': ('pool/main/y/yaru-theme/yaru-theme-gtk_26.04.5.1ubuntu_all.deb',
                  'e66576554ab66d6c6bcd3a86ce1ab9347c861907f81fd89df927bd1c326a7225'),
    'icons': ('pool/main/y/yaru-theme/yaru-theme-icon_26.04.5.1ubuntu_all.deb',
              'd0269daa895d85a72948995497a0848ab3bd6138df59b52c21a814ccf4c7b0a6'),
    'extensions': ('pool/main/g/gnome-shell-ubuntu-extensions/gnome-shell-ubuntu-extensions_50.26.04.8ubuntu_all.deb',
                   'f3e290d10e8ae0d6af6deb3c16e11f063f556fecf1b37bbab62fe8799c21be0e'),
    'wallpaper': ('pool/main/u/ubuntu-wallpapers/ubuntu-wallpapers_26.04.2_all.deb',
                  'd7f7acea3bb3dcd547a4be8aa5515b63c9e2a66e110d7a52ea98323f9c14b68e'),
}
DING_SOURCE_SHA256 = 'e41eb8152982a10384bd2c5c42bc10d51000f35080ee4c934e38c9ac0ef2f0ef'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def copy_tree(source, target, root):
    for path in source.rglob('*'):
        if not path.resolve().is_relative_to(root):
            raise ValueError('Source link escapes pinned package: ' + str(path))
    shutil.copytree(source, target, symlinks=False, dirs_exist_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--download-base', default=BASE,
                        help='HTTPS Ubuntu mirror; pinned checksums remain mandatory')
    args = parser.parse_args()
    if not args.download_base.startswith('https://'):
        parser.error('--download-base must use HTTPS')
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    sources = {}
    for name, (relative, digest) in SOURCES.items():
        archive = work / (name + '.deb')
        if not archive.is_file():
            partial = archive.with_suffix('.partial')
            try:
                with urllib.request.urlopen(args.download_base.rstrip('/') + '/' + relative,
                                            timeout=90) as response:
                    with partial.open('wb') as out:
                        shutil.copyfileobj(response, out)
                if sha(partial.read_bytes()) != digest:
                    raise ValueError('Downloaded source SHA256 mismatch: ' + name)
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
        if sha(archive.read_bytes()) != digest:
            raise ValueError('Source SHA256 mismatch: ' + name)
        architecture = subprocess.check_output(
            ['dpkg-deb', '-f', str(archive), 'Architecture'], text=True).strip()
        if architecture != 'all':
            raise ValueError('Appearance input must be architecture-independent: ' + name)
        destination = work / (name + '-source')
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir()
        run('dpkg-deb', '-x', archive, destination)
        sources[name] = destination
    bundle = work / 'bundle'
    if bundle.exists():
        shutil.rmtree(bundle)
    for name, directories in {
        'shell-theme': ('share/gnome-shell/theme/Yaru', 'share/gnome-shell/theme/Yaru-dark'),
        'gtk-theme': ('share/themes/Yaru', 'share/themes/Yaru-dark'),
        'icons': ('share/icons/Yaru', 'share/icons/Yaru-dark'),
    }.items():
        for directory in directories:
            copy_tree(sources[name] / 'usr' / directory, bundle / directory, sources[name])
    ext_root = bundle / 'share/gnome-shell/extensions'
    schemas = {
        'ubuntu-dock@ubuntu.com': 'org.gnome.shell.extensions.dash-to-dock',
        'ubuntu-appindicators@ubuntu.com': 'org.gnome.shell.extensions.appindicator',
        'ding@rastersoft.com': 'org.gnome.shell.extensions.ding',
        'tiling-assistant@ubuntu.com': 'org.gnome.shell.extensions.tiling-assistant',
    }
    packaged_schemas = sources['extensions'] / 'usr/share/glib-2.0/schemas'
    for uuid in EXTENSIONS:
        source = sources['extensions'] / 'usr/share/gnome-shell/extensions' / uuid
        metadata = json.loads((source / 'metadata.json').read_text())
        if metadata.get('uuid') != uuid or '50' not in metadata.get('shell-version', []):
            raise ValueError('Extension must really support GNOME 50: ' + uuid)
        copy_tree(source, ext_root / uuid, sources['extensions'])
        # Ubuntu relocates extension schemas to the distro-wide schema tree.
        # Native Shell loads each managed extension's private schema directory.
        private_schemas = ext_root / uuid / 'schemas'
        private_schemas.mkdir(exist_ok=True)
        name = schemas[uuid] + '.gschema.xml'
        shutil.copyfile(packaged_schemas / name, private_schemas / name)
        if uuid == 'ubuntu-dock@ubuntu.com':
            shutil.copyfile(packaged_schemas / '10_ubuntu-dock.gschema.override',
                            private_schemas / '10_ubuntu-dock.gschema.override')
        run('glib-compile-schemas', '--strict', private_schemas)
    # Retain the real 50 extension. Patch only Android executable locations and
    # remove its global /proc scan: the owning session cleans its children.
    ding = ext_root / 'ding@rastersoft.com'
    if sha((ding / 'extension.js').read_bytes()) != DING_SOURCE_SHA256:
        raise ValueError('Unsupported DING source SHA256')
    patches = {}
    for file in ding.rglob('*.js'):
        original = file.read_text()
        modified = original.replace('#!/usr/bin/env gjs', '#!' + PREFIX + '/bin/gjs')
        modified = modified.replace('#!/usr/bin/gjs', '#!' + PREFIX + '/bin/gjs')
        modified = modified.replace("'/bin/kill'", "'" + PREFIX + "/bin/kill'")
        if file.name == 'extension.js':
            start = modified.index('    doKillAllOldDesktopProcesses() {')
            end = modified.index('\n    /**', start)
            modified = modified[:start] + """    doKillAllOldDesktopProcesses() {
        // The owning Native desktop session cleans its DING children.
        // Android denies unrelated /proc reads; never scan or kill them.
    }
""" + modified[end:]
        if original != modified:
            file.write_text(modified)
            patches[str(file.relative_to(bundle))] = dict(
                before=sha(original.encode()), after=sha(modified.encode()),
                reason='Termux executable paths and session-owned process cleanup')
        if file.name in ('ding.js', 'createThumbnail.js'):
            file.chmod(0o755)
    background = bundle / 'share/backgrounds/termux-native-ubuntu'
    background.mkdir(parents=True)
    shutil.copyfile(sources['wallpaper'] / 'usr/share/backgrounds/ubuntu-wallpaper-d.png',
                    background / 'default.png')
    modes = bundle / 'share/gnome-shell/modes'
    modes.mkdir(parents=True)
    (modes / 'termux-ubuntu.json').write_text(json.dumps({
        'parentMode': 'user', 'colorScheme': 'prefer-dark',
        'stylesheetName': 'Yaru/gnome-shell.css',
        'themeResourceName': 'theme/Yaru/gnome-shell-theme.gresource',
        'iconsResourceName': 'theme/Yaru/gnome-shell-icons.gresource',
        'enabledExtensions': EXTENSIONS,
    }, indent=2) + '\n')
    info = bundle / 'share/termux-native-ubuntu'
    info.mkdir(parents=True)
    for name, root in sources.items():
        for copyright in (root / 'usr/share/doc').glob('*/copyright'):
            shutil.copyfile(copyright, info / (name + '-' + copyright.parent.name + '-copyright'))
    files = {}
    for file in sorted(bundle.rglob('*')):
        if file.is_file():
            with file.open('rb') as source:
                if source.read(4) == b'\x7fELF':
                    raise ValueError('ELF is forbidden in appearance bundle: ' + str(file))
            file.chmod(0o755 if file.stat().st_mode & 0o111 else 0o644)
            files[str(file.relative_to(bundle))] = sha(file.read_bytes())
    manifest = dict(profile=PROFILE, shell_major=50, distribution='ubuntu:resolute',
                    sources={k: dict(url=BASE + v[0], sha256=v[1]) for k, v in SOURCES.items()},
                    patches=patches, files=files)
    (info / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('wb') as out, gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode='w', format=tarfile.PAX_FORMAT) as tar:
            seen = {}
            for file in sorted(bundle.rglob('*')):
                if file.is_file():
                    entry = tar.gettarinfo(file, arcname=str(file.relative_to(bundle)))
                    entry.uid = entry.gid = entry.mtime = 0
                    entry.uname = entry.gname = ''
                    content_key = (sha(file.read_bytes()), entry.mode)
                    if content_key in seen:
                        entry.type = tarfile.LNKTYPE
                        entry.linkname = seen[content_key]
                        entry.size = 0
                        tar.addfile(entry)
                    else:
                        seen[content_key] = entry.name
                        with file.open('rb') as stream:
                            tar.addfile(entry, stream)
    print('BUNDLE_SHA256=' + sha(args.output.read_bytes()))


if __name__ == '__main__':
    main()
