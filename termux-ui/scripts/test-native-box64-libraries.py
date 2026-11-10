#!/usr/bin/env python3
"""Exercise the real Native selector/loader with isolated version directories."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/native_runtime_components.sh'


class SelectedLibrariesTest(unittest.TestCase):
    def test_missing_default_never_runs_another_installed_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ('lib/ld-linux-aarch64.so.1', 'bin/box64', 'components/glibc_box64_0.4.4/bin/box64'):
                binary = root / relative
                binary.parent.mkdir(parents=True, exist_ok=True)
                binary.write_text('#!/bin/sh\necho wrong-version\n')
                binary.chmod(0o755)
            result = subprocess.run(['sh', '-ec', '. "$1"; resolve_native_box64', 'test', str(HELPER)],
                env=dict(os.environ, TERMUX_GLIBC_DIR=str(root), GAMES_CUSTOM_BOX64_ID=''),
                text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('wrong-version', result.stdout)
            self.assertIn('box64_selected_binary_missing:', result.stderr)

    def test_loader_receives_only_selected_version_and_wine_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            loader = root / 'lib/ld-linux-aarch64.so.1'
            loader.parent.mkdir()
            loader.write_text('#!/bin/sh\nprintf "%s\\n" "$BOX64_LD_LIBRARY_PATH"\n')
            loader.chmod(0o755)
            ids = ['glibc_box64_0.2.8', 'glibc_box64_0.3.8', 'glibc_box64_0.4.4', 'custom-box64-test']
            for component in ids:
                base = root / 'components' / component
                binary = base / 'bin/box64'
                binary.parent.mkdir(parents=True)
                binary.touch()
                binary.chmod(0o755)
                (base / '.custom-component.properties').write_text(
                    f'schemaVersion=1\nruntimeBackend=glibc_termux_box\ncomponentId={component}\nkind=box64\n')
            env = dict(os.environ, TERMUX_GLIBC_DIR=str(root), WINE_PATH=str(root / 'wine'))
            result = subprocess.run(['sh', '-ec', '''
                . "$1"
                for GAMES_CUSTOM_BOX64_ID in glibc_box64_0.2.8 glibc_box64_0.3.8 glibc_box64_0.4.4 custom-box64-test; do
                    resolve_native_box64
                    resolve_native_box64
                    native_run_box64 test.exe
                done
                BOX64_LD_LIBRARY_PATH=/user/custom:/wine/extra
                GAMES_CUSTOM_BOX64_ID=glibc_box64_0.4.4
                resolve_native_box64
                native_run_box64 test.exe
                rm "$TERMUX_GLIBC_DIR/components/$GAMES_CUSTOM_BOX64_ID/bin/box64"
                if resolve_native_box64; then exit 23; fi
            ''', 'test', str(HELPER)], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            paths = result.stdout.splitlines()
            self.assertEqual(len(paths), 5)
            wine = root / 'wine'
            suffix = [str(wine / p) for p in ('lib64', 'lib64/wine/x86_64-unix', 'lib', 'lib/wine/x86_64-unix')]
            suffix.append(str(root / 'lib/x86_64-linux-gnu'))
            for component, actual in zip(ids, paths):
                self.assertEqual(actual.split(':'), [str(root / 'components' / component / 'lib/x86_64-linux-gnu')] + suffix)
            self.assertEqual(paths[4].split(':'), [str(root / 'components/glibc_box64_0.4.4/lib/x86_64-linux-gnu'), '/user/custom', '/wine/extra'])
            self.assertIn('box64_selected_binary_missing:glibc_box64_0.4.4', result.stderr)


if __name__ == '__main__':
    unittest.main()
