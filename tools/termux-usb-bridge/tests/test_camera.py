# SPDX-License-Identifier: MIT
"""Actual PipeWire clients; input is explicitly a synthetic pattern, not USB UVC."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('build', type=Path)
parser.add_argument('--output-dir', type=Path)
args = parser.parse_args()
build = args.build.resolve()
if args.output_dir: args.output_dir.mkdir(parents=True, exist_ok=True)


def wait_for(predicate, message, timeout=6):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value: return value
        time.sleep(0.03)
    raise RuntimeError(message)


with tempfile.TemporaryDirectory(prefix='tusb-pw-', dir=os.environ.get('TMPDIR', '/tmp')) as directory:
    runtime = Path(directory)
    environment = dict(os.environ, XDG_RUNTIME_DIR=directory, PIPEWIRE_RUNTIME_DIR=directory, PIPEWIRE_REMOTE='pipewire-0')
    processes = []
    with (runtime / 'server.log').open('w+') as server_log, (runtime / 'camera.log').open('w+') as camera_log:
        def nodes():
            return json.loads(subprocess.check_output(['pw-dump'], env=environment, text=True, timeout=5))
        def has_node(name):
            return any(n.get('info', {}).get('props', {}).get('node.name') == name for n in nodes())
        try:
            server = subprocess.Popen([shutil.which('pipewire')], env=environment, stdout=server_log, stderr=server_log)
            processes.append(server)
            wait_for(lambda: (runtime / 'pipewire-0').exists(), 'PipeWire test server failed')
            for index, (width, height, fps, frames) in enumerate(((64,48,10,3), (18,3,10,3), (640,480,30,30), (1280,720,30,10), (64,48,1,3))):
                name, capture_name = f'tusb.camera.validation.{index}', f'tusb.capture.validation.{index}'
                source = subprocess.Popen([str(build / 'tusb-camera'), '--test-pattern', '--width', str(width), '--height', str(height),
                                           '--fps', str(fps), '--seconds', '15', '--name', name], env=environment, stdout=camera_log, stderr=camera_log)
                processes.append(source)
                wait_for(lambda: has_node(name), 'camera node not published')
                properties = next(n['info']['props'] for n in nodes() if n.get('info',{}).get('props',{}).get('node.name') == name)
                assert properties['media.class'] == 'Video/Source', properties
                image = (args.output_dir or runtime) / f'camera-{width}x{height}-{fps}fps.ppm'
                sink = subprocess.Popen([str(build / 'tusb-camera-sink'), '--width', str(width), '--height', str(height), '--fps', str(fps),
                                         '--frames', str(frames), '--name', capture_name, '--output', str(image)],
                                        env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                processes.append(sink)
                def ports():
                    outputs = subprocess.check_output(['pw-link','-o'], env=environment, text=True, timeout=5)
                    inputs = subprocess.check_output(['pw-link','-i'], env=environment, text=True, timeout=5)
                    left = next((p.strip() for p in outputs.splitlines() if p.strip().startswith(name + ':')), None)
                    right = next((p.strip() for p in inputs.splitlines() if p.strip().startswith(capture_name + ':')), None)
                    return (left,right) if left and right else None
                left,right = wait_for(ports, 'camera/capture ports not published')
                subprocess.run(['pw-link',left,right], env=environment, check=True, timeout=5)
                output,errors = sink.communicate(timeout=14)
                if sink.returncode: raise RuntimeError(f'capture failed: {output}\n{errors}')
                header = f'P6\n{width} {height}\n255\n'.encode()
                blob = image.read_bytes();assert blob.startswith(header)
                pixels = blob[len(header):];assert len(pixels) == width*height*3
                sequence = pixels[2]
                for y in (0,height//2,height-1):
                    for x in (0,width//2,width-1):
                        offset = (y*width+x)*3
                        assert pixels[offset:offset+3] == bytes(((x+sequence)&255,y&255,sequence))
                print('PASS RGB source -> independent PipeWire capture + pixel/stride verification:', output.strip(), flush=True)
                source.terminate();assert source.wait(timeout=5) == 0
                wait_for(lambda: not has_node(name), 'camera node leaked after shutdown')
            # Disconnecting the server must stop the producer, rather than leave a live orphan.
            source = subprocess.Popen([str(build / 'tusb-camera'), '--test-pattern', '--name', 'tusb.camera.disconnect'],
                                      env=environment, stdout=camera_log, stderr=camera_log)
            processes.append(source)
            wait_for(lambda: has_node('tusb.camera.disconnect'), 'disconnect-test source did not connect')
            server.terminate();server.wait(timeout=5)
            assert source.wait(timeout=5) != 0
            print('PASS source/node cleanup and PipeWire server disconnect exits producer', flush=True)
        except BaseException:
            server_log.seek(0);camera_log.seek(0)
            print(server_log.read(),camera_log.read())
            raise
        finally:
            for process in reversed(processes):
                if process.poll() is None: process.terminate()
                process.wait(timeout=5)
print('TUSB_PIPEWIRE_CAMERA_PASS', flush=True)
