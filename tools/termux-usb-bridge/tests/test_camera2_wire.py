# SPDX-License-Identifier: MIT
"""Validate installed Camera2 frame transport rejects malformed/truncated input."""
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time

source = str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory(prefix='tusb-camera2-wire-',dir=os.environ.get('TMPDIR','/tmp')) as tmp:
    e=dict(os.environ,XDG_RUNTIME_DIR=tmp,PIPEWIRE_RUNTIME_DIR=tmp,PIPEWIRE_REMOTE='pipewire-0')
    with open(Path(tmp)/'pipewire.log','w') as log:
        server=subprocess.Popen(['pipewire'],env=e,stdout=log,stderr=log)
        try:
            until=time.monotonic()+5
            while not (Path(tmp)/'pipewire-0').exists():
                if server.poll() is not None or time.monotonic()>until:raise RuntimeError('PipeWire did not start')
                time.sleep(.05)
            payload=bytes((7,17,27))*(640*480)
            def header(stamp=1_000_000_000,seq=1,length=len(payload)):
                return struct.pack('<4sIIIQQ',b'TCF1',640,480,length,stamp,seq)
            cases={
                'empty':(b'',1),
                'truncated-header':(b'TCF1',1),
                'bad-magic':(bytes(32),1),
                'oversized-length':(header(length=0xffffffff),1),
                'truncated-payload':(header()+payload[:500],1),
                'nonmonotonic-timestamp':(header()+payload+header(seq=2),1),
                'nonmonotonic-sequence':(header()+payload+header(stamp=2_000_000_000),1),
                'complete-frame-eof':(header()+payload,0),
            }
            for label,(data,expected) in cases.items():
                name='tusb.camera2.wire.'+label
                result=subprocess.run([source,'--name',name],input=data,env=e,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=8)
                assert result.returncode==expected,(label,result.returncode,result.stderr.decode())
                nodes=json.loads(subprocess.check_output(['pw-dump'],env=e,text=True,timeout=5))
                assert not any(n.get('info',{}).get('props',{}).get('node.name')==name for n in nodes)
                print('PASS framed RGB transport:',label,flush=True)
        finally:
            server.terminate()
            server.wait(timeout=5)
print('TUSB_CAMERA2_WIRE_PASS',flush=True)
