#!/usr/bin/env python3
"""Corrupt prefixes must fail before extraction/wineboot and retain all existing files."""
import os
import shutil
from pathlib import Path
import subprocess
import tempfile

asset = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/rootfs_prefix_warmup.sh'
for scenario in ('no-marker', 'no-registry', 'missing-drive', 'matching-marker-damaged', 'low-space'):
    with tempfile.TemporaryDirectory(prefix='prefix-recovery-') as directory:
        root = Path(directory)
        prefix = root / 'prefix'
        prefix.mkdir()
        (prefix / 'keep-office-document').write_text('user data')
        marker = prefix / '.games-runtime/runtime'
        marker.parent.mkdir()
        if scenario != 'no-marker':
            marker.write_text('/runtime|rootfs_hangover_11.16')
        if scenario not in ('no-registry', 'matching-marker-damaged', 'low-space'):
            (prefix / 'system.reg').write_text('registry')
            (prefix / 'user.reg').write_text('registry')
        if scenario != 'missing-drive':
            (prefix / 'drive_c').mkdir()
        if scenario == 'low-space':
            shutil.rmtree(prefix)
            prefix.mkdir()
            (root / 'bin').mkdir()
            mock = root / 'bin/df'
            mock.write_text("#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 10000000 0 1 1%% /\\n'\n")
            mock.chmod(0o700)
        before = {str(p.relative_to(prefix)): p.read_bytes()
                  for p in prefix.rglob('*') if p.is_file()}
        script = '''
set -eu
. "$ASSET"
warmup_fail() { printf '%s' "$1"; exit 70; }
PREFIX_PATH="$ROOT/prefix"
RUNTIME_ROOT_PATH=/runtime
WINE_PACKAGE=rootfs_hangover_11.16
LOG_PATH="$ROOT/log"
TERMUX_FILES_DIR="$ROOT"
PATH="$ROOT/bin:$PATH"
warmup_rootfs_prefix
'''
        result = subprocess.run(['sh', '-c', script], env=dict(os.environ, ROOT=str(root), ASSET=str(asset)),
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == 70, result.stderr
        assert result.stdout == ('rootfs_prefix_insufficient_storage' if scenario == 'low-space' else 'rootfs_prefix_recovery_required'), result.stdout
        after = {str(p.relative_to(prefix)): p.read_bytes()
                 for p in prefix.rglob('*') if p.is_file()}
        assert before == after
print('Wine prefix recovery and low storage protection passed (5 cases).')
