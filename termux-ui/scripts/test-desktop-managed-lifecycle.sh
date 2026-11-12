#!/bin/sh
# Local desktop switching/stop fixtures only; no packages, devices or network.
set -eu
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
python3 - "$PROJECT_ROOT" <<'PY'
import os,pathlib,shlex,shutil,socket,subprocess,sys,tempfile,threading,time
assets=pathlib.Path(sys.argv[1])/'termux-ui/src/main/assets/local-games'
with tempfile.TemporaryDirectory(prefix='dl-',dir='/tmp') as directory:
 base=pathlib.Path(directory)
 def run(name,failed=False,missing_spec=False,missing_asset=False,mode='native',gl='system',renderer=None,display=None,buffer=None,rejected=False,audio='host-pulseaudio',audio_failure=False,de='gnome'):
  work=base/name; work.mkdir(); prefix=work/'p'; (prefix/'bin').mkdir(parents=True)
  (prefix/'bin/python3').symlink_to(sys.executable)
  for audio_command in ('pulseaudio','pactl'):
   command=prefix/'bin'/audio_command
   command.write_text('#!/bin/sh\n[ "$FIXTURE_AUDIO_FAILURE" = 0 ] || exit 1\ncase "$*" in *"list short sinks"*) printf "0 android_sink module-sles-sink.c s16le\\n" ;; esac\n')
   command.chmod(0o700)
  home=work/'home'; home.mkdir(); state=work/'desktop/state'; state.mkdir(parents=True); specs=work/'desktop/specs'; specs.mkdir()
  scripts=work/'scripts'; scripts.mkdir()
  for filename in ('desktop_start.sh','desktop_stop.sh','desktop_common.sh','desktop_rendering.sh'): shutil.copy(assets/filename,scripts/filename)
  (scripts/'desktop_gnome_launch.py').write_text('import json,os,pathlib,sys\npathlib.Path(os.environ["FIXTURE"],"new-started").write_text(json.dumps(sys.argv[1:]))\npathlib.Path(os.environ["FIXTURE"],"new-audio").write_text(os.environ["PULSE_SERVER"])\n')
  (scripts/'desktop_ubuntu_gnome.py').write_text('# fixture validator\n')
  if not missing_asset: (scripts/'desktop_usb_storage.py').write_text('# fixture\n')
  env=dict(os.environ,PREFIX=str(prefix),HOME=str(home),FIXTURE=str(work),FIXTURE_AUDIO_FAILURE='1' if audio_failure else '0')
  def spec(identifier):
   values=dict(DESKTOP_ID=identifier,DESKTOP_MODE=mode,DESKTOP_DE=de,DESKTOP_SPEC_VERSION='3',DESKTOP_HOST_GL_MODE='system' if mode=='proot' and gl=='llvmpipe' else gl,DESKTOP_GUEST_GL_MODE=gl if mode=='proot' else 'none',DESKTOP_USB_STORAGE='off',DESKTOP_GNOME_BACKEND='x11' if mode=='proot' else 'android',DESKTOP_GNOME_RENDERER=renderer or gl,DESKTOP_GNOME_SESSION_STARTUP='direct',DESKTOP_REFRESH_RATE='60',DESKTOP_WIDTH='800',DESKTOP_HEIGHT='600',DESKTOP_RENDER_SOCKET=str(work/'render'),DESKTOP_PID_FILE=str(state/(identifier+'.pid')),DESKTOP_STATUS_FILE=str(state/(identifier+'.status')),DESKTOP_LOG_FILE=str(work/(identifier+'.log')))
   if audio is not None: values['DESKTOP_AUDIO_MODE']=audio
   if mode=='proot': values.update(DESKTOP_PROOT_DISTRO='debian-test',DESKTOP_CONTAINER_CODENAME='trixie')
   if de=='ubuntu_gnome': values.update(DESKTOP_CONTAINER_BASE='ubuntu:noble',DESKTOP_CONTAINER_CODENAME='noble',DESKTOP_GNOME_SESSION_PROFILE='ubuntu')
   if display is not None: values['DESKTOP_GNOME_DISPLAY_GL_MODE']=display
   if buffer is not None: values['DESKTOP_GNOME_DISPLAY_BUFFER_MODE']=buffer
   target=specs/(identifier+'.spec'); target.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items())); return target
  new_spec=spec('new'); old_spec=spec('old')
  old_code='''import os,pathlib,signal,time,sys
base=pathlib.Path(os.environ['FIXTURE'])
def stop(sig,frame):
 time.sleep(.3)
 (base/'old-cleaned').touch()
 if os.environ['FAILED']=='1': (base/'desktop/state/old.status').write_text('exit:73\\n')
 sys.exit(0)
signal.signal(signal.SIGTERM,stop)
(base/'old-running').touch()
while True: time.sleep(.05)
'''
  old=subprocess.Popen([sys.executable,'-c',old_code],env=dict(env,FAILED='1' if failed else '0'))
  reaper=threading.Thread(target=old.wait,daemon=True); reaper.start()
  while not (work/'old-running').exists(): time.sleep(.01)
  (state/'old.pid').write_text(str(old.pid)); (state/'old.pid.owned-session').write_text('gnome\n')
  if missing_spec: old_spec.unlink()
  render_socket=socket.socket(socket.AF_UNIX); render_socket.bind(str(work/'render'))
  try:
   result=subprocess.run(['sh',str(scripts/'desktop_start.sh'),str(new_spec)],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=15)
   if rejected:
    assert result.returncode==64 and old.poll() is None and not (work/'new-started').exists(),result.stdout
   elif missing_spec:
    assert result.returncode==74 and old.poll() is None and not (work/'new-started').exists(),result.stdout
   elif failed:
    assert result.returncode==74 and not (work/'new-started').exists(),result.stdout
    assert (state/'old.status').read_text()=='exit:73\n',result.stdout
   elif missing_asset:
    assert result.returncode==74 and not (work/'new-started').exists(),result.stdout
    assert (work/'old-cleaned').exists()
   elif audio != 'host-pulseaudio' or audio_failure:
    expected=70 if audio_failure else 64
    assert result.returncode==expected and not (work/'new-started').exists(),result.stdout
    assert (state/'new.status').read_text()=='exit:'+str(expected)+'\n'
    assert (work/'old-cleaned').exists(), 'audio failure bypassed owned session cleanup'
   else:
    assert result.returncode==0 and (work/'new-started').exists(),result.stdout
    assert (work/'old-cleaned').exists(),'new session started before old cleanup'
    assert (work/'new-audio').read_text()=='tcp:127.0.0.1:4713', 'managed audio endpoint was not forwarded'
    if mode=='proot':
     import json
     args=json.loads((work/'new-started').read_text())
     assert args[args.index('--x11-display-gl-mode')+1]==display
     assert args[args.index('--x11-display-buffer-mode')+1]==buffer
     assert args[args.index('--gl-mode')+1]==gl
     if de=='ubuntu_gnome': assert args[args.index('--session-profile')+1]=='ubuntu'
    assert (prefix/'libexec/termux-gnome/launch.py').read_text()==(scripts/'desktop_gnome_launch.py').read_text()
  finally:
   if old.poll() is None: old.terminate()
   reaper.join(timeout=5)
   if old.poll() is None: old.kill(); old.wait()
   render_socket.close()
 run('audio-missing',audio=None); run('audio-invalid',audio='auto'); run('audio-service-failed',audio_failure=True)
 run('switch-ok'); run('switch-cleanup-error',failed=True); run('switch-spec-missing',missing_spec=True); run('stage-error',missing_asset=True)
 run('proot-llvmpipe',mode='proot',gl='llvmpipe',display='system',buffer='native')
 run('proot-zink',mode='proot',gl='zink',display='zink',buffer='dmabuf')
 run('proot-mismatched-renderer',mode='proot',gl='zink',renderer='llvmpipe',display='zink',buffer='dmabuf',rejected=True)
 run('proot-missing-buffer',mode='proot',gl='zink',display='zink',rejected=True)
 run('proot-mismatched-display',mode='proot',gl='zink',display='system',buffer='dmabuf',rejected=True)
 run('proot-mismatched-buffer',mode='proot',gl='zink',display='zink',buffer='native',rejected=True)
 run('native-forbidden-buffer',buffer='native',rejected=True)
 run('ubuntu-switch-ok',mode='proot',gl='llvmpipe',display='system',buffer='native',de='ubuntu_gnome')
 run('ubuntu-cleanup-error',mode='proot',gl='llvmpipe',display='system',buffer='native',de='ubuntu_gnome',failed=True)
 # Blocking the package's global autostart must preserve all other user entries.
 folder=base/'autostart'; folder.mkdir(); config=folder/'config'; autostart=config/'autostart'; autostart.mkdir(parents=True)
 (autostart/'user.desktop').write_text('unchanged'); (autostart/'tusb-desktop.desktop').write_text('Exec=unexpected-global-service')
 spec=dict(DESKTOP_LOG_FILE=str(folder/'log'),DESKTOP_PID_FILE=str(folder/'state/pid'),DESKTOP_STATUS_FILE=str(folder/'state/status'))
 command='. '+shlex.quote(str(assets/'desktop_common.sh'))+'; disable_global_usb_autostart '+shlex.quote(str(config))
 result=subprocess.run(['sh','-c',command],env=dict(os.environ,**spec),capture_output=True,text=True)
 assert result.returncode==0,result.stderr
 assert (autostart/'user.desktop').read_text()=='unchanged'
 hidden=(autostart/'tusb-desktop.desktop').read_text(); assert 'Hidden=true' in hidden and 'Exec=' not in hidden
 print('PASS: 17 managed desktop lifecycle fixtures')
PY
