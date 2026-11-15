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

    def bootstrap(self, ca_source):
        return subprocess.run(['sh', '-eu', '-c',
            '. "$1"; bootstrap_ubuntu_ca_certificates "$2" "$3"',
            'test', str(HELPER), str(self.root), str(ca_source)], capture_output=True)

    def test_fresh_ubuntu_seeds_host_trust_and_preserves_existing_bundle(self):
        source = self.root / 'host-ca.pem'
        source.write_bytes(Path('/etc/ssl/certs/ca-certificates.crt').read_bytes())
        target = self.root / 'etc/ssl/certs/ca-certificates.crt'
        result = self.bootstrap(source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(source.read_bytes(), target.read_bytes())
        self.assertEqual(0o644, target.stat().st_mode & 0o777)
        source.write_text('changed host bundle')
        original = target.read_bytes()
        self.assertEqual(0, self.bootstrap(source).returncode)
        self.assertEqual(original, target.read_bytes())
        self.assertFalse(list(self.root.rglob('.desktop-ca.*')))

    def test_missing_or_invalid_host_trust_fails_without_creating_guest_bundle(self):
        source = self.root / 'host-ca.pem'
        for content in (None, '', 'invalid certificate'):
            if content is not None:
                source.write_text(content)
            result = self.bootstrap(source)
            self.assertEqual(66, result.returncode, result.stderr)
            self.assertFalse((self.root / 'etc/ssl/certs/ca-certificates.crt').exists())

    def test_non_ubuntu_does_not_require_or_copy_host_trust(self):
        (self.root / 'etc/os-release').write_text('ID=debian\n')
        self.assertEqual(0, self.bootstrap(self.root / 'absent').returncode)
        self.assertFalse((self.root / 'etc/ssl').exists())

if __name__ == '__main__':
    unittest.main()
