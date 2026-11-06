#!/bin/sh
# Local process/socket fixtures only. No Android build, device, package install or network.
set -eu
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
python3 - "$PROJECT_ROOT" <<'PY'
import json, os, pathlib, shutil, signal, subprocess, sys, tempfile, time
root = pathlib.Path(sys.argv[1])
assets = root/'termux-ui/src/main/assets/local-games'
mock = '#!'+sys.executable+'\n'+r'''
import html,json,os,pathlib,re,signal,socket,subprocess,sys,time
base=pathlib.Path(os.environ['FIXTURE']); prefix=pathlib.Path(os.environ['PREFIX'])
name=pathlib.Path(sys.argv[0]).name; args=sys.argv[1:]
record={k:os.environ.get(k) for k in ('XLORIE_GL_MODE','XLORIE_GLX','TERMUX_RENDER_AHB_RECEIVE','LIBGL_ALWAYS_SOFTWARE','GALLIUM_DRIVER','MESA_LOADER_DRIVER_OVERRIDE','VK_DRIVER_FILES','VK_ICD_FILENAMES','LIBGL_KOPPER_DRI2','MESA_VK_DEVICE_SELECT','DRI_PRIME','COGL_DRIVER','CLUTTER_DRIVER','HOME','XCURSOR_PATH','XCURSOR_THEME','XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME','XDG_RUNTIME_DIR','DBUS_SESSION_BUS_ADDRESS','TERMUX_USB_DESKTOP_DIR','PLASMA_LOG','PULSE_SERVER')}
record['KWIN_ENV']={key:value for key,value in os.environ.items() if key.startswith('KWIN_')}
with (base/'calls').open('a') as out: out.write(json.dumps([name,args,record])+'\n')
def serve(path):
 for dst,src in json.loads(os.environ.get('FIXTURE_MAPS','[]')): path=path.replace(dst,src)
 s=socket.socket(socket.AF_UNIX); s.bind(str(path)); return s
def wait():
 while True: time.sleep(.1)
if name in ('proot','proot-distro'):
 maps=[]
 if name=='proot': maps.append(('/root',str(pathlib.Path(args[args.index('-r')+1])/'root')))
 for i,arg in enumerate(args):
  if arg in ('-b','--bind'):
   value=args[i+1]; src,sep,dst=value.partition(':'); maps.append((dst if sep else src,src))
 def translate(value):
  for dst,src in sorted(maps,key=lambda item:len(item[0]),reverse=True): value=value.replace(dst,src)
  return value
 index=args.index('/usr/bin/env')+2 if name=='proot' else args.index('--')+2; env=dict(os.environ)
 while index<len(args) and args[index]=='-u': env.pop(args[index+1],None); index+=2
 while index<len(args) and '=' in args[index]:
  key,value=args[index].split('=',1); env[key]=translate(value); index+=1
 env['PATH']=str(prefix/'bin')+':/usr/bin:/bin'
 env['FIXTURE_MAPS']=json.dumps(sorted(maps,key=lambda item:len(item[0]),reverse=True))
 command=[translate(value) for value in args[index:]]
 if command[0]=='/bin/sh' and len(command)>1 and pathlib.Path(command[1]).name=='session.sh':
  original=pathlib.Path(command[1]); translated=original.with_name('fixture-session.sh')
  translated.write_text(translate(original.read_text())); command[1]=str(translated)
 os.execvpe(command[0],command,env)
elif name=='dbus-daemon':
 config=next(v.split('=',1)[1] for v in args if v.startswith('--config-file='))
 path=html.unescape(re.search('<listen>unix:path=(.*?)</listen>',pathlib.Path(config).read_text())[1])
 sock=serve(path); wait()
elif name=='dbus-run-session':
 config=next(v.split('=',1)[1] for v in args if v.startswith('--config-file='))
 path=html.unescape(re.search('<listen>unix:path=(.*?)</listen>',pathlib.Path(config).read_text())[1])
 sock=serve(path); env=dict(os.environ,DBUS_SESSION_BUS_ADDRESS='unix:path='+path)
 command=args[args.index('--')+1:]
 if command[0].endswith('/sh') and len(command)>1 and pathlib.Path(command[1]).name=='session.sh':
  original=pathlib.Path(command[1]); translated=original.with_name('fixture-session.sh'); content=original.read_text()
  for dst,src in json.loads(os.environ.get('FIXTURE_MAPS','[]')): content=content.replace(dst,src)
  translated.write_text(content); command[1]=str(translated)
 child=subprocess.Popen(command,env=env)
 sys.exit(child.wait())
elif name in ('gsettings','dbus-update-activation-environment'): sys.exit(0)
elif name=='dbus-send':
 sys.exit(0 if (base/'shell-authorized').exists() and (base/'shell-running').exists() else 1)
elif name=='glxinfo':
 print('Accelerated: '+os.environ.get('FIXTURE_ACCELERATED','yes')); print('OpenGL renderer string: '+os.environ.get('FIXTURE_RENDERER','llvmpipe (LLVM fixture)')); sys.exit(int(os.environ.get('FIXTURE_RENDERER_EXIT','0')))
elif name=='xdpyinfo': sys.exit(0 if (prefix/'tmp/.X11-unix/X0').is_socket() else 1)
elif name=='Xlorie':
 if os.environ.get('KWIN_ANDROID_GL_MODE') not in (None,os.environ.get('XLORIE_GL_MODE')):
  print('XLORIE_GL_MODE conflicts with KWIN_ANDROID_GL_MODE'); sys.exit(91)
 sock=serve(prefix/'tmp/.X11-unix/X0'); wait()
elif name in ('gnome-shell','gnome-session','xfce4-session'):
 if name=='gnome-shell' and '--wayland' in args: sock=serve(pathlib.Path(os.environ['XDG_RUNTIME_DIR'])/os.environ['WAYLAND_DISPLAY'])
 if name!='xfce4-session': (pathlib.Path(os.environ['HOME'])/'user-created.txt').write_text('user data')
 (base/'shell-running').write_text(str(os.getpid())); wait()
else: sys.exit(90)
'''
def wait_for(predicate, process=None):
 deadline=time.monotonic()+12
 while not predicate():
  if process is not None and process.poll() is not None: raise AssertionError('launcher exited '+str(process.returncode))
  if time.monotonic()>deadline: raise AssertionError('fixture timed out')
  time.sleep(.05)
with tempfile.TemporaryDirectory(prefix='gn-',dir='/tmp') as temporary:
 base=pathlib.Path(temporary)
 count=0
 def case(name,mode='native',backend='android',gl='system',hz=60,codename='trixie',success=False,usb=False,cleanup_failure=False,display_gl='by-backend',display_buffer='by-backend',probe_renderer='llvmpipe (LLVM fixture)',probe_code=0,expected_code=2,startup='direct',guest_icd='valid',host_icd='valid',probe_accelerated='yes'):
  global count
  count+=1; folder=base/name; prefix=folder/'p'; (prefix/'bin').mkdir(parents=True); (prefix/'tmp').mkdir(); (folder/'calls').touch()
  for command in ('proot','dbus-daemon','dbus-run-session','gsettings','dbus-update-activation-environment','dbus-send','xdpyinfo','glxinfo','Xlorie','gnome-shell','gnome-session'):
   target=prefix/'bin'/command; target.write_text(mock); target.chmod(0o700)
  (prefix/'bin/sh').symlink_to('/bin/sh')
  (prefix/'bin/python3').symlink_to(sys.executable)
  def turnip(root,kind,library):
   icd=root/'share/vulkan/icd.d/freedreno_icd.aarch64.json'
   if kind=='missing': return
   icd.parent.mkdir(parents=True)
   selected=library if kind!='wrong-driver' else library.replace('freedreno','lvp')
   icd.write_text(json.dumps({'ICD':{'library_path':selected}}) if kind!='malformed' else 'invalid JSON')
   if kind!='missing-library':
    target=root/'lib/libvulkan_freedreno.so' if root==prefix else root/'lib/aarch64-linux-gnu/libvulkan_freedreno.so'
    target.parent.mkdir(parents=True,exist_ok=True); target.touch()
  turnip(prefix,host_icd,str(prefix/'lib/libvulkan_freedreno.so'))
  launcher=folder/'desktop_gnome_launch.py'; shutil.copy(assets/launcher.name,launcher)
  services=prefix/'share/dbus-1/services'; services.mkdir(parents=True)
  for service in ('ca.desrt.dconf','org.gtk.vfs.Daemon','org.gnome.Nautilus'): (services/(service+'.service')).write_text('[D-BUS Service]\nName='+service+'\nExec=/fixture\n')
  if usb:
   modules=prefix/'lib/gio/modules'; modules.mkdir(parents=True); (modules/'libtusb-volume-monitor.so').touch()
   helper=folder/'desktop_usb_storage.py'
   helper.write_text('#!'+sys.executable+'\n'+r'''
import json,os,pathlib,signal,socket,sys,time
base=pathlib.Path(os.environ['FIXTURE']); (base/'storage-args').write_text(json.dumps(sys.argv[1:])); runtime=pathlib.Path(sys.argv[sys.argv.index('--runtime')+1]); runtime.mkdir(parents=True)
if '--gio-command-json' in sys.argv: (base/'gio-command').write_text(sys.argv[sys.argv.index('--gio-command-json')+1])
sock=socket.socket(socket.AF_UNIX); sock.bind(str(runtime/'control.sock'))
def stop(signum,frame):
 pid=int((base/'shell-running').read_text()); os.kill(pid,0)
 (base/'storage-cleanup').write_text('session-alive')
 (base/'storage-bus').write_text(os.environ['DBUS_SESSION_BUS_ADDRESS'])
 time.sleep(.2)
 sys.exit(1 if (base/'cleanup-failure').exists() else 0)
signal.signal(signal.SIGTERM,stop)
while True: time.sleep(.1)
''')
   if cleanup_failure: (folder/'cleanup-failure').touch()
  render=folder/'render'; import socket
  sock=socket.socket(socket.AF_UNIX); sock.bind(str(render))
  termux_home=folder/'termux-home'; termux_home.mkdir()
  ready=folder/'ready'; work=folder/'work'; env=dict(os.environ,HOME=str(termux_home),LIBGL_ALWAYS_SOFTWARE='1',GALLIUM_DRIVER='softpipe',MESA_LOADER_DRIVER_OVERRIDE='swrast',VK_DRIVER_FILES='/stale/lvp.json',VK_ICD_FILENAMES='/stale/lvp.json',LIBGL_KOPPER_DRI2='true',MESA_VK_DEVICE_SELECT='wrong',DRI_PRIME='wrong',KWIN_ANDROID_GL_MODE='llvmpipe',KWIN_ANDROID_RUNTIME_ROOT='/stale-kwin-runtime',KWIN_ANDROID_REFRESH_RATE='120',KWIN_UNRELATED_TEST='must-not-leak',PLASMA_LOG='/stale-plasma.log',FIXTURE_ACCELERATED=probe_accelerated,FIXTURE_RENDERER=probe_renderer,FIXTURE_RENDERER_EXIT=str(probe_code),XCURSOR_PATH='/inherited-wrong-path',XCURSOR_THEME='WrongTheme',PREFIX=str(prefix),PATH=str(prefix/'bin')+':/usr/bin:/bin',FIXTURE=str(folder))
  env.update(COGL_DRIVER='gles2',CLUTTER_DRIVER='gles2,any',PULSE_SERVER='tcp:127.0.0.1:4713')
  command=[sys.executable,'-I','-B',str(launcher),'--desktop-id',name,'--ready-file',str(ready),'--run-mode',mode,'--backend',backend,'--gl-mode',gl,'--size','800x600','--refresh-rate',str(hz),'--render-socket',str(render),'--display',':0','--wayland-display','wayland-gnome','--usb-storage','read-only' if usb else 'off','--service-policy','core','--work',str(work)]
  if startup is not None: command+=['--session-startup',startup]
  if display_gl=='by-backend': display_gl=('zink' if gl=='zink' else 'system') if backend=='x11' else None
  if display_buffer=='by-backend': display_buffer=('dmabuf' if gl=='zink' else 'native') if backend=='x11' else None
  if display_gl is not None: command+=['--x11-display-gl-mode',display_gl]
  if display_buffer is not None: command+=['--x11-display-buffer-mode',display_buffer]
  if mode=='proot':
   rootfs=folder/'root'; (rootfs/'etc').mkdir(parents=True); (rootfs/'usr/bin').mkdir(parents=True)
   (rootfs/'etc/os-release').write_text('ID=debian\nVERSION_CODENAME='+codename+'\n'); (rootfs/'usr/bin/gnome-shell').touch(); (rootfs/'usr/bin/gnome-shell').chmod(0o700)
   if usb:
    (rootfs/'usr/local/lib/termux-usb-bridge/gio/modules').mkdir(parents=True)
    (rootfs/'usr/local/lib/termux-usb-bridge/gio/modules/libtusb-volume-monitor.so').touch()
    (rootfs/'usr/local/libexec').mkdir(parents=True)
    (rootfs/'usr/local/libexec/tusb-gio-mount').touch(); (rootfs/'usr/local/libexec/tusb-gio-mount').chmod(0o700)
   turnip(rootfs/'usr',guest_icd,'/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so')
   shutil.copytree(services,rootfs/'usr/share/dbus-1/services')
   command+=['--rootfs',str(rootfs)]
  output=(folder/'output').open('w'); process=subprocess.Popen(command,env=env,stdout=output,stderr=output)
  try:
   if not success:
    assert process.wait(timeout=10)==expected_code, (name,(folder/'output').read_text())
    assert not ready.exists(), name
    if expected_code==2: assert not (folder/'calls').read_text(), name
    else:
     assert (work/'renderer-check.log').is_file()
     assert not (folder/'shell-running').exists(), 'renderer verification failure started Shell'
     assert 'GLX verification failed' in (folder/'output').read_text()
   else:
    wait_for(lambda:(folder/'shell-running').exists(),process)
    time.sleep(.3); assert not ready.exists(), 'session.env/socket must not imply Shell readiness'
    (folder/'shell-authorized').touch(); wait_for(ready.exists,process)
    assert ready.read_text().strip()==str(process.pid)
    calls=[json.loads(line) for line in (folder/'calls').read_text().splitlines()]
    assert not any(row[0]=='gnome-session' for row in calls), 'implicit session-manager startup was used'
    assert int((work/'shell.pid').read_text())==int((folder/'shell-running').read_text()), 'ready tracked a manager instead of Shell'
    assert all(row[2]['VK_ICD_FILENAMES'] is None and row[2]['LIBGL_KOPPER_DRI2'] is None and row[2]['MESA_VK_DEVICE_SELECT'] is None and row[2]['DRI_PRIME'] is None for row in calls), 'inherited renderer/Vulkan selection leaked'
    assert all(not row[2]['KWIN_ENV'] and row[2]['PLASMA_LOG'] is None for row in calls), 'KWin mode aliases leaked into GNOME children'
    if mode=='proot':
     xlorie=next(row for row in calls if row[0]=='Xlorie')
     assert xlorie[1][xlorie[1].index('-framerate')+1]==str(hz)
     assert xlorie[1][xlorie[1].index('-screen')+2]=='800x600x24'
     assert xlorie[2]['XLORIE_GL_MODE']==display_gl and xlorie[2]['XLORIE_GLX']=='1'
     assert xlorie[2]['TERMUX_RENDER_AHB_RECEIVE']==display_buffer
     assert probe_renderer in (work/'renderer-check.log').read_text()
     glx=next(row for row in calls if row[0]=='glxinfo')
     assert glx[1]==['-B'] and glx[2]['GALLIUM_DRIVER']==gl
     assert glx[2]['COGL_DRIVER']=='gl3' and glx[2]['CLUTTER_DRIVER']=='gl3'
     assert xlorie[2]['COGL_DRIVER'] is None and xlorie[2]['CLUTTER_DRIVER'] is None
     assert glx[2]['LIBGL_ALWAYS_SOFTWARE']==('1' if gl=='llvmpipe' else None)
     if gl=='zink':
      assert xlorie[2]['LIBGL_ALWAYS_SOFTWARE'] is None
      assert xlorie[2]['GALLIUM_DRIVER']=='zink' and xlorie[2]['MESA_LOADER_DRIVER_OVERRIDE']=='zink'
      assert xlorie[2]['VK_DRIVER_FILES']==str(prefix/'share/vulkan/icd.d/freedreno_icd.aarch64.json')
      assert glx[2]['MESA_LOADER_DRIVER_OVERRIDE']=='zink'
      assert glx[2]['VK_DRIVER_FILES']=='/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json'
     else:
      for key in ('LIBGL_ALWAYS_SOFTWARE','GALLIUM_DRIVER','MESA_LOADER_DRIVER_OVERRIDE','VK_DRIVER_FILES'): assert xlorie[2][key] is None
    else:
     shell=next(row for row in calls if row[0]=='gnome-shell')
     assert '--wayland' in shell[1] and '--no-x11' in shell[1]
     assert shell[2]['COGL_DRIVER'] is None and shell[2]['CLUTTER_DRIVER']=='gles2'
     assert not any(row[0]=='Xlorie' for row in calls)
    shell_audio=next(row for row in calls if row[0]=='gnome-shell')
    assert shell_audio[2]['PULSE_SERVER']=='tcp:127.0.0.1:4713', 'audio endpoint was not forwarded'
    child_pid=int((folder/'shell-running').read_text())
    sentinel=subprocess.Popen(['/bin/sleep','30'])
    try:
     process.terminate(); assert process.wait(timeout=10)==(1 if cleanup_failure else 130)
     assert sentinel.poll() is None, 'cleanup killed an unrelated process'
    finally:
     sentinel.terminate(); sentinel.wait()
    if usb: assert (folder/'storage-cleanup').read_text()=='session-alive'
    user_home = rootfs.resolve()/'root' if mode=='proot' else termux_home
    config_home = user_home/'.config' if mode=='proot' else work.resolve()/'config'
    assert (user_home/'user-created.txt').read_text()=='user data'
    assert not (work/'user-created.txt').exists(), 'user data leaked into launcher workspace'
    for filename in ('session.env','session.log','host-session.env'):
     assert not (user_home/filename).exists(), 'private runtime file leaked into Files HOME: '+filename
    assert (work/'session.env').is_file() and (work/'session.log').is_file()
    assert 'Hidden=true' in (config_home/'autostart/tusb-desktop.desktop').read_text()
    assert not (config_home/'gnome-session/sessions/gnome-bridge.session').exists(), 'unused session-manager config was generated'
    shell = next(row for row in calls if row[0]=='gnome-shell')
    assert shell[2]['HOME']==str(user_home)
    assert shell[2]['XCURSOR_THEME']=='Adwaita'
    assert shell[2]['XCURSOR_PATH']==('/usr/share/icons' if mode=='proot' else str(prefix/'share/icons'))
    assert any(row[0]=='gsettings' and row[1]==['set','org.gnome.desktop.interface','cursor-theme','Adwaita'] for row in calls)
    assert shell[2]['XDG_CONFIG_HOME']==str(config_home)
    assert shell[2]['XDG_CACHE_HOME']==str(user_home/'.cache' if mode=='proot' else work.resolve()/'cache')
    assert shell[2]['XDG_DATA_HOME']==str(user_home/'.local/share' if mode=='proot' else work.resolve()/'data')
    if mode=='proot':
     for flag in ('--x11','--sm-disable','--mode=user'): assert flag in shell[1]
     assert '--wayland' not in shell[1]
     assert shell[2]['COGL_DRIVER']=='gl3' and shell[2]['CLUTTER_DRIVER']=='gl3'
     proot_args = next(row[1] for row in calls if row[0]=='proot')
     for assignment in ('HOME=/root','XDG_CONFIG_HOME=/root/.config','XDG_CACHE_HOME=/root/.cache','XDG_DATA_HOME=/root/.local/share'):
      assert assignment in proot_args
     assert ('GALLIUM_DRIVER='+gl) in proot_args
     assert 'COGL_DRIVER=gl3' in proot_args and 'CLUTTER_DRIVER=gl3' in proot_args
     assert ('LIBGL_ALWAYS_SOFTWARE=1' in proot_args)==(gl=='llvmpipe')
     if gl=='zink':
      assert 'VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json' in proot_args
      assert 'MESA_LOADER_DRIVER_OVERRIDE=zink' in proot_args
     if usb:
      gio_command=json.loads((folder/'gio-command').read_text())
      assert any('. /tusb-gnome/session.env;' in part for part in gio_command)
      assert 'HOME=/root' in gio_command
    if usb:
     storage_args=json.loads((folder/'storage-args').read_text())
     assert storage_args[storage_args.index('--termux-usb')+1]==str(prefix/'libexec/termux-usb-app')
     import hashlib
     host_runtime = prefix/('tmp/gnome-'+hashlib.sha256(name.encode()).hexdigest()[:16])/'runtime'
     expected_bus = shell[2]['DBUS_SESSION_BUS_ADDRESS'].replace('/run/tusb-gnome/',str(host_runtime)+'/')
     assert (folder/'storage-bus').read_text()==expected_bus, 'storage left the desktop session bus'

    assert (work/'dbus-services/org.gnome.Nautilus.service').is_file()
    assert not ready.exists(), 'shutdown left a success marker'
    try: os.kill(child_pid,0)
    except ProcessLookupError: pass
    else: raise AssertionError('owned Shell child survived cleanup')
  except BaseException:
   print(name, (folder/'output').read_text(), file=sys.stderr)
   for logfile in folder.rglob('*.log'): print(str(logfile),logfile.read_text(),file=sys.stderr)
   raise
  finally:
   if process.poll() is None: process.kill(); process.wait()
   output.close(); sock.close()
 case('native-30',hz=30)
 case('native-software',gl='llvmpipe')
 case('native-zink',gl='zink')
 case('native-forbidden-x11-buffer',display_buffer='native')
 case('native-x11',backend='x11')
 case('proot-system',mode='proot',backend='x11',gl='system')
 case('proot-forky',mode='proot',backend='x11',gl='llvmpipe',codename='forky')
 case('native-forbidden-x11-display',display_gl='system')
 case('proot-display-missing',mode='proot',backend='x11',gl='llvmpipe',display_gl=None)
 case('proot-display-software',mode='proot',backend='x11',gl='llvmpipe',display_gl='llvmpipe')
 case('proot-guest-hardware',mode='proot',backend='x11',gl='llvmpipe',probe_renderer='Adreno GPU',expected_code=1)
 case('proot-glx-failed',mode='proot',backend='x11',gl='llvmpipe',probe_code=1,expected_code=1)
 case('native-startup-missing',startup=None)
 case('proot-startup-systemd',mode='proot',backend='x11',gl='llvmpipe',startup='systemd')
 case('proot-buffer-missing',mode='proot',backend='x11',gl='llvmpipe',display_buffer=None)
 case('proot-buffer-mismatch',mode='proot',backend='x11',gl='llvmpipe',display_buffer='dmabuf')
 case('zink-display-mismatch',mode='proot',backend='x11',gl='zink',display_gl='system')
 case('zink-buffer-mismatch',mode='proot',backend='x11',gl='zink',display_buffer='native')
 for part in ('missing','wrong-driver','malformed','missing-library'):
  case('zink-guest-icd-'+part,mode='proot',backend='x11',gl='zink',guest_icd=part)
  case('zink-host-icd-'+part,mode='proot',backend='x11',gl='zink',host_icd=part)
 for index,renderer in enumerate(('llvmpipe (LLVM)', 'zink (Vulkan llvmpipe)', 'zink (Intel GPU)', 'Adreno (TM) 660', 'zink (Adreno software)', '')):
  case('zink-invalid-renderer-'+str(index),mode='proot',backend='x11',gl='zink',probe_renderer=renderer,expected_code=1)
 case('zink-not-accelerated',mode='proot',backend='x11',gl='zink',probe_renderer='zink Turnip Adreno 660',probe_accelerated='no',expected_code=1)
 case('zink-acceleration-unknown',mode='proot',backend='x11',gl='zink',probe_renderer='zink Turnip Adreno 660',probe_accelerated='',expected_code=1)
 case('zink-glx-failed',mode='proot',backend='x11',gl='zink',probe_renderer='zink Vulkan Turnip Adreno 660',probe_code=1,expected_code=1)
 case('zink-ready',mode='proot',backend='x11',gl='zink',probe_renderer='zink Vulkan Turnip Adreno 660',success=True)
 case('zink-usb',mode='proot',backend='x11',gl='zink',probe_renderer='zink (Adreno (TM) 660)',success=True,usb=True)
 case('native-ready',success=True)
 case('proot-ready',mode='proot',backend='x11',gl='llvmpipe',hz=30,success=True)
 case('native-usb',success=True,usb=True)
 case('native-usb-cleanup-fail',success=True,usb=True,cleanup_failure=True)
 case('proot-usb',mode='proot',backend='x11',gl='llvmpipe',hz=30,success=True,usb=True)
 def xfce_case(name,mode='native',cleanup_failure=False):
  global count
  count+=1; folder=base/name; prefix=folder/'p'; (prefix/'bin').mkdir(parents=True); (prefix/'tmp').mkdir(); (folder/'calls').touch()
  for command in ('proot-distro','dbus-run-session','xfce4-session'):
   target=prefix/'bin'/command; target.write_text(mock); target.chmod(0o700)
  (prefix/'bin/sh').symlink_to('/bin/sh'); (prefix/'bin/python3').symlink_to(sys.executable)
  launcher=folder/'desktop_xfce_session.py'; shutil.copy(assets/launcher.name,launcher)
  shutil.copy(base/'native-usb/desktop_usb_storage.py',folder/'desktop_usb_storage.py')
  if cleanup_failure: (folder/'cleanup-failure').touch()
  if mode=='native': modules=prefix/'lib/gio/modules'
  else:
   rootfs=prefix/'var/lib/proot-distro/containers/debian-test/rootfs'
   modules=rootfs/'usr/local/lib/termux-usb-bridge/gio/modules'
   (rootfs/'usr/local/libexec').mkdir(parents=True); helper=rootfs/'usr/local/libexec/tusb-gio-mount'; helper.touch(); helper.chmod(0o700)
  modules.mkdir(parents=True); (modules/'libtusb-volume-monitor.so').touch()
  env=dict(os.environ,PREFIX=str(prefix),PATH=str(prefix/'bin')+':/usr/bin:/bin',FIXTURE=str(folder),PULSE_SERVER='tcp:127.0.0.1:4713')
  command=[sys.executable,'-I','-B',str(launcher),'--desktop-id',name,'--run-mode',mode,'--usb-storage','read-only','--display',':0']
  if mode=='proot': command+=['--alias','debian-test']
  output=(folder/'output').open('w'); process=subprocess.Popen(command,env=env,stdout=output,stderr=output)
  try:
   wait_for(lambda:(folder/'shell-running').exists(),process)
   import hashlib
   parent=prefix/('tmp/tusb-'+hashlib.sha256(name.encode()).hexdigest()[:16])
   wait_for(lambda:(parent/'desktop/control.sock').is_socket(),process)
   process.terminate(); assert process.wait(timeout=10)==(1 if cleanup_failure else 130)
   calls=[json.loads(line) for line in (folder/'calls').read_text().splitlines()]
   shell=next(row for row in calls if row[0]=='xfce4-session')
   assert shell[2]['PULSE_SERVER']=='tcp:127.0.0.1:4713'
   assert (folder/'storage-cleanup').read_text()=='session-alive'
   assert (folder/'storage-bus').read_text()=='unix:path='+str(parent/'xfce-session/session-bus')
  except BaseException:
   print(name,(folder/'output').read_text(),file=sys.stderr)
   for logfile in folder.rglob('*.log'): print(str(logfile),logfile.read_text(),file=sys.stderr)
   raise
  finally:
   if process.poll() is None: process.kill(); process.wait()
   output.close()
 xfce_case('xfce-native')
 xfce_case('xfce-proot',mode='proot')
 xfce_case('xfce-cleanup-fail',cleanup_failure=True)
 print('PASS: '+str(count)+' GNOME/XFCE launcher process/socket fixtures')
PY
