#!/usr/bin/env python3
"""Execute account provisioning against an isolated passwd/command fixture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[2] / 'termux-ui/src/main/assets/local-games'
MOCK = r'''
import json,os,sys
from pathlib import Path
root=Path(os.environ['ACCOUNT_FIXTURE']); state=json.loads((root/'state').read_text())
cmd=Path(sys.argv[0]).name; args=sys.argv[1:]
with (root/'trace').open('a') as f: f.write(json.dumps([cmd,*args])+'\n')
if cmd=='id':
    if args==['-u']: print(state.get('manager_uid',0))
    elif args==['-u','termux']: print(state['uid'])
    elif args==['-gn','termux']: print('termux')
elif cmd=='getent':
    if 'uid' not in state: sys.exit(2)
    print('termux:x:'+str(state['uid'])+':1000::'+state.get('home',str(root/'home'))+':/bin/bash')
elif cmd=='useradd': state['uid']=1000
elif cmd=='chpasswd': state['password_writes']=state.get('password_writes',0)+1; state['password']=sys.stdin.read()
elif cmd=='install': Path(args[-1]).mkdir(parents=True,exist_ok=True)
elif cmd=='visudo':
    if Path(args[1].split('=',1)[1]).read_text()!='termux ALL=(ALL:ALL) ALL\n': sys.exit(1)
(root/'state').write_text(json.dumps(state))
'''

class AccountProvisioning(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name); (self.root/'bin').mkdir()
        for command in ('id','getent','useradd','usermod','chpasswd','install','visudo'):
            file=self.root/'bin'/command
            file.write_text('#!/usr/bin/env python3\n'+MOCK);file.chmod(0o755)
        source=(ASSETS/'desktop_container_user.sh').read_text()
        source=source.replace('export PATH=', '# fixture PATH=')
        source=source.replace('/home/termux',str(self.root/'home')).replace('/etc/sudoers.d',str(self.root/'sudoers.d'))
        self.script=self.root/'provision';self.script.write_text(source)
        self.state={}
    def run_script(self):
        (self.root/'state').write_text(json.dumps(self.state))
        r=subprocess.run(['sh',str(self.script)],env=dict(os.environ,ACCOUNT_FIXTURE=str(self.root),
            PATH=str(self.root/'bin')+':'+os.environ['PATH']),capture_output=True,text=True,timeout=10)
        self.state=json.loads((self.root/'state').read_text());return r
    def test_creates_account_home_and_password_protected_admin_policy(self):
        r=self.run_script();self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual(1000,self.state['uid']);self.assertEqual('termux:termux\n',self.state['password'])
        self.assertTrue((self.root/'home/.local/share').is_dir())
        self.assertEqual(0o440,(self.root/'sudoers.d/termux').stat().st_mode & 0o777)
        self.assertNotIn('NOPASSWD',(self.root/'sudoers.d/termux').read_text())
    def test_retry_preserves_changed_password(self):
        self.assertEqual(0,self.run_script().returncode)
        self.state['password']='user-changed';self.assertEqual(0,self.run_script().returncode)
        self.assertEqual(1,self.state['password_writes']);self.assertEqual('user-changed',self.state['password'])
    def test_root_identity_or_wrong_home_fails_closed(self):
        for state in ({'uid':0},{'uid':1000,'home':'/root'},{'manager_uid':1000}):
            with self.subTest(state=state):
                self.state=state;self.assertEqual(77,self.run_script().returncode)
                self.assertNotIn('password_writes',self.state)

if __name__=='__main__': unittest.main()
