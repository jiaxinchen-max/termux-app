#!/usr/bin/env python3
from pathlib import Path
import subprocess,struct,hashlib,json,re,sys
r=Path(sys.argv[1]);results=[]
for backend,arch,d in [('native','x86_64','native64'),('native','i386','native32'),('proot','x86_64','proot'),('proot','i386','proot')]:
 paths=[r/f'{backend}-original-{arch}-windows.cpl',r/d/'baseline'/f'{arch}-windows/joy.cpl',r/d/'fixed'/f'{arch}-windows/joy.cpl']
 def section(p,name):
  data=p.read_bytes();pe=struct.unpack_from('<I',data,0x3c)[0]
  count=struct.unpack_from('<H',data,pe+6)[0];optional=struct.unpack_from('<H',data,pe+20)[0]
  for index in range(count):
   header=pe+24+optional+40*index
   if data[header:header+8].rstrip(b'\0').decode()==name:
    size,offset=struct.unpack_from('<II',data,header+16);return data[offset:offset+size]
  raise AssertionError(name)

 for name in ['.text','.rdata','.rsrc']:assert section(paths[0],name)==section(paths[1],name),(backend,arch,name)
 assert section(paths[0],'.rsrc')==section(paths[2],'.rsrc')
 imports=[]
 for p in paths:
  b=p.read_bytes();assert b[:2]==b'MZ';assert b[64:80]==b'Wine builtin DLL';offset=struct.unpack_from('<I',b,0x3c)[0];assert b[offset:offset+4]==b'PE\0\0'
  assert struct.unpack_from('<H',b,offset+4)[0]==({'i386':0x14c,'x86_64':0x8664}[arch])
  h=subprocess.check_output(['objdump','-p',str(p)],text=True);imports.append(re.findall(r'DLL Name: (\S+)',h))
 assert imports[0]==imports[1]==imports[2]
 # Save the DirectInput callback object disassembly, separate from other same-name callbacks.
 for variant in ['baseline','fixed']:
  b=subprocess.check_output(['objdump','-d',str(r/d/variant/f'{arch}-windows/dinput.o')],text=True)
  match=re.search(r'^[0-9a-f]+ <_?enum_devices(?:@8)?>:\n(.*?)(?=\n\n|\Z)',b,re.M|re.S);assert match
  (r/f'{backend}-{arch}-{variant}-enum.asm').write_text(match[0])
 results.append({'backend':backend,'arch':arch,'baselineMatchesOriginalCodeDataResources':True,'importsUnchanged':imports[0],'resourcesUnchanged':True,'fixedSha256':hashlib.sha256(paths[2].read_bytes()).hexdigest()})
(r/'panel-verification.json').write_text(json.dumps({'passed':True,'panels':results},indent=2)+'\n')
print('PASS: four PE panels, baseline code/data/resources, architecture, imports, resources')
