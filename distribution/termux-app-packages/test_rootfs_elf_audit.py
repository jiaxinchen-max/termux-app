#!/usr/bin/env python3
"""Use real compiler-produced DSOs to prove dependency failures and guest links."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('audit-rootfs-elf.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)


@unittest.skipUnless(shutil.which('cc'), 'real C compiler required')
class Audit(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lib = self.root / 'usr/lib/aarch64-linux-gnu'
        self.lib.mkdir(parents=True)
        (self.root/'dep.c').write_text('int dependency(void) { return 42; }\n')
        (self.root/'use.c').write_text('extern int dependency(void); int use(void) { return dependency(); }\n')
        self.cc('-shared', '-fPIC', '-nostdlib', str(self.root/'dep.c'), '-Wl,-soname,libneeded.so', '-o', str(self.lib/'libneeded.so'))
        self.cc('-shared', '-fPIC', '-nostdlib', str(self.root/'use.c'), '-L'+str(self.lib), '-Wl,--no-as-needed', '-lneeded', '-o', str(self.lib/'consumer.so'))
        self.machine = a.elf(self.lib/'consumer.so')['machine']

    def cc(self, *args):
        subprocess.run(['cc', *args], check=True, capture_output=True)

    def test_real_dso_and_dependency_resolve(self):
        result = a.scan(self.root, self.machine)
        self.assertEqual([], result['problems'])
        self.assertEqual(2, result['elfCount'])
        consumer = next(r for r in result['records'] if r['path'].endswith('consumer.so'))
        self.assertIn('libneeded.so', consumer['resolved'])

    def test_missing_transitive_dependency_fails_closed(self):
        (self.lib/'libneeded.so').unlink()
        result = a.scan(self.root, self.machine)
        self.assertEqual('MissingOrUnsupportedDependency', result['status'])
        self.assertEqual('libneeded.so', result['problems'][0]['needed'])

    def test_relocatable_object_cannot_impersonate_loadable_library(self):
        self.cc('-c', '-fPIC', str(self.root/'dep.c'), '-o', str(self.lib/'libneeded.so'))
        result = a.scan(self.root, self.machine)
        self.assertEqual('MissingOrUnsupportedDependency', result['status'])
        self.assertEqual('libneeded.so', result['problems'][0]['needed'])

    def test_absolute_guest_symlink_does_not_use_host_files(self):
        (self.lib/'libneeded.so').rename(self.lib/'entity.so')
        (self.lib/'libneeded.so').symlink_to('/usr/lib/aarch64-linux-gnu/entity.so')
        self.assertEqual([], a.scan(self.root, self.machine)['problems'])
        (self.lib/'libneeded.so').unlink()
        (self.lib/'libneeded.so').symlink_to('/etc/passwd')
        self.assertTrue(a.scan(self.root, self.machine)['problems'])

    def test_origin_runpath_resolves_private_library(self):
        private = self.lib/'private'
        private.mkdir()
        (self.lib/'libneeded.so').rename(private/'libneeded.so')
        self.cc('-shared', '-fPIC', '-nostdlib', str(self.root/'use.c'), '-L'+str(private), '-Wl,--no-as-needed', '-lneeded', '-Wl,-rpath,$ORIGIN/private', '-o', str(self.lib/'consumer.so'))
        self.assertEqual([], a.scan(self.root, self.machine)['problems'])

    def test_real_executable_requires_its_guest_interpreter(self):
        source = self.root/'main.c'
        source.write_text('extern int dependency(void); void _start(void) { dependency(); }\n')
        executable = self.root/'probe'
        self.cc('-nostdlib', '-no-pie', '-Wl,-e,_start', str(source), '-L'+str(self.lib),
                '-Wl,--no-as-needed', '-lneeded', '-o', str(executable))
        interpreter = a.elf(executable)['interpreter']
        result = a.scan(self.root, self.machine)
        self.assertTrue(any(p.get('needed') == interpreter for p in result['problems']))
        target = self.root/interpreter.lstrip('/')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(interpreter).resolve(strict=True), target)
        self.assertEqual([], a.scan(self.root, self.machine)['problems'])

    def test_private_owner_context_is_explicit_and_soname_checked(self):
        private = self.lib/'private'
        private.mkdir()
        provider = private/'libneeded.so'
        (self.lib/'libneeded.so').rename(provider)
        self.assertTrue(a.scan(self.root, self.machine)['problems'])
        contexts = {'usr/lib/aarch64-linux-gnu/': {'libneeded.so': provider.relative_to(self.root).as_posix()}}
        self.assertEqual([], a.scan(self.root, self.machine, contexts)['problems'])
        self.cc('-shared', '-fPIC', '-nostdlib', str(self.root/'dep.c'), '-Wl,-soname,wrong.so', '-o', str(provider))
        self.assertTrue(a.scan(self.root, self.machine, contexts)['problems'])

    def test_emulated_context_requires_matching_exact_tree_gate(self):
        # An x86 DSO in its declared guest dir still fails the native audit
        # without a successful external closure report and current file hash.
        x86 = self.root / 'usr/lib/x86_64-linux-gnu'
        x86.mkdir()
        shutil.copyfile(self.lib / 'libneeded.so', x86 / 'libextra.so')
        for p in self.lib.iterdir():
            data = bytearray(p.read_bytes())
            data[18:20] = (183).to_bytes(2, 'little')
            p.write_bytes(data)
        self.assertTrue(a.scan(self.root)['problems'])
        report = {'rootfsRoot': str(self.root.resolve()), 'status': 'PassedDirectDependencyClosure',
                  'problems': [], 'backend': 'rootfs_proot', 'records': [
                      {'owner': 'rootfs-x86', 'path': 'usr/lib/x86_64-linux-gnu/libextra.so',
                       'machine': 62, 'class': 64, 'sha256': a.m.digest(x86 / 'libextra.so')}]}
        coverage = a.emulated_coverage(self.root, report)
        self.assertEqual([], a.scan(self.root, emulated=coverage)['problems'])
        report['status'] = 'FailedDirectDependencyClosure'
        with self.assertRaisesRegex(ValueError, 'external_audit_failed'):
            a.emulated_coverage(self.root, report)
        report['status'] = 'PassedDirectDependencyClosure'
        report['rootfsRoot'] = '/another/tree'
        with self.assertRaisesRegex(ValueError, 'external_audit_root'):
            a.emulated_coverage(self.root, report)
        (x86 / 'libextra.so').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'external_emulated_changed'):
            a.scan(self.root, emulated=coverage)

    def test_bad_machine_or_program_header_is_rejected(self):
        self.assertTrue(a.scan(self.root, 0)['problems'])
        broken = self.lib/'broken.so'
        broken.write_bytes(b'\x7fELF' + b'\0'*60)
        with self.assertRaises(ValueError):
            a.scan(self.root, self.machine)


if __name__ == '__main__':
    unittest.main()
