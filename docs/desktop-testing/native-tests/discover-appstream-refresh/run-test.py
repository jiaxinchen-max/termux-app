#!/usr/bin/python3
from pathlib import Path
import os,subprocess,tempfile,time,sys,json,hashlib
base=Path('/home/builder/desktop-fixes-20261030/discover');project=base/'rootfs/root/build/discover-fix4/backend-test'
if len(sys.argv)!=2 or sys.argv[1] not in ('baseline','fixed'):raise SystemExit('explicit baseline|fixed required')
variant=sys.argv[1];work=Path(tempfile.mkdtemp(prefix=variant+'-',dir=base/'tmp'));print('ISOLATED_INPUTS',work,flush=True)
for name in ['home','runtime','swcatalog/xml','cache-swcatalog/cache','app-info','metainfo','applications','appstream','usr-swcatalog','apt-lists','flatpak']:(work/name).mkdir(parents=True,exist_ok=True)
(work/'runtime').chmod(0o700)
for appname in ('backend-test-baseline','backend-test-fixed'):
 ratings=work/'home/.cache'/appname/'ratings/ratings';ratings.parent.mkdir(parents=True,exist_ok=True);ratings.write_text('{}')
localpkg=work/'localpkg/DEBIAN';localpkg.mkdir(parents=True);(localpkg/'control').write_text('Package: local-fixture\nVersion: 1\nArchitecture: arm64\nMaintainer: Fixture <fixture@example.invalid>\nDescription: Independent local package discovery fixture\n')
subprocess.run(['dpkg-deb','--build',str(localpkg.parent),str(work/'local-fixture.deb')],check=True,capture_output=True)
actual='unix:path='+str(work/'bus.sock');config=work/'dbus.conf';config.write_text('<busconfig><type>session</type><listen>'+actual+'</listen><auth>EXTERNAL</auth><policy context="default"><allow own="*"/><allow send_destination="*"/><allow receive_sender="*"/></policy></busconfig>')
bus=subprocess.Popen(['dbus-daemon','--nofork','--print-address=1','--config-file='+str(config)],stdout=subprocess.PIPE,text=True);assert bus.stdout.readline().strip().startswith(actual)
hostenv=os.environ.copy();hostenv['DBUS_SESSION_BUS_ADDRESS']=actual;serverlog=(work/'server.log').open('w');server=subprocess.Popen(['/usr/bin/python3',str(project/'fake-packagekit.py')],env=hostenv,stdout=serverlog,stderr=subprocess.STDOUT)
try:
 for i in range(100):
  if 'READY' in (work/'server.log').read_text():break
  if server.poll() is not None:raise RuntimeError((work/'server.log').read_text())
  time.sleep(.05)
 else:raise RuntimeError('fixture readiness timeout')
 env=os.environ.copy();env.update(LD_LIBRARY_PATH=str(base/'tools/root/usr/lib/x86_64-linux-gnu'),PROOT_TMP_DIR=str(base/'tmp'),PROOT_NO_SECCOMP='1')
 cmd=[str(base/'tools/root/usr/bin/proot'),'-r',str(base/'rootfs'),'-q',str(base/'tools/root/usr/bin/qemu-aarch64'),'-b','/dev','-b','/proc','-b','/sys','-b',str(work)+':/fixture']
 for src,dst in [('swcatalog','/var/lib/swcatalog'),('cache-swcatalog','/var/cache/swcatalog'),('app-info','/var/cache/app-info'),('metainfo','/usr/share/metainfo'),('applications','/usr/share/applications'),('appstream','/usr/share/appstream'),('usr-swcatalog','/usr/share/swcatalog'),('apt-lists','/var/lib/apt/lists'),('flatpak','/var/lib/flatpak')]:cmd+=['-b',str(work/src)+':'+dst]
 cmd+=['-w','/fixture','/usr/bin/env','-i','HOME=/fixture/home','LANG=C.UTF-8','PATH=/usr/bin:/bin','QT_QPA_PLATFORM=offscreen','XDG_RUNTIME_DIR=/fixture/runtime','XDG_DATA_DIRS=/usr/share:/usr/local/share','DBUS_SESSION_BUS_ADDRESS=unix:path=/fixture/bus.sock','DBUS_SYSTEM_BUS_ADDRESS=unix:path=/fixture/bus.sock','/root/build/discover-fix4/backend-test/build/backend-test-'+variant,'--independent-packagekit-fixture']
 result=subprocess.run(cmd,env=env,capture_output=True,text=True,timeout=180);out=result.stdout+result.stderr;(work/'result.log').write_text(out);print(out,flush=True)
 expected='BASELINE REPRODUCED: actual PackageKitBackend' if variant=='baseline' else 'PASS: actual PackageKitBackend refresh lifecycle and resource generation tests'
 assert result.returncode==0 and expected in out,(result.returncode,expected)
 (project/('last-'+variant+'.json')).write_text(json.dumps({'inputs':str(work),'variant':variant,'executable_sha256':hashlib.sha256((project/'build'/('backend-test-'+variant)).read_bytes()).hexdigest(),'exit':result.returncode,'expected':expected,'log_sha256':hashlib.sha256(out.encode()).hexdigest()},indent=2)+'\n')
finally:server.terminate();bus.terminate();server.wait();bus.wait();serverlog.close()
