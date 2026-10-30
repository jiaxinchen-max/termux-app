# SPDX-License-Identifier: MIT
import argparse,os,json,subprocess,time
from pathlib import Path
parser=argparse.ArgumentParser(description='Consume host Android Camera2 from an existing Linux PRoot rootfs')
parser.add_argument('--rootfs',type=Path,required=True)
parser.add_argument('--work',type=Path,required=True)
parser.add_argument('--camera',default='10')
parser.add_argument('--delay',type=int,default=25)
parser.add_argument('--bridge-command',default='tusb-camera2')
args=parser.parse_args()
work=args.work.resolve();work.mkdir(parents=True,exist_ok=True)
runtime=work/'runtime';runtime.mkdir(mode=0o700,exist_ok=True)
captures=work/'captures';captures.mkdir(exist_ok=True)
root=args.rootfs.resolve()
(root/'run/tusb-pipewire').mkdir(parents=True,exist_ok=True)
(root/'captures').mkdir(exist_ok=True)
e=dict(os.environ,XDG_RUNTIME_DIR=str(runtime),PIPEWIRE_RUNTIME_DIR=str(runtime),PIPEWIRE_REMOTE='pipewire-0')
pe=dict(e);pe.pop('LD_PRELOAD',None)
base=['proot','--link2symlink','-0','-r',str(root),'-b','/dev','-b','/proc','-b','/sys','-b',str(runtime)+':/run/tusb-pipewire','-b',str(captures)+':/captures','-w','/',
 '/usr/bin/env','-i','PATH=/usr/sbin:/usr/bin:/sbin:/bin','HOME=/root','XDG_RUNTIME_DIR=/run/tusb-pipewire','PIPEWIRE_RUNTIME_DIR=/run/tusb-pipewire','PIPEWIRE_REMOTE=pipewire-0']
children=[]
def guest(args,timeout=45):
 result=subprocess.run(base+args,env=pe,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout)
 print(result.stdout,flush=True)
 if result.returncode:raise RuntimeError('guest failed: '+str(result.returncode)+' '+str(args))
 return result.stdout
try:
 print('CONTAINER_CAMERA_START uid='+str(os.getuid()),flush=True)
 guest(['gst-inspect-1.0','pipewiresrc'])
 for name,command in [('pipewire',['pipewire']),('wireplumber',['wireplumber'])]:
  f=(work/(name+'.log')).open('w');children.append(subprocess.Popen(command,env=e,stdout=f,stderr=f));time.sleep(1)
 f=(work/'camera2.log').open('w')
 camera=subprocess.Popen([args.bridge_command,'serve','--camera',args.camera,'--name','tusb.camera2.container'],env=e,stdout=f,stderr=f);children.append(camera)
 time.sleep(2)
 assert camera.poll() is None
 (work/'devices.log').write_text(guest(['gst-device-monitor-1.0','Video/Source']))
 graph=json.loads(guest(['pw-dump']))
 assert any(n.get('info',{}).get('props',{}).get('node.name')=='tusb.camera2.container' for n in graph)
 (work/'graph.json').write_text(json.dumps(graph))
 for label in ('early','late'):
  if label=='late':time.sleep(args.delay)
  guest(['gst-launch-1.0','-q','pipewiresrc','target-object=tusb.camera2.container','num-buffers=30','!',
         'videoconvert','!','video/x-raw,format=RGB','!','pngenc','!',
         'multifilesink','location=/captures/'+label+'-%02d.png'])
  assert len(list(captures.glob(label+'-*.png')))==30
  print('PASS Alpine/musl PRoot -> host Camera2/PipeWire: '+label+' 30 PNG frames',flush=True)
 camera.terminate();assert camera.wait(timeout=10)==0
 print('CONTAINER_CAMERA_PASS',flush=True)
finally:
 for p in reversed(children):
  if p.poll() is None:p.terminate()
  try:p.wait(timeout=10)
  except subprocess.TimeoutExpired:p.kill();p.wait()
