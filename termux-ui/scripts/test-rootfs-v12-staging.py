#!/usr/bin/env python3
"""Run the real v12 shell publication transaction against isolated host fixtures."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import signal
import time
import unittest

ASSETS=Path(__file__).resolve().parents[1]/'src/main/assets/local-games'
spec=importlib.util.spec_from_file_location('image_fixture',Path(__file__).with_name('test-prebuilt-rootfs.py'))
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

def write(path,text,executable=False):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
    if executable:path.chmod(0o700)

class StagingTests(unittest.TestCase):
    def run_case(self,case):
        with tempfile.TemporaryDirectory() as td:
            files=Path(td)/'files';front=files/'front';runtime=front/'runtime';recipe=runtime/'setup/recipes/debian-v12';host=files/'usr';shared=host/'var/lib/proot-distro/games-shared-rootfs';scratch=host/'var/lib/proot-distro/containers/tmpl-build';cache=host/'var/lib/proot-distro/games-template-cache';identity='a'*64
            seed=fixture.ImageTests();seed.setUp()
            try:
                seed.build();component=front/'components/image';component.mkdir(parents=True);shutil.copy2(seed.archive,component/seed.archive.name);recipe.mkdir(parents=True);shutil.copy2(seed.descriptor,recipe/'image.json')
                for name in ('prebuilt_rootfs.py','relocate_rootfs_links.py'):shutil.copy2(ASSETS/name,recipe/name)
                # Needed executable on the image for the production publication precheck.
                source=seed.records[1];source['mode']=0o755
                seed.files['usr/bin/env']=b'env';seed.records.append({'path':'usr/bin/env','type':'file','mode':0o755,'bytes':3,'sha256':fixture.m.sha_bytes(b'env')});seed.build();shutil.copy2(seed.archive,component/seed.archive.name);shutil.copy2(seed.descriptor,recipe/'image.json')
                if case!='fresh':
                    shared.mkdir(parents=True)
                    fixture.m.restore(seed.archive,shared/'rootfs',seed.descriptor,identity,'zstd')
                    write(shared/'rootfs/old-environment','keep');write(cache/'games-rootfs-base.recipe',identity)
                write(front/'containers/existing/prefix/drive_c/game.save','save-data')
                if case=='unowned':write(scratch/'do-not-delete','user-data')
                if case=='interrupted':write(scratch/'.idap-image-owner','prebuilt-image-v1');write(scratch/'rootfs/partial','partial')
                if case=='bad-package':(component/seed.archive.name).write_bytes(b'bad')
                if case=='changed':write(cache/'games-rootfs-base.recipe','b'*64)
                for name in ('python','zstd'):
                    (host/'bin').mkdir(parents=True,exist_ok=True);(host/'bin'/name).symlink_to(sys.executable if name=='python' else shutil.which('zstd'))
                if case=='cancel':
                    (host/'bin/zstd').unlink()
                    write(host/'bin/zstd','#!'+sys.executable+'\nimport os,time\nopen('+repr(str(front/'decoder.pid'))+',"w").write(str(os.getpid()))\ntime.sleep(30)\n',True)
                for name in ('dbus-daemon','pulseaudio'):write(host/'bin'/name,'#!/bin/sh\nexit 0\n',True)
                write(host/'lib/libexpat.so.1','library')
                write(host/'bin/proot','''#!/bin/sh
if [ "$CASE" = locale-failure ]; then
 case "$*" in *games-shared-rootfs*) exit 1 ;; esac
fi
exit 0
''',True)
                write(host/'bin/mv','''#!/bin/sh
if [ "$CASE" = publish-failure ] && [ "$1" = "$SCRATCH" ]; then exit 1; fi
if [ "$CASE" = receipt-failure ]; then case "$1" in *.recipe.tmp) exit 1 ;; esac; fi
exec /bin/mv "$@"
''',True)
                write(host/'bin/pkg','#!/bin/sh\nexit 99\n',True)
                for name in ('rootfs_script_common.sh','proot_component_bindings.sh'):shutil.copy2(ASSETS/name,runtime/name)
                write(runtime/'warmup.sh','''resolve_rootfs_translator() { RUNTIME_TRANSLATOR=hangover; }
prepare_external_prefix_template() { :; }
warmup_rootfs_prefix() { :; }
require_external_prepared_prefix() { :; }
''')
                values={'taskId':'task','packageName':'debian-13-games-rootfs','version':'12','recipeSha256':identity,'containerId':'container','containerName':'container','buildContext':runtime/'setup/staging/task','recipeDirectory':recipe,'eventsPath':runtime/'setup/events/task.jsonl','logPath':runtime/'setup/logs/task.log','baseOnly':'false' if case in ('reuse','changed') else 'true','winePrefixDirectory':front/'containers/test/prefix','homeDirectory':front/'containers/test/home','winePackage':'rootfs_hangover_11.16','prefixWarmupScript':runtime/'warmup.sh','runtimeTranslator':'hangover','box64ComponentId':''}
                lines=['schemaVersion=4']+[k+'='+base64.b64encode(str(v).encode()).decode() for k,v in values.items()]+['componentCount=1','component.0.id='+base64.b64encode(fixture.m.IMAGE_ID.encode()).decode(),'component.0.directory='+base64.b64encode(str(component).encode()).decode(),'bindingCount=0']
                specpath=runtime/'setup/specs/task.setupspec';write(specpath,'\n'.join(lines)+'\n')
                command=['sh',str(ASSETS/'setup_rootfs_runtime.sh'),str(specpath)]
                env=dict(os.environ,PREFIX=str(host),CASE=case,SCRATCH=str(scratch))
                if case=='cancel':
                    proc=subprocess.Popen(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
                    try:
                        for _ in range(200):
                            if (front/'decoder.pid').exists():break
                            time.sleep(.02)
                        self.assertTrue((front/'decoder.pid').exists());pid=int((front/'decoder.pid').read_text());proc.send_signal(signal.SIGTERM);stdout,stderr=proc.communicate(timeout=5)
                        result=subprocess.CompletedProcess(command,proc.returncode,stdout,stderr)
                        with self.assertRaises(ProcessLookupError):os.kill(pid,0)
                        self.assertFalse((scratch/'rootfs').exists())
                    finally:
                        if proc.poll() is None:proc.kill();proc.communicate()
                else:
                    result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=20)
                log=(runtime/'setup/logs/task.log').read_text() if (runtime/'setup/logs/task.log').exists() else ''
                if case in ('fresh','explicit','interrupted','reuse'):
                    self.assertEqual(result.returncode,0,result.stderr[-1500:]+log);self.assertTrue((shared/'rootfs/etc/games-image-install.json').exists());self.assertEqual((cache/'games-rootfs-base.recipe').read_text().strip(),identity);self.assertFalse(shared.with_name(shared.name+'.previous').exists())
                    if case=='reuse':self.assertNotIn('Extracting',log)
                else:
                    self.assertNotEqual(result.returncode,0,result.stderr+log);self.assertEqual((shared/'rootfs/old-environment').read_text(),'keep');self.assertEqual((cache/'games-rootfs-base.recipe').read_text().strip(),'b'*64 if case=='changed' else identity)
                    if case=='unowned':self.assertEqual((scratch/'do-not-delete').read_text(),'user-data')
                self.assertEqual((front/'containers/existing/prefix/drive_c/game.save').read_text(),'save-data')
                self.assertNotIn('apt-get',log);self.assertNotIn('proot-distro install',log)
            finally:seed.doCleanups()

for case in ('fresh','explicit','reuse','changed','bad-package','interrupted','unowned','locale-failure','publish-failure','receipt-failure','cancel'):
    setattr(StagingTests,'test_'+case.replace('-','_'),lambda self,c=case:self.run_case(c))
if __name__=='__main__':unittest.main()
