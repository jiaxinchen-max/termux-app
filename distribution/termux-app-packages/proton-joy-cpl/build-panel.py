#!/usr/bin/env python3
# Run under the original build environment. Never modifies the original build tree.
import sys,os,json,subprocess,hashlib,difflib
from pathlib import Path
build=Path(sys.argv[1]);out=Path(sys.argv[2]);arches=sys.argv[3:];out.mkdir(parents=True,exist_ok=True)
os.chdir(build)
makefile=(build/'Makefile').read_text()
srcdir=next(s.split(' = ',1)[1] for s in makefile.splitlines() if s.startswith('srcdir = '))
source=srcdir+'/dlls/joy.cpl/dinput.c';old=Path(source).read_text()
needle='if (SUCCEEDED(hr)) hr = IDirectInputDevice8_SetProperty( entry->device, DIPROP_AUTOCENTER, &ac_prop.diph );'
assert old.count(needle)==1
new=old.replace(needle,needle.replace('hr = ',''))
(out/'dinput-original.c').write_text(old);(out/'dinput.c').write_text(new)
(out/'source.patch').write_text(''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile=source,tofile='patched/dinput.c')))
commands=[]
for arch in arches:
    obj=f'dlls/joy.cpl/{arch}-windows/dinput.o';target=f'dlls/joy.cpl/{arch}-windows/joy.cpl'
    def getcmd(force,target):
        s=subprocess.check_output(['make','-n','-o','Makefile','-W',force,target],universal_newlines=True).replace('\\\n',' ')
        lines=[x for x in s.splitlines() if x.strip()]
        assert len(lines)==1,lines
        return lines[0]
    compile_cmd=getcmd(source,obj);link_cmd=getcmd(obj,target)
    for variant in ['baseline','fixed']:
        work=out/variant/(arch+'-windows');work.mkdir(parents=True,exist_ok=True)
        c=compile_cmd.replace(' -o '+obj+' ',' -o '+str(work/'dinput.o')+' ')
        if variant=='fixed':c=c.replace(' '+source+' ',' '+str(out/'dinput.c')+' ')
        l=link_cmd.replace(' -o '+target+' ',' -o '+str(work/'joy.cpl')+' ').replace(' '+obj+' ',' '+str(work/'dinput.o')+' ')
        for cmd in [c,l]:
            commands.append(cmd);print(cmd,flush=True);subprocess.run(cmd,shell=True,check=True)
        # Preserve original build flags and strip only debug data for the shipping PE.
        subprocess.run([arch.replace('i386','i686')+'-w64-mingw32-strip','--strip-debug',str(work/'joy.cpl')],check=True)
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
print('BUILD_COMPLETE',out,flush=True)
