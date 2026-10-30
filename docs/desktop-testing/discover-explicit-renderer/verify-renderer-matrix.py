from pathlib import Path
import subprocess,json,hashlib
b=Path('/home/builder/desktop-fixes-20261030/discover')
v='6.7.4-3+termuxfix2'
deb=b/'rootfs/root/build'/f'plasma-discover_{v}_arm64.deb'
dest=b/'rootfs/root/build/discover-fix2-verify'
subprocess.run(['dpkg-deb','-x',str(deb),str(dest)],check=True)
common=b/'rootfs/root/build'/f'plasma-discover-common_{v}_all.deb'
subprocess.run(['dpkg-deb','-x',str(common),str(dest)],check=True)
exe='/root/build/discover-fix2-verify/usr/bin/plasma-discover'
lib='/root/build/discover-fix2-verify/usr/lib/aarch64-linux-gnu/plasma-discover'
cases=[
 ('opengl',{'QSG_RHI_BACKEND':'opengl'},0,'Discover explicit renderer: opengl ; embedded web authentication: available'),
 ('vulkan',{'QSG_RHI_BACKEND':'vulkan'},0,'Discover explicit renderer: vulkan ; embedded web authentication: unavailable'),
 ('software',{'QT_QUICK_BACKEND':'software'},0,'Discover explicit renderer: software ; embedded web authentication: unavailable'),
 ('missing',{},64,None),
 ('unknown-rhi',{'QSG_RHI_BACKEND':'invalid'},64,None),
 ('unknown-quick',{'QT_QUICK_BACKEND':'invalid'},64,None),
 ('conflicting',{'QSG_RHI_BACKEND':'vulkan','QT_QUICK_BACKEND':'software'},64,None),
 ('unsupported-rhi-software',{'QSG_RHI_BACKEND':'software'},64,None),
 ('case-sensitive',{'QSG_RHI_BACKEND':'Vulkan'},64,None),
]
results=[]
for label,environment,code,marker in cases:
 args=[str(b/'run-arm64'),'/usr/bin/env',f'LD_LIBRARY_PATH={lib}']+[k+'='+x for k,x in environment.items()]+[exe,'--version']
 r=subprocess.run(args,text=True,capture_output=True,timeout=60)
 print('CASE',label,'exit',r.returncode,flush=True)
 print(r.stdout,end='');print(r.stderr,end='',flush=True)
 assert r.returncode==code,(label,code,r.returncode)
 if marker: assert marker in r.stderr,(label,marker)
 else: assert 'requires exactly one explicit renderer' in r.stderr
 assert 'QRhiGles2' not in r.stderr and 'Failed to initialize graphics backend' not in r.stderr
 results.append({'case':label,'environment':environment,'expected_exit':code,'actual_exit':r.returncode})
(b/'fix2/renderer-matrix.json').write_text(json.dumps(results,indent=2)+'\n')
print('PASS: actual final-deb binary 9-case explicit renderer matrix')
print('binary_sha256',hashlib.sha256((dest/'usr/bin/plasma-discover').read_bytes()).hexdigest())
