# SPDX-License-Identifier: MIT
"""Exercise installed host FAT/exFAT WebDAV from an existing musl/glibc PRoot.

Uses disposable disk images, not USB hardware. Only an exchange directory is
bound into the guest; the volume image is opened exclusively by the host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.parse import quote, unquote
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', required=True, type=Path)
    parser.add_argument('--work', required=True, type=Path)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    if (work / 'fat32.img').exists() or (work / 'exfat.img').exists():
        parser.error('use a fresh work directory; existing images are never overwritten')
    prefix = Path(os.environ['PREFIX'])
    sys.path.insert(0, str(prefix / 'share/termux-usb-bridge'))
    from filesystem import Filesystem
    exchange = work / 'exchange'
    exchange.mkdir(mode=0o700)
    root = args.rootfs.resolve()
    (root / 'tusb-storage-test').mkdir(exist_ok=True)
    guest_env = dict(os.environ)
    guest_env.pop('LD_PRELOAD', None)
    base = ['proot', '--link2symlink', '-0', '-r', str(root), '-b', '/dev',
            '-b', '/proc', '-b', '/sys', '-b', str(exchange) + ':/tusb-storage-test',
            '-w', '/', '/usr/bin/env', '-i', 'PATH=/usr/sbin:/usr/bin:/sbin:/bin',
            'HOME=/root']

    def guest(command):
        r = subprocess.run(base + command, env=guest_env, capture_output=True,
                           text=True, timeout=30)
        if r.returncode:
            raise RuntimeError(f'guest command failed ({r.returncode}): {r.stderr}')
        return r.stdout

    environment = guest(['/bin/sh', '-c',
                         'cat /etc/alpine-release; id; curl --version; ldd /usr/bin/curl'])
    (work / 'guest-environment.log').write_text(environment)
    print(environment, flush=True)
    payload = bytes((i * 17 + 3) & 255 for i in range(131123))
    (exchange / 'payload.bin').write_bytes(payload)
    seed = b'Host filesystem -> container read\n'
    report = {'backend': 'disposable FAT32/exFAT images; no physical USB transport',
              'guest': environment, 'payload_bytes': len(payload),
              'payload_sha256': hashlib.sha256(payload).hexdigest(), 'volumes': []}

    for kind in ('fat32', 'exfat'):
        image = work / (kind + '.img')
        subprocess.run(['tusb-files', 'create-image', str(image), '--mib', '64'] +
                       (['--exfat'] if kind == 'exfat' else []), check=True, timeout=20)
        fs = Filesystem(image=str(image), writable=True)
        try:
            fs.mkdir('/宿主目录')
            fs.write('/宿主目录/种子.txt', seed, truncate=True)
            initial = fs.volume_info()
        finally:
            fs.close()
        token = secrets.token_urlsafe(24)
        token_file = work / (kind + '.token')
        token_file.write_text(token + '\n')
        token_file.chmod(0o600)
        config = exchange / 'curl.conf'
        config.write_text(f'user = "tusb:{token}"\n')
        config.chmod(0o600)
        ready = work / (kind + '.ready.json')
        requests = []

        def start(writable):
            ready.unlink(missing_ok=True)
            log = (work / (kind + ('-rw.log' if writable else '-ro.log'))).open('w')
            command = ['tusb-files', '--image', str(image)]
            if writable:
                command.append('--read-write')
            command += ['serve', '--port', '0', '--token-file', str(token_file),
                        '--ready-file', str(ready)]
            child = subprocess.Popen(command, stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while not ready.exists():
                    if child.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError(f'{kind} file server failed to start')
                    time.sleep(.05)
                return child, log, json.loads(ready.read_text())
            except BaseException:
                stop(child, log)
                raise

        def stop(child, log):
            try:
                if child.poll() is None:
                    child.terminate()
                assert child.wait(timeout=10) == 0, child.returncode
            finally:
                if child.poll() is None:
                    child.kill()
                    child.wait()
                log.close()

        def request(endpoint, method, path, expected, *, auth=True, headers=(), upload=False):
            command = ['curl', '-q', '--silent', '--show-error', '--max-time', '15',
                       '--output', '/tusb-storage-test/response.bin',
                       '--write-out', '%{http_code}', '--request', method]
            if auth:
                command += ['--config', '/tusb-storage-test/curl.conf']
            for header in headers:
                command += ['--header', header]
            if upload:
                command += ['--data-binary', '@/tusb-storage-test/payload.bin']
            command.append(f'http://127.0.0.1:{endpoint["port"]}' + quote(path, safe='/'))
            status = int(guest(command).strip())
            requests.append({'method': method, 'path': path, 'authenticated': auth,
                             'writable': endpoint['writable'], 'status': status})
            assert status == expected, (method, path, status, expected)
            return (exchange / 'response.bin').read_bytes()

        server, log, endpoint = start(True)
        try:
            assert endpoint['writable']
            assert endpoint['type'] == ('FAT32' if kind == 'fat32' else 'exFAT')
            request(endpoint, 'GET', '/', 401, auth=False)
            assert request(endpoint, 'GET', '/宿主目录/种子.txt', 200) == seed
            xml = request(endpoint, 'PROPFIND', '/', 207, headers=['Depth: 1'])
            names = [unquote(x.text) for x in ET.fromstring(xml).findall('.//{DAV:}href')]
            assert '/宿主目录/' in names, names
            assert ET.fromstring(xml).find('.//{DAV:}quota-available-bytes') is not None
            request(endpoint, 'MKCOL', '/容器写入', 201)
            original = '/容器写入/原始-😀.bin'
            renamed = '/容器写入/改名-😀.bin'
            request(endpoint, 'PUT', original, 201, upload=True)
            assert request(endpoint, 'GET', original, 200) == payload
            assert request(endpoint, 'GET', original, 206,
                           headers=['Range: bytes=65529-65650']) == payload[65529:65651]
            request(endpoint, 'MOVE', original, 201,
                    headers=['Destination: ' + quote(renamed, safe='/')])
            request(endpoint, 'GET', original, 404)
            request(endpoint, 'COPY', renamed, 201,
                    headers=['Destination: /temporary-copy.bin'])
            assert request(endpoint, 'GET', '/temporary-copy.bin', 200) == payload
            request(endpoint, 'DELETE', '/temporary-copy.bin', 204)
            request(endpoint, 'GET', '/temporary-copy.bin', 404)
        finally:
            stop(server, log)

        # Reopen the image in another host instance, after the service flushes.
        fs = Filesystem(image=str(image))
        try:
            readback = fs.read(renamed, len(payload) + 1)
            assert readback == payload
            assert fs.read('/宿主目录/种子.txt', len(seed) + 1) == seed
            final = fs.volume_info()
            assert final['free'] < initial['free']
        finally:
            fs.close()
        server, log, readonly = start(False)
        try:
            assert not readonly['writable']
            assert request(readonly, 'GET', renamed, 200) == payload
            request(readonly, 'PUT', '/blocked.bin', 403, upload=True)
            request(readonly, 'GET', '/blocked.bin', 404)
        finally:
            stop(server, log)
        config.unlink()
        token_file.unlink()
        report['volumes'].append({'format': kind, 'status': 'PASS',
                                  'initial': initial, 'final': final,
                                  'readback_sha256': hashlib.sha256(readback).hexdigest(),
                                  'requests': requests})
        (work / 'result.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
        print(f'PASS {kind}: native PRoot curl -> host WebDAV; Unicode, 131123-byte '
              'read/write, Range, MOVE/COPY/DELETE, restart persistence, read-only and auth', flush=True)
    print('CONTAINER_STORAGE_PASS', flush=True)


if __name__ == '__main__':
    main()
