#!/usr/bin/env python3
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import signal
import time
import unittest
from unittest import mock

HELPER = Path(__file__).resolve().parents[1] / 'src/main/assets/local-games/prebuilt_rootfs.py'
spec = importlib.util.spec_from_file_location('image', HELPER)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ImageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name); self.root = self.base / 'restored'; self.archive = self.base / 'test.tar.zst'; self.descriptor = self.base / 'image.json'; self.recipe = 'a' * 64
        self.files = {'etc/games-runtime.properties': b'layout=external-components-v1\n', 'usr/bin/wine': b'wine', '.l2s/one': b'data'}
        self.records = [{'path': n, 'type': 'file', 'mode': 0o644, 'bytes': len(v), 'sha256': m.sha_bytes(v)} for n, v in self.files.items()]
        self.links = {'bin': '/usr/bin', 'usr/bin/linked': '../../.l2s/one'}
        self.records += [{'path': n, 'type': 'symlink', 'mode': 0o777, 'link': v} for n, v in self.links.items()]

    def build(self, records=None, members=None, trailing=b'', decoder_data=None):
        records = self.records if records is None else records
        manifest = m.raw_json({'schemaVersion': 1, 'imageId': m.IMAGE_ID, 'sourceComponentSha256': m.SOURCE_SHA, 'entries': records, 'protectedFiles': {'usr/bin/wine': m.sha_bytes(b'wine')}, 'runtimeFiles': {'usr/bin/wine': m.sha_bytes(b'wine')}})
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode='w', format=tarfile.PAX_FORMAT) as tar:
            info = tarfile.TarInfo(m.MANIFEST); info.size = len(manifest); info.mode = 0o644; tar.addfile(info, io.BytesIO(manifest))
            for r in records if members is None else members:
                t = tarfile.TarInfo(r['path']); t.mode = r['mode']
                if r['type'] == 'symlink': t.type = tarfile.SYMTYPE; t.linkname = r['link']; tar.addfile(t)
                elif r['type'] == 'directory': t.type = tarfile.DIRTYPE; tar.addfile(t)
                else:
                    data = r.get('payload', self.files.get(r['path'], b'data')); t.size = len(data); tar.addfile(t, io.BytesIO(data))
        raw = stream.getvalue() + trailing if decoder_data is None else decoder_data
        with self.archive.open('wb') as dest:
            subprocess.run(['zstd', '-q', '-c'], input=raw, stdout=dest, check=True)
        d = {'schemaVersion': 1, 'imageId': m.IMAGE_ID, 'sourceComponentSha256': m.SOURCE_SHA, 'file': self.archive.name, 'manifestSha256': m.sha_bytes(manifest), 'expandedBytes': sum(r.get('bytes', 0) for r in records), 'entryCount': len(records), 'bytes': self.archive.stat().st_size, 'sha256': m.digest(self.archive)}
        self.descriptor.write_bytes(m.raw_json(d))
        return d

    def restore(self): return m.restore(self.archive, self.root, self.descriptor, self.recipe, 'zstd')
    def rejected(self):
        with self.assertRaises((ValueError, OSError, tarfile.TarError)): self.restore()
        self.assertFalse(self.root.exists())

    def test_roundtrip_absolute_guest_and_l2s_links(self):
        self.build(); self.restore(); self.assertEqual(m.guest(self.root, 'bin/linked').read_bytes(), b'data'); self.assertEqual(m.verify(self.root, self.descriptor, self.recipe, True)['state'], 'Verified')
    def test_digest_mismatch_before_writes(self):
        self.build(); self.archive.write_bytes(b'bad'); self.rejected()
    def test_wrong_source(self):
        self.build(); d=json.loads(self.descriptor.read_text()); d['sourceComponentSha256']='0'*64; self.descriptor.write_bytes(m.raw_json(d)); self.rejected()
    def test_path_escape(self):
        for name in ('../outside', '/tmp/outside', 'usr/../../outside'):
            with self.subTest(name=name):
                r=dict(self.records[0], path=name); self.build(records=[r]); self.rejected()
    def test_link_write_collision(self):
        outside = self.base / 'outside'; outside.mkdir(); self.files['redirect/payload']=b'data'
        r = [{'path':'redirect','type':'symlink','mode':0o777,'link':str(outside)}, {'path':'redirect/payload','type':'file','mode':0o644,'bytes':4,'sha256':m.sha_bytes(b'data')}]
        self.build(records=r); self.rejected(); self.assertFalse((outside/'payload').exists())
    def test_guest_relative_escape_rejected(self):
        self.build(records=self.records+[{'path':'escape','type':'symlink','mode':0o777,'link':'../outside'}]); self.rejected()
    def test_link_cycle_rejected(self):
        self.build(records=self.records+[{'path':'cycle','type':'symlink','mode':0o777,'link':'cycle'}]); self.rejected()
    def test_duplicate_member(self): self.build(members=self.records+[self.records[0]]); self.rejected()
    def test_missing_member(self): self.build(members=self.records[:-1]); self.rejected()
    def test_unexpected_member(self): self.build(members=self.records+[dict(self.records[0],path='extra')]); self.rejected()
    def test_wrong_file_data(self): self.build(members=[dict(self.records[0],payload=b'X'*len(self.files[self.records[0]['path']]))]+self.records[1:]); self.rejected()
    def test_decoder_failed(self):
        self.build(); self.archive.write_bytes(self.archive.read_bytes()[:-4]);d=json.loads(self.descriptor.read_text());d.update(bytes=self.archive.stat().st_size,sha256=m.digest(self.archive));self.descriptor.write_bytes(m.raw_json(d));self.rejected()
    def test_trailing_nonzero_data(self): self.build(trailing=b'hostile'); self.rejected()
    def test_expanded_budget(self):
        self.build(); d=json.loads(self.descriptor.read_text());d['expandedBytes']=m.MAX_BYTES+1;self.descriptor.write_bytes(m.raw_json(d));self.rejected()
    def test_insufficient_storage(self):
        self.build()
        with mock.patch.object(m.shutil,'disk_usage',return_value=shutil._ntuple_diskusage(0,0,0)):self.rejected()
    def test_prefix_space_reserved_before_root_is_created(self):
        self.build()
        from unittest.mock import patch
        from collections import namedtuple
        disk = namedtuple('disk', 'total used free')
        d = json.loads(self.descriptor.read_text())
        free = d['expandedBytes'] + d['entryCount'] * 8192 + 128 * 1024**2
        with patch.object(m.shutil, 'disk_usage', return_value=disk(free, 0, free)):
            with self.assertRaisesRegex(ValueError, 'image_insufficient_storage'):
                m.restore(self.archive, self.root, self.descriptor, self.recipe, 'zstd', 1024**3)
        self.assertFalse(self.root.exists())

    def test_existing_destination_preserved(self):
        self.build();self.root.mkdir();(self.root/'old').write_bytes(b'old')
        with self.assertRaises(ValueError):self.restore()
        self.assertEqual((self.root/'old').read_bytes(),b'old')
    def test_recipe_and_installed_source_tampering(self):
        self.build();self.restore()
        with self.assertRaises(ValueError):m.verify(self.root,self.descriptor,'b'*64)
        (self.root/'usr/bin/wine').write_bytes(b'changed')
        with self.assertRaises(ValueError):m.verify(self.root,self.descriptor,self.recipe)
    def test_pack_reproducible_and_cleans_machine_data(self):
        source=self.base/'source';source.mkdir()
        for r in self.records:
            p=source/r['path'];p.parent.mkdir(parents=True,exist_ok=True)
            if r['type']=='file':p.write_bytes(self.files[r['path']]);p.chmod(r['mode'])
            else:p.symlink_to(r['link'])
        (source/'etc/machine-id').write_bytes(b'secret-machine-id')
        (source/'root/.ssh').mkdir(parents=True);(source/'var/log').mkdir(parents=True);(source/'var/log/private').write_bytes(b'log')
        (source/'etc/games-rootfs-trim.json').write_text(json.dumps({'state':'Verified','batches':[1,2,3,4,5]}))
        assets=self.base/'assets';assets.mkdir();(assets/'manifest.json').write_bytes(b'[]');(assets/'bundle.json').write_bytes(m.raw_json({'sourceComponentSha256':m.SOURCE_SHA,'assets':{'manifest.json':m.sha_bytes(b'[]')},'runtimeFiles':{'usr/bin/wine':m.sha_bytes(b'wine')}}))
        out=self.base/'one.tar.zst';desc=self.base/'one.json';d=m.pack(source,out,desc,assets,'zstd');out2=self.base/'two.tar.zst';desc2=self.base/'two.json';d2=m.pack(source,out2,desc2,assets,'zstd');self.assertEqual(d['sha256'],d2['sha256'])
        m.restore(out,self.root,desc,self.recipe,'zstd');self.assertEqual((self.root/'etc/machine-id').read_bytes(),b'');self.assertFalse((self.root/'var/log/private').exists());self.assertEqual((source/'etc/machine-id').read_bytes(),b'secret-machine-id')
    def test_cancel_reaps_decoder_and_removes_owned_tree(self):
        self.build(); decoder=self.base/'slow-zstd'
        decoder.write_text('#!'+__import__('sys').executable+'\nimport os,time\nopen('+repr(str(self.base/'decoder.pid'))+',"w").write(str(os.getpid()))\ntime.sleep(30)\n');decoder.chmod(0o700)
        proc=subprocess.Popen([__import__('sys').executable,str(HELPER),'restore','--root',str(self.root),'--descriptor',str(self.descriptor),'--recipe',self.recipe,'--archive',str(self.archive),'--zstd',str(decoder)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        try:
            for _ in range(100):
                if (self.base/'decoder.pid').exists():break
                time.sleep(.02)
            self.assertTrue((self.base/'decoder.pid').exists());pid=int((self.base/'decoder.pid').read_text());proc.send_signal(signal.SIGTERM);proc.communicate(timeout=5);self.assertNotEqual(proc.returncode,0);self.assertFalse(self.root.exists())
            with self.assertRaises(ProcessLookupError):os.kill(pid,0)
        finally:
            if proc.poll() is None:proc.kill();proc.communicate()
    def test_large_trailing_padding_rejected(self):self.build(trailing=b'\x00'*65536);self.rejected()
    def test_reserved_control_member_rejected(self):
        r=dict(self.records[0],path=m.RECEIPT);self.build(records=[r]);self.rejected()
    def test_privileged_mode_rejected(self):
        self.build(records=[dict(self.records[0],mode=0o4755)]);self.rejected()

    def test_private_home_rejected(self):
        source=self.base/'source';(source/'root').mkdir(parents=True);(source/'root/secret').write_bytes(b'secret')
        with self.assertRaises(ValueError):m.inventory(source)

if __name__=='__main__':unittest.main()
