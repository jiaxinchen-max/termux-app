#!/usr/bin/env python3
"""Exercise the production shell downloader through a fake HTTP transport."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / 'src/main/assets/local-games/desktop_install.sh').read_text()
FUNCTION = 'fetch_native_ubuntu_profile() {' + SCRIPT.split('fetch_native_ubuntu_profile() {', 1)[1].split('# Creates the container instance', 1)[0]

class DownloadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.payload = b'verified fixture component'
        self.source = self.root / 'source'
        self.source.write_bytes(self.payload)
        self.calls = self.root / 'calls'
        self.cache = self.root / 'cache/desktop-native-ubuntu-gnome-48-r2'
        self.target = self.cache / 'native-ubuntu-gnome-48-r2.bin'
        curl = self.root / 'curl'
        curl.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$TEST_CALLS"
while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then shift; dest=$1; fi
    shift
done
if [ "$TEST_MODE" = failed ]; then printf partial > "$dest"; exit 22; fi
if [ "$TEST_MODE" = corrupt ]; then printf corrupt > "$dest"; exit 0; fi
cp "$TEST_SOURCE" "$dest"
''')
        curl.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root)+':'+os.environ['PATH'],
                        TMPDIR=str(self.root/'cache'), TEST_SOURCE=str(self.source),
                        TEST_CALLS=str(self.calls), TEST_MODE='ok', DESKTOP_LOG_FILE=str(self.root/'log'))
        digest = hashlib.sha256(self.payload).hexdigest()
        self.function = FUNCTION.replace('5a767f2ef030ea401015e9c4afad6cfc582c203c6f4eb30e079ae6935474ff03', digest)

    def run_fetch(self):
        return subprocess.run(['sh','-eu','-c',self.function+'\nfetch_native_ubuntu_profile'],env=self.env,capture_output=True,text=True)

    def test_first_download_installs_verified_cache(self):
        result = self.run_fetch()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(result.stdout.strip(),str(self.target))
        self.assertEqual(self.target.read_bytes(),self.payload)
        self.assertIn('https://github.com/jiaxinchen-max/termux-app-packages/releases/download/native-ubuntu-gnome-48-r2/',self.calls.read_text())
        self.assertEqual(list(self.cache.glob('.download.*')),[])

    def test_valid_cache_reused_offline(self):
        self.cache.mkdir(parents=True)
        self.target.write_bytes(self.payload)
        self.env['TEST_MODE']='failed'
        self.assertEqual(self.run_fetch().returncode,0)
        self.assertFalse(self.calls.exists())

    def test_corrupt_cache_is_repaired(self):
        self.cache.mkdir(parents=True)
        self.target.write_bytes(b'corrupt')
        self.assertEqual(self.run_fetch().returncode,0)
        self.assertEqual(self.target.read_bytes(),self.payload)
        self.assertTrue(self.calls.exists())

    def test_network_or_checksum_failure_never_promotes_partial(self):
        for mode in ('failed','corrupt'):
            with self.subTest(mode=mode):
                self.env['TEST_MODE']=mode
                result=self.run_fetch()
                self.assertNotEqual(result.returncode,0)
                self.assertFalse(self.target.exists())
                self.assertEqual(result.stdout,'')
                self.assertEqual(list(self.cache.glob('.download.*')),[])
                self.assertFalse((self.root/'prefix/share/termux-native-ubuntu/.installed').exists())

    def test_failed_repair_cannot_turn_corrupt_cache_into_success(self):
        self.cache.mkdir(parents=True)
        self.target.write_bytes(b'corrupt')
        self.env['TEST_MODE']='failed'
        self.assertNotEqual(self.run_fetch().returncode,0)
        self.assertEqual(self.target.read_bytes(),b'corrupt')
        self.assertEqual(list(self.cache.glob('.download.*')),[])
        self.env['TEST_MODE']='ok'
        self.assertEqual(self.run_fetch().returncode,0)
        self.assertEqual(self.target.read_bytes(),self.payload)

class VerifiedApplicationDownloadTest(unittest.TestCase):
    def setUp(self):
        DownloadTest.setUp(self)
        self.cache = self.root / 'cache/desktop-debs-native-apps-test'
        self.target = self.cache / 'settings.deb'
        self.function = 'fetch_debs() {' + SCRIPT.split('fetch_debs() {', 1)[1].split('# Fixed data-only component', 1)[0]
        self.manifest = 'settings.deb ' + hashlib.sha256(self.payload).hexdigest()

    def fetch(self):
        import shlex
        shell = 'fail() { echo "$*" >&2; exit 1; }\n' + self.function
        shell += '\nfetch_verified_debs native-apps-test https://example.test/components ' + shlex.quote(self.manifest)
        return subprocess.run(['sh', '-eu', '-c', shell], env=self.env, capture_output=True, text=True)

    def test_verified_binary_and_offline_cache(self):
        result = self.fetch()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assertEqual(str(self.target), result.stdout.strip())
        self.calls.unlink()
        self.env['TEST_MODE'] = 'failed'
        self.assertEqual(0, self.fetch().returncode)
        self.assertFalse(self.calls.exists())

    def test_corrupt_binary_cache_is_replaced(self):
        self.cache.mkdir(parents=True)
        self.target.write_bytes(b'damaged cached deb')
        self.assertEqual(0, self.fetch().returncode)
        self.assertEqual(self.payload, self.target.read_bytes())

    def test_bad_or_interrupted_download_never_returns_install_paths(self):
        for mode in ('corrupt', 'failed'):
            with self.subTest(mode=mode):
                self.env['TEST_MODE'] = mode
                result = self.fetch()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', result.stdout)
        # A partial network response may remain in the shared deb cache, but it
        # must be discarded and verified again before it can reach apt.
        self.env['TEST_MODE'] = 'ok'
        self.assertEqual(0, self.fetch().returncode)
        self.assertEqual(self.payload, self.target.read_bytes())

if __name__=='__main__':unittest.main()
