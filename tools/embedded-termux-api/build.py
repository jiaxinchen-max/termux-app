#!/usr/bin/env python3
"""Build pinned Android API companion CLI assets using the same NDK as the APK."""
import argparse, pathlib, subprocess, json, hashlib, shutil, tempfile, os
p=argparse.ArgumentParser(); p.add_argument('--ndk',required=True); p.add_argument('--output',required=True); a=p.parse_args()
root=pathlib.Path(__file__).resolve().parent; src=root/'upstream-cli'; out=pathlib.Path(a.output)/'embedded-api'
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
