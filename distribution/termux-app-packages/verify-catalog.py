#!/usr/bin/env python3
"""Verify exact public package payloads before publishing the catalog unchanged."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--catalog', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
data = json.loads(args.catalog.read_text())
ids = [p['id'] for p in data['packages']]
if len(ids) != len(set(ids)):
    raise ValueError('duplicate package ID')
for package in data['packages']:
    print('Verifying ' + package['id'], flush=True)
    with tempfile.TemporaryDirectory() as folder:
        archive = Path(folder) / 'payload'
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                        '--output', str(archive), package['url']], check=True)
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        if archive.stat().st_size != package['size'] or digest.hexdigest() != package['sha256']:
            raise ValueError('payload mismatch: ' + package['id'])
args.output.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.catalog, args.output)
