# SPDX-License-Identifier: MIT
"""Run inside a real Termux App child under dbus-run-session.
Requires previously compiled camera2-rgb, upstream gst-camera2 and gstpipewire.
This is a GUI validation harness, not an installed camera backend.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work', type=Path, required=True)
parser.add_argument('--app-dir', type=Path, required=True)
parser.add_argument('--camera-id', required=True)
parser.add_argument('--label', choices=('front', 'back'), required=True)
parser.add_argument('--display', default=':2')
parser.add_argument('--packaged', action='store_true', help='test installed tusb-camera2, source and private plugin')
parser.add_argument('--bridge-command', type=Path, help=argparse.SUPPRESS)
args = parser.parse_args()
w = args.work.resolve()
w.mkdir(parents=True, exist_ok=True)
if any(w.glob('cap_*')):
    parser.error('--work must not contain old cap_* captures; use a fresh directory')
for name in ('runtime', 'config/autostart', 'cache', 'data'):
    (w/name).mkdir(parents=True, exist_ok=True)
(w/'runtime').chmod(0o700)
(w/'config/autostart/tusb-desktop.desktop').write_text('[Desktop Entry]\nType=Application\nHidden=true\n')
node_name = f'tusb.camera2.{args.label}'
e = dict(os.environ, HOME=str(w), DISPLAY=args.display, XDG_RUNTIME_DIR=str(w/'runtime'),
         PIPEWIRE_RUNTIME_DIR=str(w/'runtime'), PIPEWIRE_REMOTE='pipewire-0',
         XDG_CONFIG_HOME=str(w/'config'), XDG_CACHE_HOME=str(w/'cache'), XDG_DATA_HOME=str(w/'data'),
         XDG_CURRENT_DESKTOP='XFCE', GST_PLUGIN_PATH=str(args.app_dir/'plugin'), GDK_BACKEND='x11',
         GST_PLUGIN_FEATURE_RANK='ximagesink:3000,cacasink:0,glimagesink:0,xvimagesink:0')
if args.packaged and not args.bridge_command:
    e['GST_PLUGIN_PATH']=os.environ.get('PREFIX','/data/data/com.termux/files/usr')+'/lib/termux-usb-bridge/gstreamer-1.0'
(w/'env.json').write_text(json.dumps(e))
children = []
logs = []

def run(argv, **kwargs):
    return subprocess.run(argv, env=e, cwd=w, check=True, timeout=20, **kwargs)

def output(argv):
    return run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout

def start(name, argv, *, env=None, stdin=None, stdout=None):
    log = (w/(name+'.log')).open('w')
    logs.append(log)
    child = subprocess.Popen(argv, env=env or e, cwd=w, stdin=stdin,
                             stdout=stdout if stdout is not None else log, stderr=log,
                             start_new_session=True)
    children.append(child)
    return child

def wait_for(test, message, seconds=25):
    until = time.monotonic()+seconds
    while time.monotonic()<until:
        value = test()
        if value:
            return value
        time.sleep(.15)
    raise RuntimeError(message)

def nodes():
    return json.loads(output(['pw-dump']))

def camera_node():
    return next((n for n in nodes() if n.get('info',{}).get('props',{}).get('node.name')==node_name),None)

def screenshot(name):
    run(['ffmpeg','-y','-loglevel','error','-f','x11grab','-i',args.display,'-frames:v','1',str(w/name)])

result = {}
try:
    print('GUI_CAMERA2_START', args.label, 'uid='+str(os.getuid()), flush=True)
    start('x11', ['termux-x11',args.display,'-ac'])
    time.sleep(2)
    start('xfce',['xfce4-session'])
    start('pipewire',['pipewire'])
    wait_for(lambda:(w/'runtime/pipewire-0').exists(),'PipeWire socket missing')
    start('wireplumber',['wireplumber'])
    time.sleep(2)
    if args.packaged:
        native=publisher=start('bridge',[str(args.bridge_command or 'tusb-camera2'),'serve','--camera',args.camera_id,'--name',node_name])
    else:
        native_env=dict(e, LD_LIBRARY_PATH='/system/lib64')
        native=start('camera2', [str(w.parent/'camera2-rgb'),'--stream',args.camera_id,'18000'],
                     env=native_env, stdout=subprocess.PIPE)
        publisher=start('publisher',[str(w.parent/'rgb-source'),node_name,'Android Camera2 '+args.label],stdin=native.stdout)
        native.stdout.close()
    wait_for(camera_node,'Camera2 video source not published')
    (w/'devices.log').write_text(output(['gst-device-monitor-1.0','Video/Source']))
    viewer=start('viewer',[str(args.app_dir/'gst-camera2')])
    def linked():
        links=output(['pw-link','-l'])
        return links if node_name+':' in links and 'gst-camera2:input_' in links else None
    (w/'links.log').write_text(wait_for(linked,'GTK camera did not auto-connect'))
    window=wait_for(lambda:output(['xdotool','search','--onlyvisible','--name','^gst-camera2$']).strip(), 'GTK camera window not visible')
    geo=dict(line.split('=',1) for line in output(['xdotool','getwindowgeometry','--shell',window.splitlines()[0]]).splitlines())
    x,y,width=int(geo['X']),int(geo['Y']),int(geo['WIDTH'])
    # Unmodified upstream UI: 640px viewfinder at left, Capture action at right.
    def photo(index):
        run(['xdotool','mousemove',str(x+width-80),str(y+54),'click','1'])
        wait_for(lambda:(w/f'cap_{index}').exists() and (w/f'cap_{index}').stat().st_size>1000,'GUI Capture did not save JPEG')
        time.sleep(.4)
    time.sleep(2)
    screenshot('preview-1.png')
    photo(0)
    time.sleep(2)
    screenshot('preview-2.png')
    photo(1)
    (w/'graph.json').write_text(json.dumps(nodes(),indent=2))
    if not args.packaged:
        wait_for(lambda:'STREAM frames=90' in (w/'camera2.log').read_text(),'Native source did not deliver 90 frames')
    (w/'capture-ready').touch()
    print('GUI_CAMERA2_CAPTURE_PASS',args.label,flush=True)
    # Close the actual XFCE window through its observed title-bar close button.
    run(['xdotool','mousemove',str(x+width-10),str(y-14),'click','1'])
    viewer.wait(timeout=10)
    assert viewer.returncode==0, viewer.returncode
    # Reader keeps draining even with no GUI consumer. Stop Camera2 on a frame
    # boundary; EOF then stops the publisher without breaking an in-flight write.
    native.terminate()
    native.wait(timeout=10)
    publisher.wait(timeout=10)
    assert native.returncode==publisher.returncode==0, (native.returncode,publisher.returncode)
    wait_for(lambda:not camera_node(),'Camera2 PipeWire node leaked')
    after=output(['pw-link','-l'])
    (w/'links-after-close.log').write_text(after)
    assert node_name not in after
    result=dict(status='PASS', camera_id=args.camera_id,label=args.label,uid=os.getuid(),
                viewer_exit=viewer.returncode,native_exit=native.returncode,publisher_exit=publisher.returncode,
                source=node_name,photos=['cap_0','cap_1'],input='Android Camera2 HAL; no test-pattern source',
                note='Installed Camera2 framed RGB transport with capture timestamps' if args.packaged else 'Raw RGB prototype transport retimestamps at nominal 30fps; not a packaged backend')
finally:
    for child in reversed(children):
        if child.poll() is None:
            os.killpg(child.pid,signal.SIGTERM)
            try:child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid,signal.SIGKILL)
                child.wait()
    for log in logs:
        log.close()
    (w/'result.json').write_text(json.dumps(result,indent=2)+'\n')
print('GUI_CAMERA2_DONE',args.label,json.dumps(result),flush=True)
