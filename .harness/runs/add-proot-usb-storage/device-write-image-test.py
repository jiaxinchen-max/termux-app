from pathlib import Path
import hashlib,importlib.util,json,os,secrets,subprocess,threading,runpy,fcntl
p=Path.home()/'.cache/proot-usb-check-20261116'
lock=(p/'image-test.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
print('WRITE_IMAGE_DRIVER_STARTED',flush=True)
prefix=Path(os.environ['PREFIX']);alias='software-center-check-20261116'
host=p/'host/data/data/com.termux/files/usr'
os.environ['TUSB_FS_LIBRARY']=str(host/'lib/libtermux-usb-fs.so')
spec=importlib.util.spec_from_file_location('filesystem',host/'share/termux-usb-bridge/filesystem.py')
fsmod=importlib.util.module_from_spec(spec);spec.loader.exec_module(fsmod)
image=p/'test-write-exfat.img';image.unlink(missing_ok=True)
fs=fsmod.Filesystem();fs.check(fs.lib.tusb_fs_create_image(os.fsencode(image),64*1024*1024,True));fs.close()
expected=bytes(range(256))*128+b'USB image UTF-8 write test'
root=prefix/'var/lib/proot-distro/containers'/alias/'rootfs'
mask=root/'data/data/com.termux/files/usr';assert mask.stat().st_mode&0o777==0
runpy.run_path(str(p/'desktop_usb_storage.py'))['prepare_guest_mount'](root,Path('/tmp/tusb-image-test'))
guest=r'''
from pathlib import Path
import json,os,subprocess,sys
p=Path('/tmp/tusb-image-test');uri=json.loads((p/'image-endpoint.json').read_text())['uri']
helper=str(p/'guest/usr/local/libexec/tusb-gio-mount');writable=sys.argv[1]=='rw'
def run(*args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=45)
 if r.returncode: raise RuntimeError(str(args)+': '+r.stdout+r.stderr)
 return r.stdout
print('guest uid='+str(os.getuid())+' mode='+sys.argv[1],flush=True)
expected=bytes(range(256))*128+b'USB image UTF-8 write test'
local=p/'payload-write.bin';local.write_bytes(expected)
back=p/'readback-write.bin';back.unlink(missing_ok=True)
run('gio-querymodules',str(p/'guest/usr/local/lib/termux-usb-bridge/gio/modules'))
run(helper,'mount',uri,str(p/'abcd.token'))
try:
 if writable:
  run('gio','mkdir',uri+'%E7%9B%AE%E5%BD%95')
  run('gio','copy',str(local),uri+'%E7%9B%AE%E5%BD%95/payload.bin')
  run('gio','copy',uri+'%E7%9B%AE%E5%BD%95/payload.bin',str(back))
  assert back.read_bytes()==expected
  run('gio','move',uri+'%E7%9B%AE%E5%BD%95/payload.bin',uri+'renamed.bin')
  run('gio','remove',uri+'%E7%9B%AE%E5%BD%95')
  run('gio','copy',uri+'renamed.bin',uri+'copy.bin')
  run('gio','remove',uri+'copy.bin')
  print('PASS actual glibc GVfs mkdir, binary PUT/readback, MOVE, COPY, DELETE',flush=True)
 else:
  run('gio','copy',uri+'renamed.bin',str(back));assert back.read_bytes()==expected
  denied=subprocess.run(['gio','copy',str(local),uri+'denied.bin'],capture_output=True,text=True,timeout=45)
  assert denied.returncode!=0,'read-only write unexpectedly succeeded'
  print('PASS reopened exFAT via glibc GVfs durable binary readback and read-only PUT rejection',flush=True)
 volumes=run('gio','mount','-li');assert 'USB write image test' in volumes
finally:
 run(helper,'unmount',uri)
 local.unlink(missing_ok=True);back.unlink(missing_ok=True)
print('PASS helper unmount '+sys.argv[1],flush=True)
'''
(p/'guest-write-check.py').write_text(guest)
try:
 for writable in (True,False):
  before=hashlib.sha256(image.read_bytes()).hexdigest()
  fs=fsmod.Filesystem(image=str(image),writable=writable)
  if not writable: assert fs.read('/renamed.bin',len(expected))==expected
  password=secrets.token_urlsafe(24)
  token=p/'abcd.token';token.write_text(password);token.chmod(0o600)
  server=fsmod.ThreadingHTTPServer(('127.0.0.1',0),fsmod.WebDAV)
  server.daemon_threads=False
  server.filesystem,server.password=fs,password
  thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  uri='dav://tusb@127.0.0.1:'+str(server.server_port)+'/'
  (p/'image-endpoint.json').write_text(json.dumps({'uri':uri}))
  (p/'volumes.ini').write_text('[desktop]\npid='+str(os.getpid())+'\n[volume:0123456789abcdef]\nid=0123456789abcdef\nname=USB write image test\ndevice=/dev/bus/usb/001/002\nuri='+uri+'\nmounted=true\nwritable='+str(writable).lower()+'\n')
  (p/'volumes.ini').chmod(0o600)
  cmd=[str(prefix/'bin/proot-distro'),'login',alias,'--user','termux','--bind',str(p)+':/tmp/tusb-image-test','--','env',
   'GIO_EXTRA_MODULES=/tmp/tusb-image-test/guest/usr/local/lib/termux-usb-bridge/gio/modules',
   'TERMUX_USB_DESKTOP_DIR=/tmp/tusb-image-test','dbus-run-session','--','python3','/tmp/tusb-image-test/guest-write-check.py','rw' if writable else 'ro']
  try:
   result=subprocess.run(cmd,capture_output=True,text=True,timeout=240,
    env={k:v for k,v in os.environ.items() if k not in ('LD_LIBRARY_PATH','LD_PRELOAD')})
   print(result.stdout,flush=True);print(result.stderr,flush=True);result.check_returncode()
  finally:
   server.shutdown();server.server_close();thread.join();fs.close();token.unlink(missing_ok=True)
  after=hashlib.sha256(image.read_bytes()).hexdigest()
  assert (before!=after) if writable else (before==after)
  assert mask.stat().st_mode&0o777==0,'masked PREFIX changed'
  print('PASS clean fs close, image '+('changed for write' if writable else 'unchanged in read-only'),flush=True)
 print('WRITE_IMAGE_PASS',flush=True)
 (p/'write-image.ready').touch()
finally:
 image.unlink(missing_ok=True)
 for name in ('abcd.token','image-endpoint.json','volumes.ini','payload-write.bin','readback-write.bin'):
  (p/name).unlink(missing_ok=True)
