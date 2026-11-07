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
        with self.assertRaisesRegex((ValueError,OSError), text): self.run_restore()
        self.assertFalse(self.target.exists())
        self.assertFalse(self.target.with_name('game.prebuilt-stage').exists())

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

    def test_concurrent_publication_preserves_first_completed_prefix(self):
        context = multiprocessing.get_context('fork')
        copying = context.Event(); release = context.Event(); results = context.Queue()
        def first():
            original=m.copy_verified
            def slow_copy(*args):
                copying.set()
                if not release.wait(5): raise RuntimeError('test synchronization timeout')
                original(*args)
            m.copy_verified=slow_copy
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
        with (self.assets/'delta.tgz').open('ab') as f:f.write(b'bad')
        self.rejected('asset_digest_mismatch')

    def test_runtime_identity(self):
        self.meta['runtimeFiles']={'usr/lib/wine/public.dll':'0'*64}
        (self.assets/'bundle.json').write_text(json.dumps(self.meta))
        self.rejected('runtime_mismatch')

    def test_public_digest_mismatch_leaves_no_ready_prefix(self):
        (self.root/'usr/lib/wine/public.dll').write_bytes(b'wrong  dll')
        self.rejected('file_digest_mismatch')

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
        self.rejected('recipe_mismatch')

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
        real=m.copy_verified
        def cancel(*args): real(*args); raise SystemExit(143)
        with patch.object(m,'copy_verified',side_effect=cancel):
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
        for package,translator in [('rootfs_wine_10.0','box64'),('rootfs_hangover_11.16','hangover')]:
            with self.subTest(package=package), tempfile.TemporaryDirectory() as temp:
                header="""set -eu
resolve_rootfs_translator() { :; }
fail() { exit 90; }
WARMUP_DISPLAY_PID=
trap '[ -z "$WARMUP_DISPLAY_PID" ] || kill "$WARMUP_DISPLAY_PID" 2>/dev/null || true' EXIT
prepare_external_prefix_template() {
  if [ "$RUNTIME_TRANSLATOR" = hangover ]; then
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
                             PATH=temp+os.pathsep+os.environ['PATH'],
                             LOG_PATH=str(Path(temp)/'log')),timeout=5)
                self.assertEqual(0,result.returncode,result.stderr)

class BundledAssetTests(unittest.TestCase):
    def test_signed_apk_input_is_clean_and_hashes_match(self):
        assets=ASSETS/'runtime-rootfs-v11/prefix-hangover-pdb2'
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
