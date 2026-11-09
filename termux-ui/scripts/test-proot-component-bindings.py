#!/usr/bin/env python3
"""Contract tests exercise the actual shell parser and exec argv without Android."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/proot_component_bindings.sh'
SHA = 'a' * 64

class BindingsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bindings $' = ")
        self.addCleanup(self.temp.cleanup)
        self.files = Path(self.temp.name)
        self.version = self.files / 'front/components/install/rootfs_wine_10.0/versions/v1-aaaa'
        self.content = self.version / "wine $' = 目录"
        (self.content / 'bin').mkdir(parents=True)
        (self.content / 'bin/wine64').write_bytes(b'executable')
        (self.version / '.games-component.properties').write_text(
            'schemaVersion=1\npackageName=rootfs_wine_10.0\nversion=1\nsha256=' + SHA + '\n')
        self.mock = self.files / 'mock-proot'
        self.mock.write_text('#!/bin/sh\nfor arg do printf "<%s>\\n" "$arg"; done\n')
        self.mock.chmod(0o700)

    def fields(self, **changes):
        values = dict(componentId='rootfs_wine_10.0', version='1', sha256=SHA,
            versionDirectory=str(self.version), contentRelativePath=self.content.name,
            guestTarget='/opt/box64-wine', role='WINE', revision='1')
        values.update(changes)
        return 'bindingCount=1\n' + ''.join('binding.0.' + k + '=' +
            base64.b64encode(v.encode()).decode() + '\n' for k, v in values.items())

    def run_fields(self, fields, tail='proot_exec_bound --kill-on-exit /usr/bin/env "literal $ value"'):
        spec = self.files / 'snapshot'
        spec.write_text(fields)
        return subprocess.run(['sh', '-c', '''
TERMUX_FILES_DIR=$1
PROOT_BIN=$2
. "$3"
while IFS= read -r line; do
    proot_binding_field "${line%%=*}" "${line#*=}" || exit 64
done < "$4"
proot_bindings_complete || exit 65
''' + tail, 'test', str(self.files), str(self.mock), str(HELPER), str(spec)],
            text=True, capture_output=True)

    def test_special_characters_remain_one_bind_argument_and_are_never_executed(self):
        p = self.run_fields(self.fields())
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual(['<-b>', '<' + str(self.content) + ':/opt/box64-wine>',
            '<--kill-on-exit>', '</usr/bin/env>', '<literal $ value>'], p.stdout.splitlines())

    def test_host_reader_uses_exact_same_content(self):
        p = self.run_fields(self.fields(), 'proot_binding_host_path /opt/box64-wine/bin/wine64')
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual(str(self.content / 'bin/wine64') + '\n', p.stdout)

    def test_unapproved_guest_target_is_rejected_before_proot(self):
        p = self.run_fields(self.fields(guestTarget='/usr'))
        self.assertEqual(64, p.returncode)
        self.assertEqual('', p.stdout)

    def test_changed_digest_still_uses_selected_version(self):
        p = self.run_fields(self.fields(sha256='b'*64))
        self.assertEqual(0,p.returncode,p.stderr)
        self.assertIn(str(self.version), p.stdout)

    def test_content_link_escape_is_rejected(self):
        (self.version / 'outside').symlink_to(self.files, target_is_directory=True)
        p = self.run_fields(self.fields(contentRelativePath='outside'))
        self.assertEqual(64, p.returncode)

    def test_package_link_escape_is_rejected(self):
        actual = self.version.parents[1].rename(self.files / 'outside-package')
        self.version.parents[1].symlink_to(actual, target_is_directory=True)
        self.assertEqual(64, self.run_fields(self.fields()).returncode)

    def test_duplicate_and_missing_fields_are_rejected(self):
        fields = self.fields()
        self.assertEqual(64, self.run_fields(fields + 'bindingCount=1\n').returncode)
        self.assertNotEqual(0, self.run_fields(fields.rsplit('\n', 2)[0] + '\n').returncode)
        self.assertEqual(64, self.run_fields(fields.replace('bindingCount=1', 'bindingCount=17')).returncode)

    def test_newline_and_nul_cannot_be_silently_trimmed(self):
        for value in ['WINE\n', 'WI\x00NE', 'WI\rNE']:
            self.assertEqual(64, self.run_fields(self.fields(role=value)).returncode)

    def test_no_bindings_for_hangover_remains_valid(self):
        p = self.run_fields('bindingCount=0\n')
        self.assertEqual(0, p.returncode)
        self.assertNotIn('<-b>', p.stdout)

class SetupInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="setup $' = 资料 ")
        self.addCleanup(self.temp.cleanup)
        self.files = Path(self.temp.name)
        self.private = self.files / 'front'
        self.runtime = self.private / 'runtime'
        self.spec = self.runtime / 'setup/specs/task.setupspec'
        self.spec.parent.mkdir(parents=True)
        (self.runtime / 'proot_component_bindings.sh').write_bytes(HELPER.read_bytes())
        (self.runtime / 'rootfs_script_common.sh').write_bytes(
            (HELPER.parent / 'rootfs_script_common.sh').read_bytes())
        (self.runtime / 'rootfs_prefix_warmup.sh').write_bytes(
            (HELPER.parent / 'rootfs_prefix_warmup.sh').read_bytes())
        self.context = self.runtime / 'setup/staging/task'
        self.context.mkdir(parents=True)
        self.events = self.runtime / 'setup/events/task.jsonl'
        self.log = self.runtime / 'setup/logs/task.log'
        self.recipe = self.runtime / 'recipes/base'
        self.component = self.private / 'components/install/rootfs_hangover_11.16/versions/v1-aaaa'
        self.component.mkdir(parents=True)
        self.values = dict(taskId='task', packageName='debian-13-games-rootfs', version='11',
            recipeSha256=SHA, containerId='container', containerName='container', baseOnly='true',
            buildContext=str(self.context), recipeDirectory=str(self.recipe),
            eventsPath=str(self.events), logPath=str(self.log), winePackage='',
            winePrefixDirectory='', homeDirectory='', prefixWarmupScript=str(self.runtime / 'rootfs_prefix_warmup.sh'),
            runtimeTranslator='', box64ComponentId='')

    def encoded(self, values):
        return ''.join(k+'='+base64.b64encode(v.encode()).decode()+'\n' for k,v in values.items())

    def run_setup(self, schema=4, extra='', **changes):
        values = dict(self.values, **changes)
        if schema == 4:
            raw = 'schemaVersion=4\n' + self.encoded(values)
            raw += 'componentCount=1\n' + self.encoded({
                'component.0.id':'rootfs_hangover_11.16', 'component.0.directory':str(self.component)})
            raw += 'bindingCount=0\n'
        else:
            raw = 'schemaVersion=3\n' + ''.join(k+'='+v+'\n' for k,v in values.items())
            raw += 'component=rootfs_hangover_11.16|'+str(self.component)+'\n'
        self.spec.write_text(raw + extra)
        env = dict(os.environ, PREFIX=str(self.files / 'missing-host-tools'))
        return subprocess.run(['sh', str(HELPER.parent / 'setup_rootfs_runtime.sh'), str(self.spec)],
            env=env, text=True, capture_output=True)

    def test_v4_decodes_special_paths_before_reaching_dependency_gate(self):
        p = self.run_setup()
        self.assertEqual(69, p.returncode, p.stderr)
        self.assertIn('termux_pkg_missing', self.events.read_text())
        self.assertFalse(self.context.exists())

    def test_v11_rejects_legacy_v3_before_mutation(self):
        p = self.run_setup(schema=3)
        self.assertEqual(64, p.returncode, p.stderr)
        self.assertIn('unsupported_setup_spec', p.stderr)
        self.assertTrue(self.context.exists())
        self.assertFalse(self.events.exists())

    def test_invalid_private_path_never_deletes_the_supplied_directory(self):
        outside = self.files / 'preserved'
        outside.mkdir()
        sentinel = outside / 'save'
        sentinel.write_text('keep')
        p = self.run_setup(buildContext=str(outside))
        self.assertEqual(64, p.returncode)
        self.assertEqual('keep', sentinel.read_text())
        self.assertFalse(self.events.exists())

    def test_duplicate_protocol_fields_and_encoded_newlines_are_rejected(self):
        p = self.run_setup(extra='bindingCount=0\n')
        self.assertEqual(64, p.returncode)
        self.assertTrue(self.context.exists())
        p = self.run_setup(containerName='container\nforged')
        self.assertEqual(64, p.returncode)
        self.assertTrue(self.context.exists())

if __name__ == '__main__':
    unittest.main()
