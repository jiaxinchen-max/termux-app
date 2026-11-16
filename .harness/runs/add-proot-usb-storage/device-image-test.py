from pathlib import Path
import hashlib,importlib.util,json,os,secrets,subprocess,threading,runpy,fcntl
p=Path.home()/'.cache/proot-usb-check-20261116'
lock=(p/'image-test.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
print('IMAGE_DRIVER_STARTED',flush=True)
prefix=Path(os.environ['PREFIX']);alias='software-center-check-20261116'
host=p/'host/data/data/com.termux/files/usr'
os.environ['TUSB_FS_LIBRARY']=str(host/'lib/libtermux-usb-fs.so')
spec=importlib.util.spec_from_file_location('filesystem',host/'share/termux-usb-bridge/filesystem.py')
fsmod=importlib.util.module_from_spec(spec);spec.loader.exec_module(fsmod)
image=p/'test-exfat.img';image.unlink(missing_ok=True)
fs=fsmod.Filesystem();fs.check(fs.lib.tusb_fs_create_image(os.fsencode(image),64*1024*1024,True));fs.close()
expected=bytes(range(256))*128+b'USB image UTF-8 test'
fs=fsmod.Filesystem(image=str(image),writable=True)
fs.mkdir('/目录');fs.write('/目录/payload.bin',expected,truncate=True);fs.close()
before=hashlib.sha256(image.read_bytes()).hexdigest()
fs=fsmod.Filesystem(image=str(image),writable=False)
password=secrets.token_urlsafe(24)
token=p/'abcd.token';token.write_text(password);token.chmod(0o600)
server=fsmod.ThreadingHTTPServer(('127.0.0.1',0),fsmod.WebDAV)
server.filesystem,server.password=fs,password
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
uri='dav://tusb@127.0.0.1:'+str(server.server_port)+'/'
(p/'image-endpoint.json').write_text(json.dumps({'uri':uri}))
(p/'volumes.ini').write_text('[desktop]\npid='+str(os.getpid())+'\n[volume:0123456789abcdef]\nid=0123456789abcdef\nname=USB image test\ndevice=/dev/bus/usb/001/002\nuri='+uri+'\nmounted=true\n')
(p/'volumes.ini').chmod(0o600)
guest=r'''
from pathlib import Path
import json,os,subprocess
p=Path('/tmp/tusb-image-test');uri=json.loads((p/'image-endpoint.json').read_text())['uri']
helper=str(p/'guest/usr/local/libexec/tusb-gio-mount')
def run(*args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=45)
 if r.returncode: raise RuntimeError(str(args)+': '+r.stdout+r.stderr)
 return r.stdout
print('guest uid='+str(os.getuid()),flush=True)
run('gio-querymodules',str(p/'guest/usr/local/lib/termux-usb-bridge/gio/modules'))
run(helper,'mount',uri,str(p/'abcd.token'))
try:
 listing=run('gio','list',uri);assert '目录' in listing,listing
 out=p/'readback.bin';out.unlink(missing_ok=True)
 run('gio','copy',uri+'%E7%9B%AE%E5%BD%95/payload.bin',str(out))
 assert out.read_bytes()==bytes(range(256))*128+b'USB image UTF-8 test'
 print('PASS glibc GVfs authenticated mount, Unicode listing and binary readback',flush=True)
 denied=subprocess.run(['gio','copy',str(out),uri+'denied.bin'],capture_output=True,text=True,timeout=45)
 assert denied.returncode!=0,'read-only write unexpectedly succeeded'
 print('PASS read-only GVfs rejects write: '+denied.stderr.strip(),flush=True)
 volumes=run('gio','mount','-li');assert 'USB image test' in volumes,volumes
 print('PASS guest GIO module exposes removable volume',flush=True)
finally:
 run(helper,'unmount',uri)
print('PASS helper unmount',flush=True)
'''
(p/'guest-check.py').write_text(guest)
root=prefix/'var/lib/proot-distro/containers'/alias/'rootfs'
mask=root/'data/data/com.termux/files/usr';mask_mode=mask.stat().st_mode&0o777
runpy.run_path(str(p/'desktop_usb_storage.py'))['prepare_guest_mount'](root,Path('/tmp/tusb-image-test'))
cmd=[str(prefix/'bin/proot-distro'),'login',alias,'--user','termux','--bind',str(p)+':/tmp/tusb-image-test','--','env',
 'GIO_EXTRA_MODULES=/tmp/tusb-image-test/guest/usr/local/lib/termux-usb-bridge/gio/modules',
 'TERMUX_USB_DESKTOP_DIR=/tmp/tusb-image-test','dbus-run-session','--','python3','/tmp/tusb-image-test/guest-check.py']
try:
 result=subprocess.run(cmd,capture_output=True,text=True,timeout=200)
 print(result.stdout,flush=True);print(result.stderr,flush=True)
 result.check_returncode()
finally:
 server.shutdown();server.server_close();thread.join();fs.close();token.unlink(missing_ok=True)
assert before==hashlib.sha256(image.read_bytes()).hexdigest(),'disk changed'
assert mask_mode==0 and mask.stat().st_mode&0o777==0,'masked PREFIX changed'
print('PASS exFAT image SHA256 unchanged and masked PREFIX remains 000',flush=True)
(p/'image.ready').touch()
