#!/usr/bin/env python3
"""Replace exactly two joy.cpl members; compare every payload and member attribute."""
import argparse,copy,hashlib,io,json,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('original',type=Path);p.add_argument('output',type=Path);p.add_argument('i386',type=Path);p.add_argument('x86_64',type=Path);p.add_argument('--verify-only',action='store_true');a=p.parse_args()
assert a.original.resolve()!=a.output.resolve()
replacement={arch:path.read_bytes() for arch,path in [('i386',a.i386),('x86_64',a.x86_64)]}
seen=set();changed=[]
if not a.verify_only:
 with a.output.open('wb') as dest:
  proc=subprocess.Popen(['xz','-T2','-6','-c'],stdin=subprocess.PIPE,stdout=dest)
  try:
   with tarfile.open(a.original,'r:xz') as src,tarfile.open(fileobj=proc.stdin,mode='w|',format=tarfile.PAX_FORMAT) as target:
    for member in src:
     assert member.name not in seen;seen.add(member.name)
     data=src.extractfile(member).read() if member.isfile() else None
     parts=Path(member.name).parts
     if member.isfile() and parts[-1]=='joy.cpl':
      arch=parts[-2].removesuffix('-windows');assert arch in replacement
      data=replacement.pop(arch);member=copy.copy(member);member.size=len(data);changed.append(member.name)
     target.addfile(member,io.BytesIO(data) if data is not None else None)
   proc.stdin.close();assert proc.wait()==0
  except BaseException:
   proc.kill();proc.wait();raise
 assert not replacement and len(changed)==2
else:
 with tarfile.open(a.original) as src:
  changed=[m.name for m in src if m.isfile() and m.name.endswith('/joy.cpl')]
 assert len(changed)==2
# Reopen final compressed archives and verify all members rather than trust the writer.
with tarfile.open(a.original) as old,tarfile.open(a.output) as new:
 om=old.getmembers();nm=new.getmembers();assert len(om)==len(nm)
 for before,after in zip(om,nm):
  b=before.get_info();n=after.get_info()
  # Header checksum changes when tar headers are serialized; it is not file metadata.
  b.pop('chksum');n.pop('chksum')
  if before.name in changed:b['size']=n['size']
  assert b==n,(before.name,'metadata')
  # PAX serializes long paths differently from GNU tar; effective names already match.
  bp={k:v for k,v in before.pax_headers.items() if k not in ('path','linkpath')}
  np={k:v for k,v in after.pax_headers.items() if k not in ('path','linkpath')}
  assert bp==np,(before.name,'pax')
  if before.isfile():
   prior=old.extractfile(before).read();current=new.extractfile(after).read()
   if before.name not in changed:assert prior==current,before.name
   else:
    assert prior!=current
    arch=Path(before.name).parts[-2].removesuffix('-windows')
    expected=a.i386 if arch=='i386' else a.x86_64
    assert current==expected.read_bytes(),before.name
report={'passed':True,'original':str(a.original),'output':str(a.output),'members':len(om),'changedOnly':changed,'bytes':a.output.stat().st_size,'sha256':hashlib.sha256(a.output.read_bytes()).hexdigest()}
a.output.with_suffix('.verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
