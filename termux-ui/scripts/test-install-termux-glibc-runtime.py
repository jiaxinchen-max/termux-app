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
if mode=='archive-package-failure' and 'zip' in sys.argv: sys.exit(25)
if mode=='xrandr-package-failure' and 'libxrandr-glibc' in sys.argv: sys.exit(24)
if 'libx11-glibc' in sys.argv:
 for name in ['glibc/lib/ld-linux-aarch64.so.1','glibc/lib/libc.so.6','bin/grun','bin/python','bin/7z','glibc/lib/libX11.so.6','glibc/lib/libXrender.so.1','glibc/lib/libXrandr.so.2','glibc/lib/libfreetype.so.6']+['bin/'+tool for tool in ['zip','unzip','tar','gzip','bzip2','xz','zstd']]:
  if mode=='missing-python' and name=='bin/python': continue
  if mode=='missing-loader' and name.endswith('ld-linux-aarch64.so.1'): continue
  if mode=='missing-7z' and name=='bin/7z': continue
  if mode=='missing-tool:'+name: continue
  if name.endswith('libXrandr.so.2'):
   if mode=='missing-xrandr' or 'libxrandr-glibc' not in sys.argv: continue
   if mode=='directory-xrandr':
    target=p/name; target.parent.mkdir(parents=True,exist_ok=True); target.mkdir(); continue
  target=p/name; target.parent.mkdir(parents=True,exist_ok=True); target.write_text('fixture'); target.chmod(0o700)
  if mode=='empty-xrandr' and name.endswith('libXrandr.so.2'): target.write_bytes(b'')
  if mode=='nonexecutable-7z' and name=='bin/7z': target.chmod(0o600)
  if mode=='nonexecutable-tool:'+name: target.chmod(0o600)
  if mode=='empty-tool:'+name: target.write_bytes(b'')
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
entries=[line.split() for line in config.splitlines() if not line.lstrip().startswith('#')]
assert ['zh_CN.GBK','GBK'] in entries and ['zh_CN.UTF-8','UTF-8'] in entries
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
            configurations = {
                'official-trailing-spaces': '#zh_CN.GB18030 GB18030  \n#zh_CN.GBK GBK  \n#zh_CN.UTF-8 UTF-8  \n#zh_CN GB2312  \n',
                'tabs-and-indentation': ' \t#\tzh_CN.GBK\tGBK\t \n\t# zh_CN.UTF-8\t UTF-8\t\n',
                'enabled-whitespace': ' \tzh_CN.GBK\tGBK  \n\tzh_CN.UTF-8   UTF-8\t\n',
                'missing-gbk-entry': '#zh_CN.UTF-8 UTF-8\n',
                'wrong-charset': '#zh_CN.GBK UTF-8\n#zh_CN.UTF-8 UTF-8\n',
                'similar-locale-name': '#zh_CNxGBK GBK\n#zh_CN.UTF-8 UTF-8\n',
            }
            if mode!='missing-config':
                config.write_text(configurations.get(mode, '# zh_CN.GBK GBK\n# zh_CN.UTF-8 UTF-8\n'))
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
            self.locale_config=config.read_text() if config.exists() else ''
            return result.returncode, event.read_text().strip(), calls

    def test_success_installs_host_packages_and_python_before_verifying(self):
        code, event, calls = self.execute('success')
        self.assertEqual((code, event), (0, 'SUCCEEDED'))
        self.assertEqual(calls[0], ['install', '-y', 'glibc-repo'])
        self.assertIn(['install', '-y', 'glibc', 'glibc-runner', 'python',
                       '7zip', 'zip', 'unzip', 'tar', 'gzip', 'bzip2', 'xz-utils', 'zstd'], calls)
        self.assertTrue(any('libxrandr-glibc' in call for call in calls))

    def test_archive_package_failure_stops_before_graphics_and_locale(self):
        code, event, calls = self.execute('archive-package-failure')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')
        self.assertIn('zip', calls[-1])
        self.assertFalse(any('libx11-glibc' in call for call in calls))
        self.assertEqual(self.locale_calls, 0)
        self.assertFalse(self.marker_published)

    def test_invalid_archive_commands_after_package_success_cannot_report_ready(self):
        for tool in ['7z', 'zip', 'unzip', 'tar', 'gzip', 'bzip2', 'xz', 'zstd']:
            for failure in ['missing', 'nonexecutable', 'empty']:
                with self.subTest(tool=tool, failure=failure):
                    code, event, _ = self.execute(failure+'-tool:bin/'+tool)
                    self.assertNotEqual(code, 0)
                    self.assertEqual(event, 'FAILED')
                    self.assertEqual(self.locale_calls, 0)
                    self.assertFalse(self.marker_published)

    def test_xrandr_package_failure_stops_before_locale_generation(self):
        code, event, calls = self.execute('xrandr-package-failure')
        self.assertNotEqual(code, 0)
        self.assertEqual(event, 'FAILED')
        self.assertIn('libxrandr-glibc', calls[-1])
        self.assertEqual(self.locale_calls, 0)
        self.assertFalse(self.marker_published)
        self.assertNotIn('Official Termux GLIBC runtime is ready.', self.output)

    def test_invalid_xrandr_after_package_success_cannot_report_ready(self):
        for mode in ['missing-xrandr', 'empty-xrandr', 'directory-xrandr']:
            with self.subTest(mode=mode):
                code, event, _ = self.execute(mode)
                self.assertNotEqual(code, 0)
                self.assertEqual(event, 'FAILED')
                self.assertEqual(self.locale_calls, 0)
                self.assertFalse(self.marker_published)
                self.assertNotIn('Official Termux GLIBC runtime is ready.', self.output)

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

    def test_official_trailing_spaces_are_valid_and_other_locales_unchanged(self):
        code,event,_=self.execute('official-trailing-spaces')
        self.assertEqual((code,event),(0,'SUCCEEDED'),self.output)
        self.assertEqual(self.locale_calls,1)
        self.assertTrue(self.marker_published)
        self.assertIn('#zh_CN.GB18030 GB18030  \n',self.locale_config)
        self.assertIn('#zh_CN GB2312  \n',self.locale_config)

    def test_whitespace_separated_fields_and_enabled_entries_are_valid(self):
        for mode in ['tabs-and-indentation','enabled-whitespace']:
            with self.subTest(mode=mode):
                code,event,_=self.execute(mode)
                self.assertEqual((code,event),(0,'SUCCEEDED'),self.output)
                self.assertEqual(self.locale_calls,1)
                self.assertTrue(self.marker_published)

    def test_missing_or_inexact_locale_fields_stop_before_generation(self):
        for mode in ['missing-gbk-entry','wrong-charset','similar-locale-name']:
            with self.subTest(mode=mode):
                code,event,_=self.execute(mode)
                self.assertNotEqual(code,0)
                self.assertEqual(event,'FAILED')
                self.assertEqual(self.locale_calls,0)
                self.assertFalse(self.marker_published)

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
