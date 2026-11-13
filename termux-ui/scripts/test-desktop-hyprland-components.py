#!/usr/bin/env python3
"""Real static bundles, transaction safety, extraction boundaries and identity consistency."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'termux-ui/src/main/assets/local-games'
BUNDLES = ROOT / 'distribution/hyprland-themes/build'
spec = importlib.util.spec_from_file_location('components', ASSETS / 'desktop_hyprland_components.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)


class ComponentsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        p = patch.object(c, 'PREFIX', self.root / 'usr'); p.start(); self.addCleanup(p.stop)
        p = patch.object(c.urllib.request, 'build_opener'); self.opener = p.start().return_value; self.addCleanup(p.stop)
        self.opener.open.side_effect = lambda request, timeout: io.BytesIO((BUNDLES / request.full_url.rsplit('/', 1)[-1]).read_bytes())

    def testBothIndependentBundlesVerifyAndRepeatInstallationPreservesRuntimeAndUserConfig(self):
        runtime = c.PREFIX / 'opt/termux-hyprland/0.57.0-r7/bin/Hyprland'
        runtime.parent.mkdir(parents=True); runtime.write_bytes(b'unchanged-compositor')
        user = self.root / 'home/config/hyprland.lua'; user.parent.mkdir(parents=True); user.write_text('custom')
        for theme in ['classic-r7', 'm3ww-v1']:
            root = c.install_theme('0.57.0-r7', theme)
            before = (root.stat().st_ino, (root / '.installed').read_text())
            c.install_theme('0.57.0-r7', theme)
            self.assertEqual(before, (root.stat().st_ino, (root / '.installed').read_text()))
            c.verify_theme(root, '0.57.0-r7', theme)
        self.assertEqual(b'unchanged-compositor', runtime.read_bytes())
        self.assertEqual('custom', user.read_text())

    def testUnknownRuntimeOrThemeNeverCreatesDirectories(self):
        for runtime, theme in [('0.57.0-r8', 'm3ww-v1'), ('0.57.0-r7', '../../evil')]:
            with self.assertRaises(ValueError): c.install_theme(runtime, theme)
        self.assertFalse(c.PREFIX.exists())

    def testTamperedArchiveLeavesOldThemeIntact(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        before = (root / 'wallpaper.svg').read_bytes()
        bad = self.root / 'bad.tgz'; bad.write_bytes(b'not a theme')
        with self.assertRaisesRegex(RuntimeError, 'SHA-256'):
            c.install_theme('0.57.0-r7', 'classic-r7', bad)
        self.assertEqual(before, (root / 'wallpaper.svg').read_bytes())

    def testDamagedInstalledResourceIsRejectedAndCanBeRepaired(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        (root / 'wallpaper.svg').write_text('tampered')
        with self.assertRaisesRegex(RuntimeError, 'resource mismatch'):
            c.verify_theme(root, '0.57.0-r7', 'classic-r7')
        c.install_theme('0.57.0-r7', 'classic-r7')
        c.verify_theme(root, '0.57.0-r7', 'classic-r7')

    def testSymlinkInstallationDirectoryNeverWritesOutsidePrefix(self):
        outside = self.root / 'outside'; outside.mkdir()
        target = c.PREFIX / 'share/termux-hyprland'; target.parent.mkdir(parents=True)
        target.symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, 'Unsafe'): c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertEqual([], list(outside.iterdir()))

    def testEvenTrustedMalformedArchivesCannotEscapeOrPublish(self):
        for kind in ['traversal', 'symlink', 'duplicate', 'oversize']:
            with self.subTest(kind=kind):
                catalog = copy.deepcopy(c.CATALOG)
                archive = self.root / (kind + '.tgz')
                with tarfile.open(archive, 'w:gz') as tar:
                    n = '../escape' if kind == 'traversal' else 'wallpaper.svg'
                    info = tarfile.TarInfo(n)
                    if kind == 'symlink': info.type = tarfile.SYMTYPE; info.linkname = '/tmp/escape'
                    elif kind == 'oversize': info.size = 17 * 1024 * 1024
                    else: info.size = 1
                    tar.addfile(info, None if kind == 'symlink' else io.BytesIO(b'x' * info.size))
                    if kind == 'duplicate': tar.addfile(info, io.BytesIO(b'y'))
                catalog['themes']['classic-r7']['archive_sha256'] = c.digest(archive)
                with patch.object(c, 'CATALOG', catalog), self.assertRaisesRegex(RuntimeError, 'Unsafe|limits'):
                    c.install_theme('0.57.0-r7', 'classic-r7', archive)
                self.assertFalse(c.theme_root('classic-r7').exists())
                self.assertFalse((self.root / 'escape').exists())

    def testRenameFailureRestoresPreviousInstallation(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7'); (root / 'wallpaper.svg').write_text('old-damaged')
        original = Path.rename
        def rename(path, target):
            if path.name.startswith('.install-'): raise OSError('simulated full disk')
            return original(path, target)
        with patch.object(Path, 'rename', rename), self.assertRaises(OSError):
            c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertEqual('old-damaged', (root / 'wallpaper.svg').read_text())
        self.assertFalse(root.with_name(root.name + '.repair-backup').exists())

    def testInterruptedRepairRecoversPreviousPackageBeforeRetry(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        old = (root / 'wallpaper.svg').read_bytes()
        root.rename(root.with_name(root.name + '.repair-backup'))
        c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertEqual(old, (root / 'wallpaper.svg').read_bytes())
        self.assertFalse(root.with_name(root.name + '.repair-backup').exists())

    def testInterruptedActivationCleansBackupOrRecoversItBeforeRepair(self):
        import shutil
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        backup = root.with_name(root.name + '.repair-backup')
        shutil.copytree(root, backup)
        self.opener.open.side_effect = c.urllib.error.URLError('offline')
        c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertFalse(backup.exists())
        shutil.copytree(root, backup)
        (root / 'wallpaper.svg').write_text('interrupted activation')
        c.install_theme('0.57.0-r7', 'classic-r7')
        c.verify_theme(root, '0.57.0-r7', 'classic-r7')
        self.assertFalse(backup.exists())

    def testMissingReceiptIsRepairedRatherThanReportedInstalled(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        (root / '.installed').unlink()
        c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertEqual(c.CATALOG['themes']['classic-r7']['manifest_sha256'],
                         (root / '.installed').read_text().strip())

    def testInstalledThemeWorksOfflineWithoutArchive(self):
        root = c.install_theme('0.57.0-r7', 'm3ww-v1')
        self.opener.open.side_effect = c.urllib.error.URLError('offline')
        before = self.opener.open.call_count
        c.install_theme('0.57.0-r7', 'm3ww-v1')
        self.assertEqual(before, self.opener.open.call_count)
        self.assertTrue(root.is_dir())

    def testVerifiedCacheRepairsOfflineAndDamagedCacheIsRedownloaded(self):
        root = c.install_theme('0.57.0-r7', 'classic-r7')
        (root / 'wallpaper.svg').write_text('damaged')
        self.opener.open.side_effect = c.urllib.error.URLError('offline')
        c.install_theme('0.57.0-r7', 'classic-r7')
        (root / 'wallpaper.svg').write_text('damaged again')
        cached = root.parent / '.cache' / (c.CATALOG['themes']['classic-r7']['archive_sha256'] + '.tgz')
        cached.write_bytes(b'bad-cache')
        with self.assertRaises(c.urllib.error.URLError): c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertFalse(cached.exists())
        self.assertEqual([], list(cached.parent.glob('.download-*')))
        self.assertEqual('damaged again', (root / 'wallpaper.svg').read_text())

    def testFailedDownloadsNeverPublishOrActivate(self):
        for error in [c.urllib.error.URLError('offline'), c.urllib.error.HTTPError('https://example.invalid',404,'missing',{},None)]:
            self.opener.open.side_effect = error
            with self.assertRaises(c.urllib.error.URLError): c.install_theme('0.57.0-r7', 'classic-r7')
            self.assertFalse(c.theme_root('classic-r7').exists())
        self.opener.open.side_effect = lambda *a, **k: io.BytesIO(b'corrupt')
        with self.assertRaisesRegex(RuntimeError, 'size/SHA-256'): c.install_theme('0.57.0-r7', 'classic-r7')
        self.opener.open.side_effect = lambda *a, **k: io.BytesIO(b'x' * 1041)
        with self.assertRaisesRegex(RuntimeError, 'size'): c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertEqual([], list((c.PREFIX / 'share/termux-hyprland/themes/.cache').glob('.download-*')))

    def testDownloadCancellationAndConcurrentInstallLeaveNoSuccess(self):
        class Cancelled(io.BytesIO):
            def read1(self, size): raise KeyboardInterrupt('cancelled')
        self.opener.open.side_effect = lambda *a, **k: Cancelled()
        with self.assertRaises(KeyboardInterrupt): c.install_theme('0.57.0-r7', 'classic-r7')
        directory = c.PREFIX / 'share/termux-hyprland/themes'
        self.assertEqual([], list((directory / '.cache').glob('.download-*')))
        with (directory / '.install.lock').open('a') as lock:
            c.fcntl.flock(lock, c.fcntl.LOCK_EX | c.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError): c.install_theme('0.57.0-r7', 'classic-r7')
        self.assertFalse(c.theme_root('classic-r7').exists())

    def testRedirectCannotDowngradeToHttp(self):
        with self.assertRaisesRegex(RuntimeError, 'HTTPS'):
            c.HttpsRedirect().redirect_request(None,None,302,'redirect',{},'http://example.invalid/unsafe')

    def testGeneratedConsumersAndPackagesAreReproducible(self):
        subprocess.run(['python3', str(ROOT / 'tools/build-hyprland-themes.py'), '--check'], check=True, capture_output=True)

    def testShellRejectsUnknownSelectionAndKeepsClassicDependenciesSeparate(self):
        script = '. "$1"; hyprland_resolve_components || exit $?; printf "%s" "$HYPRLAND_SUPPORT"'
        env = dict(os.environ, DESKTOP_HYPRLAND_RUNTIME_ID='0.57.0-r7', DESKTOP_HYPRLAND_THEME_ID='classic-r7')
        r = subprocess.run(['sh', '-c', script, 'sh', str(ASSETS / 'hyprland-components.sh')], env=env, capture_output=True, text=True)
        self.assertEqual(0, r.returncode); self.assertIn('xfce4-panel', r.stdout); self.assertNotIn('eww', r.stdout)
        env['DESKTOP_HYPRLAND_RUNTIME_ID'] = '0.57.0-r8'
        self.assertEqual(64, subprocess.run(['sh', '-c', script, 'sh', str(ASSETS / 'hyprland-components.sh')], env=env, capture_output=True).returncode)


if __name__ == '__main__': unittest.main()
