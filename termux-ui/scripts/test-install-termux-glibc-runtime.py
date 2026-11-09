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
            locale_script = prefix / 'glibc/bin/locale-gen'
            locale_script.parent.mkdir(parents=True, exist_ok=True)
            if mode != 'missing-locale-gen':
                locale_script.write_text('#!' + sys.executable + '\n' + """import os,sys,json
from pathlib import Path
p=Path(os.environ['PREFIX']); mode=os.environ['FIXTURE_MODE']
with (p/'locale-calls.jsonl').open('a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')
config=(p/'glibc/etc/locale.gen').read_text()
assert 'zh_CN.GBK GBK' in config.splitlines() and 'zh_CN.UTF-8 UTF-8' in config.splitlines()
assert os.environ['PATH'].split(':')[0]==str(p/'glibc/bin')
if mode in ['locale-failure','stale-marker-failure']: sys.exit(31)
for name in ['zh_CN.utf8','zh_CN.gbk']:
 if mode=='missing-gbk' and name=='zh_CN.gbk': continue
 target=p/'glibc/lib/locale'/name/'LC_CTYPE';target.parent.mkdir(parents=True,exist_ok=True)
 if mode=='directory-output': target.mkdir()
 else: target.write_bytes(b'' if mode=='empty-output' else b'compiled locale')
""")
                locale_script.chmod(0o700)
            config=prefix/'glibc/etc/locale.gen'
            config.parent.mkdir(parents=True,exist_ok=True)
            if mode!='missing-config': config.write_text('# zh_CN.GBK GBK\n# zh_CN.UTF-8 UTF-8\n')
            marker=prefix/'glibc/termux-box/run/games-zh-cn-locale-v1'
            if mode in ['reuse','stale-marker-failure']:
                marker.parent.mkdir(parents=True,exist_ok=True);marker.touch()
                for name in ['zh_CN.utf8','zh_CN.gbk']:
                    if mode=='stale-marker-failure' and name=='zh_CN.gbk': continue
                    data=prefix/'glibc/lib/locale'/name/'LC_CTYPE'
                    data.parent.mkdir(parents=True,exist_ok=True);data.write_bytes(b'existing locale')
            event = root / 'result'
            spec = root / 'install.conf'
            spec.write_text(f"TERMUX_GLIBC_EVENT_FILE='{event}'\nTERMUX_GLIBC_LOG_FILE='{root / 'install.log'}'\n")
            result = subprocess.run(['sh', str(SCRIPT), str(spec)], env=dict(os.environ, PREFIX=str(prefix), FIXTURE_MODE=mode), capture_output=True, text=True)
            calls = [json.loads(line) for line in (prefix / 'calls.jsonl').read_text().splitlines()]
            generated=prefix/'locale-calls.jsonl'
            self.locale_calls=len(generated.read_text().splitlines()) if generated.exists() else 0
            self.marker_published=marker.is_file()
            self.output=result.stdout+result.stderr
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

    def test_first_install_generates_locales_before_reporting_success(self):
        code,event,_=self.execute('success')
        self.assertEqual((code,event),(0,'SUCCEEDED'))
        self.assertEqual(self.locale_calls,1)
        self.assertTrue(self.marker_published)

    def test_existing_data_is_reused_without_generation(self):
        code,event,_=self.execute('reuse')
        self.assertEqual((code,event),(0,'SUCCEEDED'))
        self.assertEqual(self.locale_calls,0)
        self.assertTrue(self.marker_published)

    def test_generation_failure_stops_installation(self):
        code,event,_=self.execute('locale-failure')
        self.assertNotEqual(code,0)
        self.assertEqual(event,'FAILED')
        self.assertFalse(self.marker_published)
        self.assertNotIn('Official Termux GLIBC runtime is ready.',self.output)

    def test_incomplete_or_empty_generation_cannot_publish_ready(self):
        for mode in ['missing-gbk','empty-output','directory-output','missing-locale-gen','missing-config']:
            with self.subTest(mode=mode):
                code,event,_=self.execute(mode)
                self.assertNotEqual(code,0)
                self.assertEqual(event,'FAILED')
                self.assertFalse(self.marker_published)

    def test_old_marker_does_not_hide_missing_data_or_survive_failure(self):
        code,event,_=self.execute('stale-marker-failure')
        self.assertNotEqual(code,0)
        self.assertEqual(event,'FAILED')
        self.assertEqual(self.locale_calls,1)
        self.assertFalse(self.marker_published)

if __name__ == '__main__': unittest.main()
