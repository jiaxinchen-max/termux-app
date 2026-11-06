#!/usr/bin/env python3
"""Copy runtime assets out of App releases and verify every published SHA-256."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True)


def api(path):
    return json.loads(gh('api', path))


def releases(repo):
    return [r for page in json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases')) for r in page]


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--work-dir', required=True, type=Path)
    args = parser.parse_args()
    if args.source == args.target:
        parser.error('source and target must differ')
    args.work_dir.mkdir(parents=True, exist_ok=True)
    report = []
    for source in releases(args.source):
        assets = [a for a in source['assets']
                  if a['name'] not in {'termux-backup.tar.gz', 'termux-backup.tar.1.gz'}
                  and a['name'].endswith(('.deb', '.tar.xz', '.tar.gz', '.zip'))]
        if any(a['name'].startswith('gamepad_') for a in assets):
            assets += [a for a in source['assets'] if a['name'] == 'default.txt']
        if not assets:
            continue
        tag = 'legacy-termux-app-' + source['tag_name']
        folder = args.work_dir / tag
        folder.mkdir(exist_ok=True)
        print(f'{tag}: {len(assets)} assets', flush=True)

        def download(asset):
            path = folder / asset['name']
            if not path.exists():
                print('Downloading ' + asset['name'], flush=True)
                gh('release', 'download', source['tag_name'], '--repo', args.source,
                   '--pattern', asset['name'], '--dir', str(folder))
            if path.stat().st_size != asset['size']:
                raise ValueError('size mismatch: ' + asset['name'])
            digest = checksum(path)
            if asset.get('digest') and asset['digest'] != 'sha256:' + digest:
                raise ValueError('source SHA-256 mismatch: ' + asset['name'])
            return {'name': asset['name'], 'size': asset['size'], 'sha256': digest,
                    'sourceAssetId': asset['id']}

        with ThreadPoolExecutor(max_workers=3) as pool:
            verified = list(pool.map(download, assets))
        hashes = folder / 'SHA256SUMS'
        hashes.write_text(''.join(a['sha256'] + '  ' + a['name'] + '\n' for a in verified))
        notes = folder / 'release-notes.md'
        notes.write_text(f'Historical runtime artifacts copied from {args.source} release '
                         f'{source["tag_name"]}. This is an archive, not a recommended runtime version.\n\n'
                         'Original asset names and contents are preserved. See SHA256SUMS for verification.\n')
        target = next((r for r in releases(args.target) if r['tag_name'] == tag), None)
        if target is None:
            request = folder / 'release-request.json'
            request.write_text(json.dumps({'tag_name': tag, 'name':
                f'Legacy App runtime assets ({source["tag_name"]})', 'body': notes.read_text(),
                'draft': True, 'make_latest': 'false'}))
            target = json.loads(gh('api', '--method', 'POST', f'repos/{args.target}/releases',
                                   '--input', str(request)))
        present = {a['name']: a for a in target['assets']}
        expected = verified + [{'name': 'SHA256SUMS', 'size': hashes.stat().st_size, 'sha256': checksum(hashes)}]
        for item in expected:
            existing = present.get(item['name'])
            if existing is None:
                print('Uploading ' + item['name'], flush=True)
                gh('release', 'upload', tag, str(folder / item['name']), '--repo', args.target)
            elif existing.get('digest') != 'sha256:' + item['sha256'] or existing['size'] != item['size']:
                raise ValueError('existing target differs: ' + item['name'])
        target = api(f'repos/{args.target}/releases/{target["id"]}')
        uploaded = {a['name']: a for a in target['assets']}
        for item in expected:
            asset = uploaded[item['name']]
            if asset.get('digest') != 'sha256:' + item['sha256'] or asset['size'] != item['size']:
                raise ValueError('uploaded SHA-256 mismatch: ' + item['name'])
        gh('api', '--method', 'PATCH', f'repos/{args.target}/releases/{target["id"]}',
           '-F', 'draft=false', '-f', 'make_latest=false')
        report.append({'sourceTag': source['tag_name'], 'targetTag': tag, 'assets': verified})
        (args.work_dir / 'migration-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(f'Verified {tag}: {len(verified)} assets; source retained', flush=True)


if __name__ == '__main__':
    main()
