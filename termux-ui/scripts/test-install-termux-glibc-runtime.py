#!/usr/bin/env python3
"""Execute the production installer against an isolated package-manager fixture."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/install_termux_glibc_runtime.sh'

class InstallTests(unittest.TestCase):
    def execute(self, mode):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            prefix = root / 'usr'
            (prefix / 'bin').mkdir(parents=True)
            apt = prefix / 'bin/apt'
            apt.write_text('#!' + sys.executable + '\n' + '''import os,sys,json
from pathlib import Path
p=Path(os.environ['PREFIX']); mode=os.environ['FIXTURE_MODE']
with (p/'calls.jsonl').open('a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')
if mode=='package-failure' and 'glibc-repo' in sys.argv: sys.exit(23)
if 'libx11-glibc' in sys.argv:
 for name in ['glibc/lib/ld-linux-aarch64.so.1','glibc/lib/libc.so.6','bin/grun','bin/python','bin/7z','glibc/lib/libX11.so.6','glibc/lib/libXrender.so.1','glibc/lib/libfreetype.so.6']:
  if mode=='missing-python' and name=='bin/python': continue
  if mode=='missing-loader' and name.endswith('ld-linux-aarch64.so.1'): continue
  if mode=='missing-7z' and name=='bin/7z': continue
  target=p/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_text('fixture'); target.chmod(0o700)
  if mode=='nonexecutable-7z' and name=='bin/7z': target.chmod(0o600)
''')
            apt.chmod(0o700)
            event = root / 'result'
            spec = root / 'install.conf'
            spec.write_text(f"TERMUX_GLIBC_EVENT_FILE='{event}'\nTERMUX_GLIBC_LOG_FILE='{root / 'install.log'}'\n")
            result = subprocess.run(['sh', str(SCRIPT), str(spec)], env=dict(os.environ, PREFIX=str(prefix), FIXTURE_MODE=mode), capture_output=True, text=True)
            calls = [json.loads(line) for line in (prefix / 'calls.jsonl').read_text().splitlines()]
            return result.returncode, event.read_text().strip(), calls

    def test_success_installs_host_packages_and_python_before_verifying(self):
        code, event, calls = self.execute('success')
        self.assertEqual((code, event), (0, 'SUCCEEDED'))
        self.assertEqual(calls[0], ['install', '-y', 'glibc-repo'])
        self.assertIn(['install', '-y', 'glibc', 'glibc-runner', 'python', '7zip'], calls)

    def test_package_failure_stops_before_subsequent_commands(self):
        code, event, calls = self.execute('package-failure')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')
        self.assertEqual(calls, [['install', '-y', 'glibc-repo']])

    def test_package_success_without_python_is_failure(self):
        code, event, _ = self.execute('missing-python')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')

    def test_package_success_without_loader_is_failure(self):
        code, event, _ = self.execute('missing-loader')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')

    def test_package_success_without_7z_is_failure(self):
        code, event, _ = self.execute('missing-7z')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')

    def test_nonexecutable_7z_is_failure(self):
        code, event, _ = self.execute('nonexecutable-7z')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')

if __name__ == '__main__': unittest.main()
