#!/usr/bin/env python3
"""Execute the real Plasma manager/user shell with isolated service stand-ins."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'termux-ui/src/main/assets/local-games/desktop_container_session.sh'
MOCK=r'''
import json,os,subprocess,sys,time
from pathlib import Path
name=Path(sys.argv[0]).name;args=sys.argv[1:];uid=os.getenv('ROLE_UID','0')
with open(os.environ['ROLE_TRACE'],'a') as f:f.write(json.dumps([name,uid,os.getenv('HOME'),os.getpid()])+'\n')
if name=='id':
 print('1000' if args==['-u','termux'] else 'termux' if '-un' in args or '-gn' in args else uid)
elif name=='getent': print('termux:x:1000:1000::'+os.environ['ROLE_HOME']+':/bin/bash')
elif name=='runuser':
 os.environ['ROLE_UID']=os.getenv('ROLE_INJECT_UID','1000');os.execvp(args[3],args[3:])
elif name=='chown': pass
elif name=='dbus-send':
 if any('GetNameOwner' in a for a in args):print('string ":1.7"')
elif name=='dbus-daemon':
 p=subprocess.Popen(['/bin/sleep','30'],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 print('unix:path=/fixture/'+uid)
 os.write(3,str(p.pid).encode())
elif name=='dbus-update-activation-environment': pass
elif name=='packagekitd' and os.getenv('ROLE_FAIL_SERVICE')=='1': sys.exit(9)
else: os.execl('/bin/sleep','sleep','30')
'''
class UserSession(unittest.TestCase):
    def run_case(self, injected_uid='1000', fail_service=False, foreign_bus=False):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);binary=root/'bin';binary.mkdir();home=root/'home';home.mkdir()
            runtime=root/'runtime';runtime.mkdir();trace=root/'trace';trace.touch()
            if foreign_bus:
                (root/'dbus').mkdir();(root/'dbus/system_bus_socket').symlink_to('/foreign/socket')
            names=['id','getent','runuser','chown','dbus-daemon','dbus-send','polkitd','packagekitd',
                'kactivitymanagerd','kded6','plasmashell','password-agent','dbus-update-activation-environment']
            for name in names:
                path=binary/name;path.write_text('#!'+sys.executable+'\n'+MOCK);path.chmod(0o755)
            source=SOURCE.read_text().replace('export PATH=', '# fixture PATH=')
            for original,replacement in [('/usr/bin/dbus-daemon',str(binary/'dbus-daemon')),
                ('/usr/bin/dbus-send',str(binary/'dbus-send')),('/usr/lib/polkit-1/polkitd',str(binary/'polkitd')),
                ('/usr/libexec/packagekitd',str(binary/'packagekitd')),
                ('/usr/bin/python3 /tmp/desktop_container_polkit_agent.py',str(binary/'password-agent')),
                ('/run/dbus',str(root/'dbus')),('/home/termux',str(home))]:source=source.replace(original,replacement)
            script=root/'session.sh';script.write_text(source)
            env=dict(os.environ,PATH=str(binary)+':/usr/bin:/bin',HOME=str(root),ROLE_TRACE=str(trace),
                ROLE_HOME=str(home),ROLE_INJECT_UID=injected_uid,ROLE_FAIL_SERVICE='1' if fail_service else '0',
                DESKTOP_KDE_SERVICE_POLICY='kwin-wayland',QT_QPA_PLATFORM='wayland',XDG_SESSION_TYPE='wayland',
                DESKTOP_GUEST_GL_MODE='llvmpipe',PULSE_SERVER='tcp:127.0.0.1:4713',
                DESKTOP_PLASMA_SESSION_TOKEN='fixture-token',XDG_RUNTIME_DIR=str(runtime),KDE_LIBEXEC=str(binary))
            process=subprocess.Popen(['sh',str(script)],env=env,start_new_session=True)
            try:
                ready=runtime/'plasma-container.ready';deadline=time.monotonic()+8
                while not ready.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.03)
                if injected_uid=='1000' and not fail_service and not foreign_bus:
                    self.assertTrue(ready.exists(),(runtime/'plasma-container.log').read_text())
                    self.assertEqual('fixture-token',ready.read_text().strip());process.terminate()
                    self.assertEqual(143,process.wait(timeout=7))
                else:self.assertEqual(1 if foreign_bus else 9 if fail_service else 77,process.wait(timeout=7))
                self.assertFalse(ready.exists())
                socket=root/'dbus/system_bus_socket'
                if foreign_bus:self.assertEqual('/foreign/socket',os.readlink(socket))
                else:self.assertFalse(socket.is_symlink())
                rows=[json.loads(line) for line in trace.read_text().splitlines()]
                for name in ['polkitd','packagekitd']:
                    self.assertEqual(not foreign_bus,any(r[0]==name and r[1]=='0' for r in rows))
                user_rows=[r for r in rows if r[0] in ['plasmashell','kded6','kactivitymanagerd']]
                if injected_uid=='1000' and not fail_service and not foreign_bus:
                    self.assertTrue(any(r[0]=='password-agent' and r[1]=='0' for r in rows));self.assertEqual(3,len(user_rows));self.assertTrue(all(r[1]=='1000' and r[2]==str(home) for r in user_rows))
                else:self.assertFalse(user_rows)
                for row in user_rows:
                    status=Path('/proc')/str(row[3])/'stat'
                    self.assertTrue(not status.exists() or status.read_text().split(') ')[1].startswith('Z '))
            finally:
                if process.poll() is None:process.terminate();process.wait(timeout=7)
                try:os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:pass
    def test_foreign_system_bus_is_preserved_and_blocks_start(self):self.run_case(foreign_bus=True)
    def test_manager_stays_root_but_kde_and_agent_are_ordinary_and_stop_cleanly(self):self.run_case()
    def test_failed_identity_switch_never_starts_kde_or_publishes_ready(self):self.run_case(injected_uid='0')
    def test_failed_privileged_service_prevents_user_start_and_ready(self):self.run_case(fail_service=True)

if __name__=='__main__':unittest.main()
