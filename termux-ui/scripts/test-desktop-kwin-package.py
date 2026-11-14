#!/usr/bin/env python3
"""Check the installer's production file gate against the actual release debs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile


def check_system_egl_identity(path):
    # The marker must be callable through dlsym, not merely present as a string.
    symbols = subprocess.check_output(
        ['readelf', '--dyn-syms', '--wide', str(path)], text=True)
    exported = any(re.search(
        r'\bFUNC\s+GLOBAL\s+DEFAULT\s+(?!UND\b)\S+\s+termux_system_egl_dispatch$', line)
        for line in symbols.splitlines())
    assert exported, 'Private System EGL identity is not dynamically exported'
    return 'termux_system_egl_dispatch'


def check(kwin_deb, libei_deb):
    root = Path(__file__).resolve().parents[2]
    installer = (root / 'termux-ui/src/main/assets/local-games/desktop_install.sh').read_text()
    manifest = re.search(r'^DEBS="(.*?)"', installer, re.M | re.S)[1]
    required = re.search(r"^HOST_FILES='(.*?)'", installer, re.M | re.S)[1]
    functions = installer[installer.index('package_version()'):installer.index('proot_valid()')]
    names = dict(line.split() for line in manifest.splitlines() if line.strip())
    packages = {}
    for deb in (kwin_deb, libei_deb):
        assert names[deb.name] == hashlib.sha256(deb.read_bytes()).hexdigest(), deb.name
        package = subprocess.check_output(['dpkg-deb', '-f', str(deb), 'Package'], text=True).strip()
        packages[package] = subprocess.check_output(
            ['dpkg-deb', '-f', str(deb), 'Version'], text=True).strip()
    assert packages['kwin'] == '6.7.5-4', packages
    native_files = [line.split()[1] for line in required.splitlines()
                    if line.startswith('kwin ') or line.startswith('libei ')]
    helpers = [
        'bin/kwin-qt-client-renderer-probe',
        'lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so',
        'lib/qt6/termux-system-egl/libEGL.so.1',
    ]
    assert set(helpers).issubset(native_files), native_files
    assert 'lib/libkwin.so.6.7.5' in native_files
    assert not any('6.6.7' in name for name in native_files)
    with tempfile.TemporaryDirectory(prefix='kwin-package-check-') as directory:
        target = Path(directory)
        for deb in (kwin_deb, libei_deb):
            subprocess.run(['dpkg-deb', '-x', str(deb), str(target)], check=True)
        prefix = target / 'data/data/com.termux/files/usr'
        identity_export = check_system_egl_identity(prefix / 'lib/qt6/termux-system-egl/libEGL.so.1')
        # Execute the real package/version/file gate. Only dpkg's database is mocked;
        # all checked files come from the actual deb payloads above.
        query = '\n'.join(
            '    ' + package + ') version=' + version + ' ;;'
            for package, version in packages.items())
        shell = '''dpkg-query() {
    case "$3" in
QUERY
        *) return 1 ;;
    esac
    if [ "$2" = '-f=${Status}' ]; then printf 'install ok installed';
    else printf '%s' "$version"; fi
}
'''.replace('QUERY', query) + functions

        def gate(package, file_list=required):
            script = "HOST_FILES='" + file_list + "'\n" + shell
            script += '\npinned_package_valid "$1" "$2"\n'
            return subprocess.run(['bash', '-c', script, 'package-check', package,
                                   packages[package]], env={'PREFIX': str(prefix)}).returncode

        for package in packages:
            assert gate(package) == 0, 'Actual release files rejected: ' + package
        assert gate('kwin', required.replace('libkwin.so.6.7.5', 'libkwin.so.6.6.7')) != 0
        for name in helpers:
            path = prefix / name
            saved = path.with_name(path.name + '.package-check')
            path.rename(saved)
            try:
                assert gate('kwin') != 0, 'Missing helper accepted: ' + name
            finally:
                saved.rename(path)
        elfs = []
        expected_digests = {line.split()[1]: line.split()[2]
                            for line in required.splitlines()
                            if line.startswith(('kwin ', 'libei '))}
        for name in native_files:
            path = prefix / name
            assert path.is_file(), name
            data = path.read_bytes()
            assert hashlib.sha256(data).hexdigest() == expected_digests[name], name
            if data.startswith(b'\x7fELF'):
                assert data[4:6] == b'\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183, name
                elfs.append(name)
        for name in ('bin/kwin_wayland', 'bin/kwin-qt-client-renderer-probe', 'bin/start-plasma'):
            assert (prefix / name).stat().st_mode & 0o111, name
        return {'result': 'Passed', 'system_egl_identity_export': identity_export, 'packages': packages, 'required_files': native_files,
                'aarch64_elf_files': elfs, 'negative_checks': [
                    'old libkwin version rejected', *['missing ' + name + ' rejected' for name in helpers]]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kwin-deb', type=Path, required=True)
    parser.add_argument('--libei-deb', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(check(args.kwin_deb, args.libei_deb), indent=2))
