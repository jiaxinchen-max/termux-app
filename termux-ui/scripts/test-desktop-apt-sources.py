#!/usr/bin/env python3
"""Exercise official-source rewriting on real isolated APT files."""
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/desktop_container_apt_sources.sh'

class AptSources(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'etc/apt/sources.list.d').mkdir(parents=True)
        (self.root / 'etc/os-release').write_text('ID="ubuntu"\n')

    def configure(self):
        subprocess.run(['sh', '-eu', '-c', '. "$1"; configure_ubuntu_apt_sources "$2"',
                        'test', str(HELPER), str(self.root)], check=True, capture_output=True)

    def test_deb822_and_list_preserve_signatures_third_party_and_original_backup(self):
        files = {
            'sources.list': 'deb [arch=arm64 signed-by=/test.gpg] http://ports.ubuntu.com/ubuntu-ports resolute main\n',
            'sources.list.d/ubuntu.sources': 'Types: deb\nURIs: http://archive.ubuntu.com/ubuntu/ http://security.ubuntu.com/ubuntu/\nSuites: resolute resolute-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n',
            'sources.list.d/third.list': 'deb http://example.org/ubuntu/ stable main\n',
            'sources.list.d/lookalike.list': 'deb http://archive.ubuntu.com/ubuntu-evil/ stable main\n',
        }
        for name, body in files.items():
            path = self.root / 'etc/apt' / name
            path.write_text(body)
            path.chmod(0o640)
        self.configure()
        for name, body in files.items():
            path = self.root / 'etc/apt' / name
            official = name in ('sources.list', 'sources.list.d/ubuntu.sources')
            self.assertEqual(body.replace('http:', 'https:') if official else body, path.read_text())
            self.assertEqual(0o640, path.stat().st_mode & 0o777)
            backup = Path(str(path) + '.desktop-http.bak')
            self.assertEqual(official, backup.exists())
            if official:
                self.assertEqual(body, backup.read_text())
        self.configure()
        for name in ('sources.list', 'sources.list.d/ubuntu.sources'):
            self.assertEqual(files[name], Path(str(self.root / 'etc/apt' / name) + '.desktop-http.bak').read_text())
        self.assertFalse(list(self.root.rglob('*.desktop-https.*')))

    def test_non_ubuntu_source_is_untouched(self):
        (self.root / 'etc/os-release').write_text('ID=debian\n')
        path = self.root / 'etc/apt/sources.list'
        body = 'deb http://archive.ubuntu.com/ubuntu/ resolute main\n'
        path.write_text(body)
        self.configure()
        self.assertEqual(body, path.read_text())
        self.assertFalse(Path(str(path) + '.desktop-http.bak').exists())

    def test_https_and_empty_source_directories_need_no_backup(self):
        self.configure()
        path = self.root / 'etc/apt/sources.list'
        path.write_text('deb https://ports.ubuntu.com/ubuntu-ports/ resolute main\n')
        self.configure()
        self.assertFalse(Path(str(path) + '.desktop-http.bak').exists())

if __name__ == '__main__':
    unittest.main()
