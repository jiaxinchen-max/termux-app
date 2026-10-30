# SPDX-License-Identifier: MIT
"""Supervise isolated Android Camera2 capture and native PipeWire publication."""
import argparse
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--libexec', type=Path, required=True, help=argparse.SUPPRESS)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('list', help='enumerate Android cameras as JSON')
    serve = commands.add_parser('serve', help='publish an Android camera on the current PipeWire server')
    serve.add_argument('--camera', required=True, help='camera ID returned by list')
    serve.add_argument('--width', type=int, default=640)
    serve.add_argument('--height', type=int, default=480)
    serve.add_argument('--fps', type=int, default=30)
    serve.add_argument('--name', help='PipeWire node name')
    serve.add_argument('--seconds', type=int, default=0, help='stop after this duration; 0 means until interrupted')
    args = parser.parse_args()
    environment = dict(os.environ, LD_LIBRARY_PATH='/system/lib64' if sys.maxsize>2**32 else '/system/lib')
    environment.pop('LD_PRELOAD', None)
    capture = args.libexec/'tusb-camera2-capture'
    if args.command == 'list':
        return subprocess.run([str(capture),'--list'],env=environment,timeout=15).returncode
    if not (0<args.width<=4096 and 0<args.height<=2160 and args.width%2==args.height%2==0 and
            0<args.fps<=240 and 0<=args.seconds<=86400):
        parser.error('dimensions must be even and <=4096x2160, fps 1..240, seconds 0..86400')
    if not args.camera or len(args.camera)>256 or '\0' in args.camera:
        parser.error('invalid camera ID')
    name = args.name or 'tusb.camera2.'+re.sub(r'[^a-zA-Z0-9_.-]','_',args.camera)
    if not name or len(name)>256:
        parser.error('node name must contain 1..256 characters')
    common = ['--width',str(args.width),'--height',str(args.height),'--fps',str(args.fps)]
    command = [str(capture),'--camera',args.camera,*common]
    if args.seconds:
        command += ['--seconds',str(args.seconds)]
    stopped = False
    def stop(_number,_frame):
        nonlocal stopped
        stopped = True
    for number in (signal.SIGINT,signal.SIGTERM):
        signal.signal(number,stop)
    producer = publisher = None
    try:
        producer = subprocess.Popen(command,env=environment,stdout=subprocess.PIPE)
        publisher = subprocess.Popen([str(args.libexec/'tusb-camera2-source'),*common,'--name',name,
                                      '--description','Android Camera2 '+args.camera],stdin=producer.stdout)
        producer.stdout.close()
        while not stopped and producer.poll() is None and publisher.poll() is None:
            time.sleep(.05)
        if stopped or publisher.poll() is not None:
            if producer.poll() is None:
                producer.terminate()
        try:
            producer.wait(timeout=7)
        except subprocess.TimeoutExpired:
            producer.kill()
            producer.wait()
        try:
            publisher.wait(timeout=4)
        except subprocess.TimeoutExpired:
            publisher.terminate()
            publisher.wait(timeout=3)
        if stopped:
            return 0
        return 0 if producer.returncode==publisher.returncode==0 else 1
    finally:
        for child in (producer,publisher):
            if child is not None and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError,subprocess.SubprocessError) as error:
        sys.exit(str(error))
