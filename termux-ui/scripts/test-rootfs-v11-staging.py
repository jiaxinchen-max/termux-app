#!/usr/bin/env python3
"""Retired v11 setup requests must fail without running the guest installer.

The current publication/recovery contract is tested by test-rootfs-v12-staging.py.
The former twenty apt/trim scenarios belong to the replaced v11 implementation.
"""
import base64
from pathlib import Path
import subprocess
import shutil
import tempfile

SCRIPT = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/setup_rootfs_runtime.sh'
with tempfile.TemporaryDirectory() as td:
    private = Path(td) / 'files/front'
    spec = private / 'runtime/setup/specs/old.setupspec'
    spec.parent.mkdir(parents=True)
    helper = private / 'runtime/proot_component_bindings.sh'
    shutil.copyfile(SCRIPT.with_name('proot_component_bindings.sh'), helper)
    enc = lambda value: base64.b64encode(value.encode()).decode()
    spec.write_text('schemaVersion=4\nversion=' + enc('11') + '\ncomponentCount=1\ncomponent.0.id=' + enc('old-source') + '\ncomponent.0.directory=' + enc(str(private/'old-source')) + '\nbindingCount=0\n')
    marker = private / 'existing-game.save'
    marker.write_text('keep')
    result = subprocess.run(['sh', str(SCRIPT), str(spec)], capture_output=True, text=True)
    assert result.returncode == 64, result.stderr
    assert 'unsupported_setup_spec' in result.stderr
    assert marker.read_text() == 'keep'
    assert not (Path(td) / 'rootfs').exists()
print('Retired v11 preparation rejected; current publication scenarios are in v12 tests.')
