#!/usr/bin/env python3
"""Exercise restoration/publication and hostile inputs with real tar/JSON/files."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import subprocess
import multiprocessing
import sys
sys.dont_write_bytecode = True
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1]/'src/main/assets/local-games'
spec = importlib.util.spec_from_file_location('restore', ASSETS/'restore_wine_prefix.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        self.root = self.base/'rootfs'
        self.assets = self.base/'recipe/prefix-hangover-pdb2'
        self.assets.mkdir(parents=True)
        (self.root/'usr/lib/wine').mkdir(parents=True)
        (self.root/'usr/lib/wine/public.dll').write_bytes(b'public dll')
        self.target = self.base/'game'
        for name in ['setup-container.sh', 'games-runtime.properties']:
            (self.assets.parent/name).write_text(name)
        self.files = {'system.reg': b'WINE REGISTRY Version 2', 'user.reg': b'registry',
                      'userdef.reg': b'registry', '.update-timestamp': b'disable\n',
                      'drive_c/windows/system32/ntdll.dll': b'public dll',
                      'drive_c/windows/syswow64/ntdll.dll': b'32bit dll'}
        self.records = [{'path': name, 'bytes': len(data), 'mode': 0o600,
                         'mtime': 12345, 'sha256': hashlib.sha256(data).hexdigest()}
                        for name, data in self.files.items()]
        self.records[-2]['source'] = 'usr/lib/wine/public.dll'
        self.extra = []
        self.pack()

    def pack(self):
        with tarfile.open(self.assets/'delta.tgz', 'w:gz') as archive:
            for r in self.records:
                if 'source' in r: continue
                data = self.files[r['path']]
                member = tarfile.TarInfo(r['path']); member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            for name, target in m.LINKS.items():
                member = tarfile.TarInfo(name); member.type = tarfile.SYMTYPE; member.linkname = target
                archive.addfile(member)
            for member, data in self.extra:
                archive.addfile(member, io.BytesIO(data) if data is not None else None)
        (self.assets/'manifest.json').write_text(json.dumps(self.records))
        self.pin()

    def pin(self):
        h = hashlib.sha256()
        for name in ['setup-container.sh', 'games-runtime.properties']:
            h.update((self.assets.parent/name).read_bytes())
        self.meta = {'schemaVersion':1, 'backend':'rootfs_proot','hostAbi':'aarch64',
                     'prefixArch':'wow64','winePackage':'rootfs_hangover_11.16',
                     'baseRecipeSha256':h.hexdigest(), 'runtimeFiles':{},
                     'expandedBytes':sum(r['bytes'] for r in self.records),
                     'assets': {name: m.sha(self.assets/name) for name in ['manifest.json','delta.tgz']}}
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))

    def run_restore(self, target=None):
        with contextlib.redirect_stdout(io.StringIO()):
            m.restore(self.root, self.assets, target or self.target, 'a'*64,
                      str(self.root), 'rootfs_hangover_11.16')

    def rejected(self, text):
        with self.assertRaisesRegex((ValueError,OSError,tarfile.TarError), text): self.run_restore()
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_name('game.prebuilt-stage').exists())

    def test_different_content_restores_without_hash_gate(self):
        (self.root/'usr/lib/wine/public.dll').write_bytes(b'larger custom replacement dll')
        self.run_restore()
        self.assertEqual((self.target/'drive_c/windows/system32/ntdll.dll').read_bytes(), b'larger custom replacement dll')

    def test_archive_lengths_and_expanded_estimate_do_not_gate_content(self):
        self.files['user.reg'] = b'longer registry contents than template'
        self.files['userdef.reg'] = b'x'
        self.pack()
        self.meta['expandedBytes'] = 1
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))
        self.run_restore()
        for name in ('user.reg', 'userdef.reg'):
            self.assertEqual((self.target/name).read_bytes(), self.files[name])

    def test_wrong_architecture_still_rejected(self):
        self.meta['hostAbi']='x86_64'
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))
        self.rejected('identity_mismatch')

    def test_independent_copies_and_exact_markers(self):
        self.run_restore()
        second = self.base/'second'; self.run_restore(second)
        for name,data in self.files.items(): self.assertEqual(data,(self.target/name).read_bytes())
        name = 'drive_c/windows/system32/ntdll.dll'
        self.assertNotEqual((self.target/name).stat().st_ino, (second/name).stat().st_ino)
        self.assertEqual(1, (self.target/name).stat().st_nlink)
        (self.target/name).write_bytes(b'modified')
        self.assertEqual(b'public dll',(second/name).read_bytes())
        self.assertEqual(b'public dll',(self.root/'usr/lib/wine/public.dll').read_bytes())
        self.assertEqual('a'*64+'\n',(self.target/'.games-runtime/prepared-identity').read_text())
        self.assertEqual('/',os.readlink(self.target/'dosdevices/z:'))

    def test_real_shell_dispatch_restores_and_reuses_without_wine(self):
        (self.root/'etc').mkdir()
        (self.root/'etc/games-runtime.properties').write_text('recipeSha256='+'b'*64+'\n')
        self.target.mkdir()
        (self.base/'usr/bin').mkdir(parents=True)
        (self.base/'usr/bin/python').symlink_to(sys.executable)
        import shutil
        shutil.copyfile(ASSETS/'restore_wine_prefix.py',self.assets.parent/'restore_wine_prefix.py')
        script = """set -eu
. "$HELPER"
warmup_fail() { echo "$1" >&2; exit 70; }
progress() { :; }
WINE_PACKAGE=rootfs_hangover_11.16
RUNTIME_TRANSLATOR=hangover
RUNTIME_ROOT_PATH=$ROOTFS_CANONICAL
run_prefix_wineboot_update() { exit 99; }
prepare_external_prefix_template
warmup_rootfs_prefix
warmup_rootfs_prefix
require_external_prepared_prefix
[ ! -e "$TEMPLATE_CACHE_DIR" ]
"""
        env=dict(os.environ,HELPER=str(ASSETS/'rootfs_prefix_warmup.sh'),
                 PREFIX=str(self.base/'usr'),ROOTFS_CANONICAL=str(self.root),
                 PREFIX_PATH=str(self.target),RECIPE_DIRECTORY=str(self.assets.parent),
                 TEMPLATE_CACHE_DIR=str(self.base/'cache'),LOG_PATH=str(self.base/'log'))
        result=subprocess.run(['sh','-c',script],env=env,capture_output=True,text=True,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr+'\n'+(self.base/'log').read_text())

    def test_preparation_and_launch_share_key_without_rewriting_image(self):
        (self.root/'etc').mkdir()
        image_recipe='recipeSha256='+'a'*64+'\n'
        (self.root/'etc/games-runtime.properties').write_text(image_recipe)
        marker=self.target/'.games-runtime/preparation-recipe'
        script='''set -eu
. "$HELPER"
warmup_fail() { echo "$1" >&2; exit 70; }
GAMES_PREPARATION_RECIPE_SHA256=$(printf '%064d' 1)
resolve_external_template_identity
prepared=$GAMES_TEMPLATE_KEY
mkdir -p "$PREFIX_PATH/.games-runtime"
printf '%s\\n' "$GAMES_PREPARATION_RECIPE_SHA256" > "$PREFIX_PATH/.games-runtime/preparation-recipe"
unset GAMES_PREPARATION_RECIPE_SHA256
resolve_external_template_identity
[ "$prepared" = "$GAMES_TEMPLATE_KEY" ]
GAMES_PREPARATION_RECIPE_SHA256=$(printf '%064d' 2)
resolve_external_template_identity
[ "$prepared" != "$GAMES_TEMPLATE_KEY" ]
'''
        env=dict(os.environ,HELPER=str(ASSETS/'rootfs_prefix_warmup.sh'),ROOTFS_CANONICAL=str(self.root),
            PREFIX_PATH=str(self.target),TEMPLATE_CACHE_DIR=str(self.base/'cache'),
            WINE_PACKAGE='rootfs_hangover_11.16',RUNTIME_TRANSLATOR='hangover')
        result=subprocess.run(['sh','-c',script],env=env,capture_output=True,text=True,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(image_recipe,(self.root/'etc/games-runtime.properties').read_text())
        marker.write_text('')
        result=subprocess.run(['sh','-c','. "$HELPER"; warmup_fail() { exit 70; }; resolve_external_template_identity'],
            env=env,capture_output=True,text=True,timeout=10)
        self.assertEqual(70,result.returncode)

    def test_concurrent_publication_preserves_first_completed_prefix(self):
        context = multiprocessing.get_context('fork')
        copying = context.Event(); release = context.Event(); results = context.Queue()
        def first():
            original=m.copy_entry
            def slow_copy(*args):
                copying.set()
                if not release.wait(5): raise RuntimeError('test synchronization timeout')
                original(*args)
            m.copy_entry=slow_copy
            try: self.run_restore();results.put('first-published')
            except Exception as error:results.put(str(error))
        def second():
            try: self.run_restore();results.put('unexpected-second-publish')
            except ValueError as error:results.put(str(error))
        p1=context.Process(target=first);p2=context.Process(target=second)
        p1.start()
        try:
            self.assertTrue(copying.wait(5))
            p2.start();release.set()
            p1.join(5);p2.join(5)
            self.assertEqual(0,p1.exitcode);self.assertEqual(0,p2.exitcode)
            outcomes={results.get(timeout=2),results.get(timeout=2)}
            self.assertIn('first-published',outcomes)
            self.assertIn('rootfs_prebuilt_prefix_destination_not_empty',outcomes)
            self.assertEqual(b'WINE REGISTRY Version 2',(self.target/'system.reg').read_bytes())
        finally:
            release.set()
            for process in [p1,p2]:
                if process.is_alive():process.terminate();process.join(5)

    def test_nonempty_target_preserved(self):
        self.target.mkdir(); (self.target/'save').write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError,'destination_not_empty'): self.run_restore()
        self.assertEqual(b'keep',(self.target/'save').read_bytes())

    def test_corrupt_archive(self):
        (self.assets/'delta.tgz').write_bytes(b'invalid archive')
        self.rejected('not a gzip file')

    def test_runtime_identity(self):
        self.meta['runtimeFiles']={'usr/lib/wine/public.dll':'0'*64}
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))
        self.run_restore()

    def test_public_content_difference_is_accepted(self):
        (self.root/'usr/lib/wine/public.dll').write_bytes(b'x')
        self.run_restore()
        self.assertEqual((self.target/'drive_c/windows/system32/ntdll.dll').read_bytes(),b'x')

    def test_public_source_escape(self):
        (self.root/'usr/lib/wine/public.dll').unlink()
        (self.base/'secret').write_bytes(b'public dll')
        (self.root/'usr/lib/wine/public.dll').symlink_to(self.base/'secret')
        self.rejected('source_invalid')

    def test_source_parent_escape(self):
        (self.root/'usr/lib/wine/public.dll').unlink()
        (self.root/'usr/lib/wine').rmdir()
        (self.root/'usr/lib/wine').symlink_to(self.base)
        self.rejected('source_escape')

    def test_recipe_mismatch(self):
        (self.assets.parent/'setup-container.sh').write_text('other build')
        self.run_restore()

    def test_traversal_absolute_duplicate_hardlink_fifo_and_link_parent(self):
        for name,kind,link in [('../escape',tarfile.REGTYPE,''),('/escape',tarfile.REGTYPE,''),
                               ('system.reg',tarfile.REGTYPE,''),('hard',tarfile.LNKTYPE,'system.reg'),
                               ('fifo',tarfile.FIFOTYPE,''),('dosdevices/z:/escape',tarfile.REGTYPE,''),
                               ('unsafe',tarfile.SYMTYPE,'../../escape')]:
            with self.subTest(name=name):
                member=tarfile.TarInfo(name);member.type=kind;member.linkname=link
                self.extra=[(member,b'' if kind==tarfile.REGTYPE else None)];self.pack()
                self.rejected('invalid|duplicate')
        self.assertFalse((self.base/'escape').exists())

    def test_out_of_space(self):
        with patch.object(m.shutil,'disk_usage',return_value=type('Disk',(),{'free':0})()):
            self.rejected('insufficient_storage')

    def test_cancel_cleanup_and_retry(self):
        real=m.copy_entry
        def cancel(*args): real(*args); raise SystemExit(143)
        with patch.object(m,'copy_entry',side_effect=cancel):
            with self.assertRaises(SystemExit): self.run_restore()
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_name('game.prebuilt-stage').exists())
        self.run_restore()

    def test_recover_abandoned_private_stage(self):
        stage=self.target.with_name('game.prebuilt-stage'); stage.mkdir();(stage/'partial').write_bytes(b'partial')
        self.run_restore();self.assertFalse(stage.exists())

    def test_stage_symlink_and_destination_symlink_rejected(self):
        stage=self.target.with_name('game.prebuilt-stage');stage.symlink_to(self.root)
        with self.assertRaisesRegex(ValueError,'stage_invalid'):self.run_restore()
        self.assertTrue(stage.is_symlink());stage.unlink()
        self.target.symlink_to(self.root)
        with self.assertRaisesRegex(ValueError,'destination_invalid'):self.run_restore()
        self.assertTrue((self.root/'usr/lib/wine/public.dll').exists())

class SetupDisplayTests(unittest.TestCase):
    def test_only_legacy_initialization_needs_display_and_starts_it_first(self):
        source=(ASSETS/'setup_rootfs_runtime.sh').read_text()
        begin=source.rindex('\nresolve_rootfs_translator\n')
        end=source.index('\nwarmup_rootfs_prefix',begin)
        snippet=source[begin:end]
        for package,translator in [('rootfs_wine_10.0','box64'),('rootfs_proton_11.0','box64'),
                                   ('rootfs_hangover_11.16','hangover'),('custom-wine-test','box64')]:
            with self.subTest(package=package), tempfile.TemporaryDirectory() as temp:
                header="""set -eu
. "$HELPER"
resolve_rootfs_translator() { :; }
fail() { exit 90; }
WARMUP_DISPLAY_PID=
trap '[ -z "$WARMUP_DISPLAY_PID" ] || kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true' EXIT
prepare_external_prefix_template() {
  if prebuilt_prefix_supported; then
    [ -z "$WARMUP_DISPLAY_PID" ]
  else
    [ -n "$WARMUP_DISPLAY_PID" ] && kill -0 "$WARMUP_DISPLAY_PID"
  fi
}
"""
                binary=Path(temp)/'termux-x11'
                binary.write_text('#!/bin/sh\nexec sleep 30\n');binary.chmod(0o700)
                result=subprocess.run(['sh','-c',header+snippet],capture_output=True,text=True,
                    env=dict(os.environ,WINE_PACKAGE=package,RUNTIME_TRANSLATOR=translator,
                             HELPER=str(ASSETS/'rootfs_prefix_warmup.sh'),
                             PATH=temp+os.pathsep+os.environ['PATH'],
                             LOG_PATH=str(Path(temp)/'log')),timeout=5)
                self.assertEqual(0,result.returncode,result.stderr)


class StandardWineRestoreTests(unittest.TestCase):
    """Exercise actual restore/shell entry points with two independent immutable sources."""
    pack = RestoreTests.pack
    rejected = RestoreTests.rejected
    test_nonempty_target_preserved = RestoreTests.test_nonempty_target_preserved
    test_corrupt_archive = RestoreTests.test_corrupt_archive
    test_recipe_mismatch = RestoreTests.test_recipe_mismatch
    test_out_of_space = RestoreTests.test_out_of_space
    test_cancel_cleanup_and_retry = RestoreTests.test_cancel_cleanup_and_retry
    test_recover_abandoned_private_stage = RestoreTests.test_recover_abandoned_private_stage
    test_stage_symlink_and_destination_symlink_rejected = RestoreTests.test_stage_symlink_and_destination_symlink_rejected

    def setUp(self):
        RestoreTests.setUp(self)
        self.wine = self.base/'components/versions/1/wine'
        (self.wine/'lib/wine/x86_64-windows').mkdir(parents=True)
        (self.wine/'lib/wine/x86_64-windows/ntdll.dll').write_bytes(b'ordinary wine dll')
        self.receipt = self.wine.parent/'.games-component.properties'
        self.receipt.write_text('schemaVersion=1\npackageName=rootfs_wine_10.0\nversion=1\nsha256='+'c'*64+'\n')
        self.records[-2].update(source='lib/wine/x86_64-windows/ntdll.dll', sourceRoot='wine',
                               bytes=len(b'ordinary wine dll'), sha256=hashlib.sha256(b'ordinary wine dll').hexdigest())
        self.pack()

    def pin(self):
        RestoreTests.pin(self)
        self.meta.update(schemaVersion=2, winePackage='rootfs_wine_10.0', translator='box64',
                         peMachine=0x8664, componentVersion=1, sourceComponentSha256='c'*64,
                         wineRuntimeFiles={})
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))

    def run_restore(self, target=None):
        with contextlib.redirect_stdout(io.StringIO()):
            m.restore(self.root,self.assets,target or self.target,'a'*64,str(self.root),
                      'rootfs_wine_10.0',self.wine)

    def test_independent_copies_and_exact_markers(self):
        self.run_restore()
        other=self.base/'other';self.run_restore(other)
        dll='drive_c/windows/system32/ntdll.dll'
        self.assertEqual(b'ordinary wine dll',(self.target/dll).read_bytes())
        self.assertEqual('box64\n',(self.target/'.games-runtime/translator').read_text())
        self.assertEqual(str(self.root)+'|rootfs_wine_10.0\n',(self.target/'.games-runtime/runtime').read_text())
        (self.target/dll).write_bytes(b'game replacement')
        self.assertEqual(b'ordinary wine dll',(other/dll).read_bytes())
        self.assertEqual(b'ordinary wine dll',(self.wine/'lib/wine/x86_64-windows/ntdll.dll').read_bytes())

    def test_real_shell_dispatch_restores_and_reuses_without_wine(self):
        import shutil
        (self.root/'etc').mkdir()
        (self.root/'etc/games-runtime.properties').write_text('recipeSha256='+'b'*64+'\n')
        (self.base/'usr/bin').mkdir(parents=True)
        (self.base/'usr/bin/python').symlink_to(sys.executable)
        shutil.copyfile(ASSETS/'restore_wine_prefix.py',self.assets.parent/'restore_wine_prefix.py')
        self.assets.rename(self.assets.with_name('prefix-wine10-wow64'))
        script='''set -eu
. "$HELPER"
warmup_fail() { echo "$1" >&2; exit 70; }
progress() { :; }
WINE_PACKAGE=rootfs_wine_10.0
RUNTIME_TRANSLATOR=box64
box64_wine_root=/opt/box64-wine
RUNTIME_ROOT_PATH=$ROOTFS_CANONICAL
rootfs_component_host() { printf '%s\\n' "$TEST_WINE_ROOT"; }
run_prefix_wineboot_update() { exit 99; }
prepare_external_prefix_template
warmup_rootfs_prefix
warmup_rootfs_prefix
require_external_prepared_prefix
[ ! -e "$TEMPLATE_CACHE_DIR" ]
'''
        result=subprocess.run(['sh','-c',script],capture_output=True,text=True,timeout=10,
             env=dict(os.environ,HELPER=str(ASSETS/'rootfs_prefix_warmup.sh'),PREFIX=str(self.base/'usr'),
                      ROOTFS_CANONICAL=str(self.root),PREFIX_PATH=str(self.target),TEST_WINE_ROOT=str(self.wine),
                      RECIPE_DIRECTORY=str(self.assets.parent),TEMPLATE_CACHE_DIR=str(self.base/'cache'),
                      LOG_PATH=str(self.base/'restore.log'),TASK_ID='standard-wine'))
        self.assertEqual(0,result.returncode,result.stderr+(self.base/'restore.log').read_text())

    def test_component_receipt_mismatch(self):
        self.receipt.write_text(self.receipt.read_text().replace('c'*64,'d'*64))
        self.run_restore()

    def test_wrong_windows_architecture(self):
        self.meta['peMachine']=0xaa64
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))
        self.rejected('identity_mismatch')

    def test_component_source_escape(self):
        dll=self.wine/'lib/wine/x86_64-windows/ntdll.dll'
        dll.unlink();dll.symlink_to(self.root/'usr/lib/wine/public.dll')
        self.rejected('source_invalid')

    def test_unknown_source_root(self):
        self.records[-2]['sourceRoot']='arbitrary'
        self.pack();self.rejected('source_invalid')

    def test_missing_template_never_falls_back_to_initialization(self):
        (self.root/'etc').mkdir()
        (self.root/'etc/games-runtime.properties').write_text('recipeSha256='+'b'*64+'\n')
        script='''set -eu
. "$HELPER"
warmup_fail() { echo "$1" >&2; exit 70; }
progress() { :; }
rootfs_component_host() { printf '%s\\n' "$TEST_WINE_ROOT"; }
run_prefix_wineboot_update() { exit 99; }
prepare_external_prefix_template
warmup_rootfs_prefix
'''
        result=subprocess.run(['sh','-c',script],capture_output=True,text=True,timeout=5,
            env=dict(os.environ,HELPER=str(ASSETS/'rootfs_prefix_warmup.sh'),WINE_PACKAGE='rootfs_wine_10.0',
                     RUNTIME_TRANSLATOR='box64',box64_wine_root='/opt/box64-wine',
                     ROOTFS_CANONICAL=str(self.root),PREFIX_PATH=str(self.target),TEST_WINE_ROOT=str(self.wine),
                     RECIPE_DIRECTORY=str(self.assets.parent),TEMPLATE_CACHE_DIR=str(self.base/'cache')))
        self.assertEqual(70,result.returncode,result.stderr)
        self.assertIn('rootfs_prebuilt_prefix_missing',result.stderr)

class BundledAssetTests(unittest.TestCase):
    def test_standard_templates_match_own_reference_and_matching_profiles(self):
        catalog=json.loads((ASSETS.parent/'termux-app-packages/index-v1.json').read_text())
        packages={p['id']:p for p in catalog['packages']}
        for folder,package in [('prefix-wine10-wow64','rootfs_wine_10.0'),
                               ('prefix-proton11-wow64','rootfs_proton_11.0')]:
            assets=ASSETS.parents[4]/'distribution/termux-app-packages/prefix-assets'/folder
            meta=json.loads((assets/'bundle.json').read_text())
            self.assertEqual(2,meta['schemaVersion'])
            self.assertEqual(package,meta['winePackage'])
            self.assertEqual(packages[package]['sha256'],meta['sourceComponentSha256'])
            self.assertEqual(packages[package]['version'],meta['componentVersion'])
            for name in ['manifest.json','delta.tgz']:
                self.assertEqual(meta['assets'][name],m.sha(assets/name))
            records={r['path']:r for r in json.loads((assets/'manifest.json').read_text())}
            for arch,folder in [('x86_64-windows','system32'),('i386-windows','syswow64')]:
                record=records['drive_c/windows/'+folder+'/ntdll.dll']
                self.assertEqual('wine',record['sourceRoot'])
                self.assertEqual('lib/wine/'+arch+'/ntdll.dll',record['source'])
            self.assertFalse(any('aarch64-windows' in r.get('source','') for r in records.values()))
            expected_user='steamuser' if package=='rootfs_proton_11.0' else 'root'
            self.assertEqual(expected_user,meta['windowsUser'])
            with tarfile.open(assets/'delta.tgz') as archive:
                self.assertIn('drive_c/users/'+expected_user+'/Desktop',archive.getnames())
            # The initialized registry skeleton keeps its original provenance when a newer
            # same-architecture component supplies every runtime DLL.
            reference = ('f166f7daa1a37b3c8e0ade213a6e7915e582091804071a58a4a32c6c672e1595'
                         if package == 'rootfs_proton_11.0' else packages[package]['sha256'])
            self.assertEqual(reference,meta['provenance']['referenceComponentSha256'])
            if package=='rootfs_proton_11.0':
                controls=[r for r in records.values() if '/winsxs/' in r['path'] and
                          r['path'].endswith('/comctl32.dll')]
                self.assertEqual(2,len(controls))
                self.assertTrue(all(r['source'].endswith('/comctl32_v6.dll') for r in controls))

    def test_signed_apk_input_is_clean_and_hashes_match(self):
        assets=ASSETS.parents[4]/'distribution/termux-app-packages/legacy-prefix-assets/prefix-hangover-pdb2'
        meta=json.loads((assets/'bundle.json').read_text());records=json.loads((assets/'manifest.json').read_text())
        entries={r['path']:r for r in records}
        self.assertEqual(len(entries),len(records))
        self.assertEqual(meta['expandedBytes'],sum(r['bytes'] for r in records))
        for r in records:
            if 'source' in r:
                m.safe_name(r['source'])
                self.assertTrue(r['source'].startswith(m.SOURCE_PREFIXES))
        for name,expected in meta['assets'].items():self.assertEqual(expected,m.sha(assets/name))
        with tarfile.open(assets/'delta.tgz') as archive:
            for member in archive:
                m.safe_name(member.name)
                self.assertFalse(member.name.startswith('.games-runtime'))
                if member.isfile():
                    data=archive.extractfile(member).read()
                    self.assertEqual(entries[member.name]['sha256'],hashlib.sha256(data).hexdigest())
                    if member.name=='system.reg':self.assertNotIn(b'PendingFileRenameOperations',data)

if __name__=='__main__': unittest.main()
