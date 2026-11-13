#!/usr/bin/env python3
"""Exercise real C transport with a fake same-UID Android endpoint and real callback sockets."""
import pathlib, subprocess, tempfile, socket, struct, threading, re, time, os, unittest, json, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
class CompanionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(); cls.binary=pathlib.Path(cls.tmp.name)/'api'
        subprocess.run(['cc','-Os','-pthread',str(ROOT/'upstream-cli/termux-api.c'),str(ROOT/'upstream-cli/termux-api-broadcast.c'),'-o',str(cls.binary)],check=True)
    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()
    def transact(self, args, expected_input=None):
        server=socket.socket(socket.AF_UNIX); server.bind('\0com.termux://api-v1'); server.listen(1); errors=[]; commands=[]
        def endpoint():
            try:
                with server.accept()[0] as conn:
                    conn.settimeout(3)
                    size=struct.unpack('!H',conn.recv(2))[0]; data=b''
                    while len(data)<size: data+=conn.recv(size-len(data))
                    command=data.decode(); commands.append(command)
                    output=re.search(r'--es socket_output "([^"]+)"',command)[1]
                    inp=re.search(r'--es socket_input "([^"]+)"',command)[1]
                    ready=re.search(r'--es socket_ready "([^"]+)"',command)[1]
                    with socket.socket(socket.AF_UNIX) as ack:
                        ack.connect('\0'+ready); ack.sendall(b'\0')
                    conn.sendall(b'\0')
                with socket.socket(socket.AF_UNIX) as result:
                    result.connect('\0'+output)
                    if expected_input is not None:
                        with socket.socket(socket.AF_UNIX) as source:
                            source.connect('\0'+inp); body=b''
                            while True:
                                chunk=source.recv(4096)
                                if not chunk: break
                                body+=chunk
                            assert body==expected_input,(body,expected_input)
                    result.sendall(b'{"percentage":73}\n')
            except BaseException as e: errors.append(e)
        thread=threading.Thread(target=endpoint); thread.start()
        try:
            p=subprocess.run([str(self.binary),*args],input=expected_input or b'',capture_output=True,timeout=5)
            thread.join(5); self.assertFalse(thread.is_alive()); self.assertFalse(errors,errors)
            self.assertEqual(p.returncode,0,p.stderr); self.assertEqual(p.stdout,b'{"percentage":73}\n')
            return commands[0]
        finally: server.close()
    def test_battery_completes_and_reaps_stdin_thread(self):
        self.assertIn('--es api_method "BatteryStatus"',self.transact(['BatteryStatus']))
    def test_text_stdin_and_quoted_arguments(self):
        self.assertIn('quoted \\"text\\"',self.transact(['Clipboard','--es','label','quoted "text"'],b'hello\n'))
    def test_unavailable_host_fails_bounded(self):
        t=time.monotonic();p=subprocess.run([str(self.binary),'BatteryStatus'],capture_output=True,timeout=12)
        self.assertEqual(p.returncode,124); self.assertIn(b'unavailable',p.stderr);self.assertLess(time.monotonic()-t,11)
    def test_manifest_entrypoints_are_private(self):
        ns='{http://schemas.android.com/apk/res/android}'
        manifest=ET.parse(ROOT.parents[1]/'termux-api/src/main/AndroidManifest.xml').getroot()
        app=manifest.find('application')
        for component in app:
            name=component.get(ns+'name')
            if component.tag in ['activity','receiver']:
                self.assertEqual(component.get(ns+'exported'),'false',name)
            if component.tag=='service' and component.get(ns+'exported')=='true':
                self.assertEqual(component.get(ns+'permission'),'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE')
        self.assertIsNone(app.get(ns+'name'))
        permission=next(c for c in app if c.get(ns+'name')=='com.termux.api.activities.TermuxApiPermissionActivity')
        self.assertNotEqual(permission.get(ns+'theme'),'@android:style/Theme.NoDisplay')
        self.assertNotEqual(permission.get(ns+'noHistory'),'true')
    def test_inventory_has_full_dispatch_and_cli_routes(self):
        repo=ROOT.parents[1]
        inventory=json.loads((repo/'openspec/changes/embed-complete-termux-api/upstream-inventory.json').read_text())
        receiver=(repo/'termux-api/src/main/java/com/termux/api/TermuxApiReceiver.java').read_text().split('private static String requiredFeature')[0]
        self.assertEqual(set(inventory['methods']),set(re.findall(r'case "([^"]+)":',receiver)))
        self.assertEqual(len(inventory['scripts']),57)
        for command in inventory['scripts']:
            self.assertTrue((ROOT/'upstream-cli/scripts'/f'{command}.in').is_file())
        for source in (repo/'termux-api/src/main/java').rglob('*.java'):
            self.assertNotIn('setClassName(TermuxConstants.TERMUX_API_PACKAGE_NAME,',source.read_text(),str(source))
    def test_async_battery_query_mainloop_and_timeout(self):
        binary=pathlib.Path(self.tmp.name)/'battery-query'
        flags=subprocess.check_output(['pkg-config','--cflags','--libs','gio-2.0'],text=True).split()
        subprocess.run(['cc',str(ROOT/'tests/battery-query.c'),'-o',str(binary),*flags],check=True)
        for name,body,success in [('success','echo \'{"percentage":73}\'','1'),('timeout','sleep 60 & echo $! > '+str(pathlib.Path(self.tmp.name)/'child-pid')+'; wait','0')]:
            script=pathlib.Path(self.tmp.name)/name;script.write_text('#!/bin/sh\n'+body+'\n');script.chmod(0o700)
            subprocess.run([str(binary),str(script),success],check=True,timeout=3)
            if success == '0':
                pid=int((pathlib.Path(self.tmp.name)/'child-pid').read_text()); proc=pathlib.Path(f'/proc/{pid}/stat')
                self.assertTrue(not proc.exists() or proc.read_text().split()[2] == 'Z', 'timed-out grandchild still running')
if __name__=='__main__': unittest.main(verbosity=2)
