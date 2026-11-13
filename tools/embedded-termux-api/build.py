#!/usr/bin/env python3
"""Build pinned Android API companion CLI assets using the same NDK as the APK."""
import argparse, pathlib, subprocess, json, hashlib, shutil, tempfile, os, zipfile, io, sys
p=argparse.ArgumentParser(); p.add_argument('--ndk',required=True); p.add_argument('--output',required=True); p.add_argument('--write-catalog',action='store_true'); a=p.parse_args()
root=pathlib.Path(__file__).resolve().parent; src=root/'upstream-cli'; out=pathlib.Path(a.output)/'embedded-api'
if sys.platform != 'linux': raise SystemExit('API companion packages must be built on Linux')
if out.exists(): shutil.rmtree(out)
prefix='/data/data/com.termux/files/usr'
for abi,target in [('arm64-v8a','aarch64-linux-android21'),('armeabi-v7a','armv7a-linux-androideabi21'),('x86','i686-linux-android21'),('x86_64','x86_64-linux-android21')]:
    dest=out/abi; dest.mkdir(parents=True,exist_ok=True)
    cc=pathlib.Path(a.ndk)/'toolchains/llvm/prebuilt/linux-x86_64/bin'/f'{target}-clang'
    subprocess.run([str(cc),'-Os','-fPIE','-pie','-pthread','-Wl,--build-id=sha1',str(src/'termux-api.c'),str(src/'termux-api-broadcast.c'),'-o',str(dest/'termux-api')],check=True)
scripts=out/'bin'; scripts.mkdir(parents=True,exist_ok=True)
for file in (src/'scripts').glob('*.in'):
    (scripts/file.stem).write_text(file.read_text().replace('@TERMUX_PREFIX@',prefix))
(out/'termux-callback').write_text((src/'termux-callback.in').read_text().replace('@TERMUX_PREFIX@',prefix))
(out/'revision').write_text('fc26ce17e3badf85d4df191af364fcf7798047f1+9e7f1531e1aa4a9c1e261a2dbde4de93653c366e+embedded-v1\n')
for abi,arch in [('arm64-v8a','aarch64'),('armeabi-v7a','arm'),('x86','i686'),('x86_64','x86_64')]:
    with tempfile.TemporaryDirectory() as temporary:
        package=pathlib.Path(temporary)
        (package/'DEBIAN').mkdir()
        (package/'DEBIAN/control').write_text(f'Package: termux-api\nVersion: 0.60.0-1+embedded.1\nArchitecture: {arch}\nMaintainer: Termux Android embedded API\nDescription: CLI for the API embedded in the main Termux APK\nDepends: bash, termux-am\n')
        dest=package/prefix.lstrip('/'); (dest/'libexec').mkdir(parents=True);shutil.copytree(scripts,dest/'bin')
        shutil.copy2(out/abi/'termux-api',dest/'libexec/termux-api');shutil.copy2(out/'termux-callback',dest/'libexec/termux-callback')
        for f in dest.rglob('*'):
            if f.is_file(): f.chmod(0o755)
        subprocess.run(['dpkg-deb','--root-owner-group','--build',str(package),str(out/abi/'termux-api.deb')],check=True,env=dict(os.environ,SOURCE_DATE_EPOCH='0'))
(out/'manifest.json').write_text(json.dumps({str(f.relative_to(out)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(out.rglob('*')) if f.is_file()},sort_keys=True,indent=2)+'\n')

# Release payloads are build outputs, never Android asset inputs.
release='embedded-termux-api-0.60.0-r2'
project=root.parents[1]
base_url='https://github.com/jiaxinchen-max/termux-app-packages/releases/download/'+release
catalog={'schema':1,'contract':'embedded-v1','release':release,'components':{}}
sha=lambda b: hashlib.sha256(b).hexdigest()
for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
    files={str(f.relative_to(out)):f.read_bytes() for f in sorted((out/'bin').iterdir()) if f.is_file()}
    files['libexec/termux-api']=(out/abi/'termux-api').read_bytes()
    files['libexec/termux-callback']=(out/'termux-callback').read_bytes()
    files['share/embedded-api/termux-api.deb']=(out/abi/'termux-api.deb').read_bytes()
    if abi=='arm64-v8a':
        component=(project/'distribution/embedded-termux-api/upower_1.91.5-1_aarch64.deb').read_bytes()
        if sha(component)!='e0198daeabe6e8e5013c9d393340118f27c583c8a68cdbc2f774e2b4d3b478f3': raise SystemExit('UPower hash mismatch')
        files['share/embedded-api/upower.deb']=component
    payload=io.BytesIO()
    with zipfile.ZipFile(payload,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0)); info.create_system=3
            info.external_attr=((0o100755 if name.startswith(('bin/','libexec/')) else 0o100644)<<16)
            info.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(info,data,compresslevel=9)
    name='termux-api-companion-'+abi+'-embedded-v1.zip'; data=payload.getvalue()
    (pathlib.Path(a.output)/name).write_bytes(data)
    catalog['components'][abi]={'abi':abi,'contract':'embedded-v1','url':base_url+'/'+name,'asset':name,'size':len(data),'sha256':sha(data),
        'files':{n:{'sha256':sha(b),'size':len(b),'executable':n.startswith(('bin/','libexec/'))} for n,b in sorted(files.items())}}
encoded=(json.dumps(catalog,sort_keys=True,indent=2)+'\n').encode()
(pathlib.Path(a.output)/'runtime-api-components.json').write_bytes(encoded)
target=project/'termux-api/src/main/assets/runtime-api-components.json'
if a.write_catalog:
    target.parent.mkdir(parents=True,exist_ok=True); target.write_bytes(encoded)
elif not target.is_file() or target.read_bytes()!=encoded:
    raise SystemExit('API component catalog differs from Linux build; regenerate with --write-catalog and publish matching assets')
print('Verified reproducible API companion catalog; payloads remain outside APK assets')
