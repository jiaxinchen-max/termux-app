#!/usr/bin/env python3
"""Rebuild a candidate manifest from every actual, pinned Ubuntu/Wine source.

This is an offline production tool. It preserves the reviewed registry/tool delta,
reports every changed input, and leaves runtime acceptance pending. It never edits
an installed prefix or marks an untested candidate as accepted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source(root, name):
    require(not name.startswith('/') and all(x not in ('', '.', '..') for x in name.split('/')),
            'source_path')
    path = (root / name).resolve(strict=True)
    require(root in path.parents and path.is_file(), 'source_escape')
    return path.read_bytes()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'rootfs', 'recipe', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--wine-root', type=Path)
    p.add_argument('--wine-archive', type=Path)
    p.add_argument('--source-sha256', required=True)
    a = p.parse_args()
    require(not a.output.exists(), 'candidate_exists')
    require(len(a.source_sha256) == 64 and all(c in '0123456789abcdef' for c in a.source_sha256),
            'source_digest')
    b = json.loads((a.source / 'bundle.json').read_text())
    require(b['backend'] == 'rootfs_proot' and b['schemaVersion'] in (1, 2), 'bundle_kind')
    for name in ('manifest.json', 'delta.tgz'):
        require(sha((a.source / name).read_bytes()) == b['assets'][name], 'original_asset_digest')
    roots = {'rootfs': a.rootfs.resolve(strict=True)}
    pinned_files = {}
    if b['schemaVersion'] == 2:
        require(a.wine_root and a.wine_archive, 'wine_input_missing')
        roots['wine'] = a.wine_root.resolve(strict=True)
        require(sha(a.wine_archive.read_bytes()) == a.source_sha256, 'wine_archive_digest')
        with tarfile.open(a.wine_archive) as archive:
            for member in archive:
                name = member.name.removeprefix('./')
                start = roots['wine'].name + '/'
                if member.isfile() and name.startswith(start):
                    relative = name[len(start):]
                    require(relative not in pinned_files, 'duplicate_wine_member')
                    pinned_files[relative] = sha(archive.extractfile(member).read())
    records = json.loads((a.source / 'manifest.json').read_text())
    require(len({r['path'] for r in records}) == len(records), 'duplicate_record')
    with tarfile.open(a.source / 'delta.tgz') as archive:
        delta = {m.name: archive.extractfile(m).read() for m in archive if m.isfile()}
    changes, verified = [], []
    for r in records:
        old = r['sha256']
        if 'source' in r:
            key = r.get('sourceRoot', 'rootfs')
            require(key in roots, 'source_root')
            data = source(roots[key], r['source'])
            if key == 'wine':
                require(sha(data) == pinned_files.get(r['source']), 'wine_payload_mismatch')
            r.update(bytes=len(data), sha256=sha(data))
            verified.append({'path': r['path'], 'sourceRoot': key, 'source': r['source'],
                             'sha256': r['sha256']})
            if old != r['sha256']:
                changes.append({'path': r['path'], 'before': old, 'after': r['sha256']})
        else:
            require(r['path'] in delta and sha(delta[r['path']]) == old and
                    len(delta[r['path']]) == r['bytes'], 'delta_payload_mismatch')
    # A new portable component may add builtin files. Missing coverage requires a
    # fresh skeleton build, rather than silently omitting those files here.
    if pinned_files:
        supplied = {r['source'] for r in records if r.get('sourceRoot') == 'wine'}
        expected = {n for n in pinned_files if n.startswith(('lib/wine/x86_64-windows/',
                                                            'lib/wine/i386-windows/'))}
        require(expected <= supplied, 'new_component_files_require_fresh_skeleton')
    for key, rootkey in (('runtimeFiles', 'rootfs'), ('wineRuntimeFiles', 'wine')):
        for name in b.get(key, {}):
            value = sha(source(roots[rootkey], name))
            if rootkey == 'wine':
                require(value == pinned_files.get(name), 'wine_guard_mismatch')
            b[key][name] = value
    a.output.mkdir(parents=True)
    (a.output / 'manifest.json').write_text(json.dumps(records, separators=(',', ':')) + '\n')
    shutil.copyfile(a.source / 'delta.tgz', a.output / 'delta.tgz')
    proof = dict(schemaVersion=1, state='SourceVerifiedRuntimePending',
                 originalBundleSha256=sha((a.source / 'bundle.json').read_bytes()),
                 originalSourceSha256=b['sourceComponentSha256'],
                 sourceComponentSha256=a.source_sha256, verifiedSources=verified,
                 changedSources=changes, deltaSha256=sha((a.output / 'delta.tgz').read_bytes()))
    (a.output / 'source-verification.json').write_text(json.dumps(proof, indent=2) + '\n')
    b.update(sourceComponentSha256=a.source_sha256,
             baseRecipeSha256=sha(b''.join((a.recipe / n).read_bytes() for n in
                                         ('setup-container.sh', 'wine-runtime.properties'))),
             expandedBytes=sum(r['bytes'] for r in records),
             initializationRevision=b['initializationRevision'] + '-ubuntu2604-1',
             assets={n: sha((a.output / n).read_bytes()) for n in ('manifest.json', 'delta.tgz')},
             provenance=dict(kind='ubuntu2604-all-source-files-verified',
                             original=b.get('provenance', {}),
                             sourceVerificationSha256=sha((a.output / 'source-verification.json').read_bytes()),
                             targetAcceptance='pending'))
    (a.output / 'bundle.json').write_text(json.dumps(b, indent=2) + '\n')
    print(json.dumps({'records': len(records), 'verifiedSources': len(verified),
                      'changedSources': len(changes), 'expandedBytes': b['expandedBytes']}))


if __name__ == '__main__':
    main()
