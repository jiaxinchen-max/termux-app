#!/usr/bin/env python3
"""Package a validated independent prefix delta for local APK trials.

This does not initialize Wine or certify graphics. Input evidence must describe
an isolated, stopped prefix, not an installed game's C drive.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    for arg in ['delta', 'manifest', 'runtime-guards', 'recipe', 'evidence', 'output']:
        p.add_argument('--' + arg, type=Path, required=True)
    a = p.parse_args()
    evidence = json.loads(a.evidence.read_text())
    assert evidence['state'] == 'PASSED' and evidence['post_boot_pending_renames'] == 0
    assert evidence['post_boot_tmp_files'] == 0
    assert evidence['asset']['sha256'] == sha(a.delta)
    records = [r for r in json.loads(a.manifest.read_text())
               if not r['path'].startswith('.games-runtime/')]
    assert not any(r['path'].endswith('.tmp') for r in records)
    a.output.mkdir(parents=True, exist_ok=False)
    out = a.output
    with tarfile.open(a.delta) as src, (out/'delta.tgz').open('wb') as raw:
        with gzip.GzipFile(fileobj=raw, mode='wb', filename='', mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w') as dst:
                for member in src:
                    if member.name == '.games-runtime' or member.name.startswith('.games-runtime/'):
                        continue
                    member.uid = member.gid = 0
                    member.uname = member.gname = ''
                    member.pax_headers = {}
                    data = src.extractfile(member) if member.isfile() else None
                    if member.name in ['system.reg', 'user.reg', 'userdef.reg']:
                        content = data.read()
                        assert all(x not in content for x in [b'/data/user/', b'/data/data/',
                                   b'session-179', b'PendingFileRenameOperations'])
                        data = io.BytesIO(content)
                    dst.addfile(member, data)
    (out/'manifest.json').write_text(json.dumps(records, separators=(',', ':')) + '\n')
    recipe = hashlib.sha256()
    for name in ['setup-container.sh', 'wine-runtime.properties']:
        recipe.update((a.recipe/name).read_bytes())
    meta = {'schemaVersion': 1, 'backend': 'rootfs_proot', 'hostAbi': 'aarch64',
            'prefixArch': 'wow64', 'winePackage': 'rootfs_hangover_11.16',
            'sourceComponentSha256': 'ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1',
            'baseRecipeSha256': recipe.hexdigest(), 'initializationRevision': 'pdb2-clean-delta-1',
            'runtimeFiles': json.loads(a.runtime_guards.read_text()),
            'expandedBytes': sum(r['bytes'] for r in records),
            'assets': {name: sha(out/name) for name in ['manifest.json', 'delta.tgz']},
            'provenance': {'kind': 'isolated-device-prototype', 'inputArchiveSha256': sha(a.delta),
                           'evidenceSha256': sha(a.evidence), 'graphicsAcceptance': 'pending-app-verification'}}
    (out/'bundle.json').write_text(json.dumps(meta, indent=2)+'\n')
    print(json.dumps({'bytes':sum(f.stat().st_size for f in out.iterdir()),
                      'expandedBytes':meta['expandedBytes'], 'assets':meta['assets']}, indent=2))


if __name__ == '__main__':
    main()
