#!/usr/bin/env python3
"""Build the data-only GNOME 48 appearance bundle. Requires meson, ninja,
sassc, gettext, libxml2-utils, glib tools, make and dpkg-deb on the build host.
No Ubuntu ELF files are included. Output is shipped inside the APK.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

PREFIX = '/data/data/com.termux/files/usr'
EXTENSIONS = ['ubuntu-dock@ubuntu.com', 'appindicatorsupport@rgcjonas.gmail.com', 'ding@rastersoft.com']
SOURCES = {
    'yaru': ('https://codeload.github.com/ubuntu/yaru/tar.gz/0323aa049c8bfa7a117fdc8c206313a6c7d10c11',
             'fabc8106beb4aa8e1972d48fd2260ff548af8bc6ecebeb5f1a710e9367e5d2d3'),
    'dock': ('https://codeload.github.com/micheleg/dash-to-dock/tar.gz/da735cf14680bc38d643c26db5e445241d9dc3d7',
             'b750355ce631a5a3dd669e15cb77a5baf4f785bf504176daada2f7fab19e70ac'),
    'indicator': ('https://codeload.github.com/ubuntu/gnome-shell-extension-appindicator/tar.gz/fbc758a467d816ad5d6e9a81148ee7b6b7ba1287',
                  'dfd0cfbee04eddc5d10d3097c1d2ed0023b23ea81a95202832d9e61ee648b240'),
    'ding': ('https://extensions.gnome.org/download-extension/ding@rastersoft.com.shell-extension.zip?version_tag=72024',
             '429171dda75a6d449650679c77370fb7edd17b9b183d68e54ab6892ab478a3f3'),
    'wallpaper': ('https://ports.ubuntu.com/ubuntu-ports/pool/main/u/ubuntu-wallpapers/ubuntu-wallpapers_25.04.2_all.deb',
                  '081c34d9359ac579a7bca4241320cab2c1e8d921841464a2b0bb1900e718fe56'),
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run(*args, **kwargs):
    subprocess.run([str(x) for x in args], check=True, **kwargs)


def copy_tree(source, target):
    # Materialize upstream relative links; installation then needs no symlink
    # traversal, and every installed byte has its own manifest entry.
    shutil.copytree(source, target, symlinks=False, dirs_exist_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    sources = {}
    for name, (url, digest) in SOURCES.items():
        archive = work / (name + '.source')
        if not archive.is_file():
            archive.write_bytes(urllib.request.urlopen(url, timeout=90).read())
        data = archive.read_bytes()
        if sha(data) != digest:
            raise ValueError('Source SHA256 mismatch: ' + name)
        destination = work / (name + '-source')
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir()
        if name == 'wallpaper':
            run('dpkg-deb', '-x', archive, destination)
        elif name == 'ding':
            with zipfile.ZipFile(io.BytesIO(data)) as zipped:
                for entry in zipped.infolist():
                    if not (destination / entry.filename).resolve().is_relative_to(destination):
                        raise ValueError('Unsafe zip entry')
                zipped.extractall(destination)
        else:
            with tarfile.open(fileobj=io.BytesIO(data)) as tar:
                tar.extractall(destination, filter='data')
            destination = next(destination.iterdir())
        sources[name] = destination
    stage = work / 'stage'
    if stage.exists():
        shutil.rmtree(stage)
    build = work / 'yaru-build'
    if build.exists():
        shutil.rmtree(build)
    run('meson', 'setup', build, sources['yaru'], '--prefix=' + PREFIX,
        '-Daccent-colors=[]', '-Dmetacity=false', '-Dgtksourceview=false',
        '-Dsounds=false', '-Dsessions=false', '-Dgnome-shell-gresource=true',
        '-Dgnome-shell-user-themes-support=enabled')
    run('meson', 'compile', '-C', build)
    import os
    run('meson', 'install', '-C', build, env=dict(os.environ, DESTDIR=str(stage)))
    installed = stage / PREFIX.lstrip('/')
    bundle = work / 'bundle'
    if bundle.exists():
        shutil.rmtree(bundle)
    copy_tree(installed / 'share', bundle / 'share')
    # Resolve relative theme/icon links while the complete staged tree exists.
    # copytree already materializes them, so all bundle entries are regular files.
    ext_root = bundle / 'share/gnome-shell/extensions'
    run('make', '-C', sources['dock'], '_build', 'VERSION=109')
    copy_tree(sources['dock'] / '_build', ext_root / EXTENSIONS[0])
    run('glib-compile-schemas', ext_root / EXTENSIONS[0] / 'schemas')
    indicator = ext_root / EXTENSIONS[1]
    indicator.mkdir(parents=True)
    for file in sources['indicator'].glob('*.js'):
        shutil.copyfile(file, indicator / file.name)
    for name in ('metadata.json', 'LICENSE', 'AUTHORS.md'):
        shutil.copyfile(sources['indicator'] / name, indicator / name)
    for name in ('schemas', 'icons', 'preferences', 'interfaces-xml', 'tools'):
        copy_tree(sources['indicator'] / name, indicator / name)
    run('glib-compile-schemas', indicator / 'schemas')
    copy_tree(sources['ding'], ext_root / EXTENSIONS[2])
    run('glib-compile-schemas', ext_root / EXTENSIONS[2] / 'schemas')
    # EGO omits the license file; DING headers declare GPLv3+, so ship its full text.
    shutil.copyfile(sources['yaru'] / 'COPYING', ext_root / EXTENSIONS[2] / 'COPYING')
    # Native Android has no /usr/bin/gjs or /bin/kill. Preserve upstream code
    # otherwise; GdkX11 is already guarded, and DING supports Wayland natively.
    patches = {}
    for file in (ext_root / EXTENSIONS[2]).rglob('*.js'):
        original = file.read_text()
        modified = original.replace('#!/usr/bin/env gjs', '#!' + PREFIX + '/bin/gjs')
        modified = modified.replace('#!/usr/bin/gjs', '#!' + PREFIX + '/bin/gjs')
        modified = modified.replace("'/bin/kill'", "'" + PREFIX + "/bin/kill'")
        if file.name == 'extension.js':
            start = modified.index('    doKillAllOldDesktopProcesses() {')
            end = modified.index('\n    /**', start)
            modified = modified[:start] + """    doKillAllOldDesktopProcesses() {
        // Native sessions own and clean their DING subprocess groups.
        // Android denies reading other /proc entries; never scan or kill them.
    }
""" + modified[end:]

        if original != modified:
            file.write_text(modified)
            patches[str(file.relative_to(bundle))] = 'Termux executable paths; session-owned cleanup replaces global /proc process scan'
        if file.name in ('ding.js', 'createThumbnail.js'):
            file.chmod(0o755)
    background = bundle / 'share/backgrounds/termux-native-ubuntu'
    background.mkdir(parents=True)
    shutil.copyfile(sources['wallpaper'] / 'usr/share/backgrounds/ubuntu-wallpaper-d.png', background / 'default.png')
    modes = bundle / 'share/gnome-shell/modes'
    modes.mkdir(parents=True)
    (modes / 'termux-ubuntu.json').write_text(json.dumps({
        'parentMode': 'user', 'colorScheme': 'prefer-dark',
        'stylesheetName': 'Yaru/gnome-shell.css',
        'themeResourceName': 'theme/Yaru/gnome-shell-theme.gresource',
        'enabledExtensions': EXTENSIONS,
    }, indent=2) + '\n')
    info = bundle / 'share/termux-native-ubuntu'
    info.mkdir(parents=True)
    for name in ('COPYING', 'COPYING.LGPL-2.1', 'COPYING.LGPL-3.0', 'LICENSE_CCBYSA'):
        shutil.copyfile(sources['yaru'] / name, info / ('Yaru-' + name))
    shutil.copyfile(sources['wallpaper'] / 'usr/share/doc/ubuntu-wallpapers/copyright', info / 'wallpaper-copyright')
    # All data files are readable by the App UID; source archives have mixed modes.
    files = {}
    for file in sorted(bundle.rglob('*')):
        if file.is_file():
            file.chmod(0o755 if file.stat().st_mode & 0o111 else 0o644)
            files[str(file.relative_to(bundle))] = sha(file.read_bytes())
    manifest = dict(profile='native-ubuntu-gnome-48-r2', sources=SOURCES, patches=patches, files=files)
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
