#!/usr/bin/python3
from pathlib import Path
import os,subprocess,time,sys
root=Path('/tmp/fwupd-state-test-20261030')
if len(sys.argv)!=4 or sys.argv[2] not in ['baseline','fixed'] or sys.argv[3] not in ['error','no-devices']:
 raise SystemExit('usage: run-test.py plugin-library-path baseline|fixed error|no-devices')
address='unix:path='+str(root/'bus.sock')
(root/'bus.sock').unlink(missing_ok=True)
bus=subprocess.Popen(['dbus-daemon','--session','--nofork','--print-address=1','--address='+address],stdout=subprocess.PIPE,text=True)
actual=bus.stdout.readline().strip();assert actual.startswith(address),actual
os.environ['DBUS_SESSION_BUS_ADDRESS']=actual
os.environ['DBUS_SYSTEM_BUS_ADDRESS']=actual
serverlog=(root/('server-'+sys.argv[2]+'-'+sys.argv[3]+'.log')).open('w')
server=subprocess.Popen(['/usr/bin/python3',str(root/'fake-fwupd.py')],stdout=serverlog,stderr=subprocess.STDOUT)
try:
 for attempt in range(100):
  serverlog.flush()
  if 'READY' in (root/('server-'+sys.argv[2]+'-'+sys.argv[3]+'.log')).read_text():break
  if server.poll() is not None:raise RuntimeError('fake service failed')
  time.sleep(.05)
 else:raise RuntimeError('fake service readiness timeout')
 result=subprocess.run([str(root/'run-arm64-user'),str(root/'build/fwupd-state-probe'),sys.argv[1],sys.argv[2],sys.argv[3]],text=True,capture_output=True,timeout=120)
 output=result.stdout+result.stderr
 (root/('result-'+sys.argv[2]+'-'+sys.argv[3]+'.log')).write_text(output)
 print(output)
 expected='BASELINE REPRODUCED:' if sys.argv[2]=='baseline' else 'PASS: actual plugin query lifecycle'
 assert result.returncode==0 and expected in output,(result.returncode,expected)
 raise SystemExit(0)
finally:
 server.terminate();bus.terminate();server.wait();bus.wait()
