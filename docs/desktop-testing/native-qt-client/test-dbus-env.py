#!/usr/bin/env python3
"""Exercise real dbus activation environment updates, without a desktop/GPU."""
import json,os,pathlib,subprocess,sys,tempfile
if len(sys.argv) != 3 or sys.argv[2] not in ('v2', 'owned'):
    raise SystemExit('usage: test-dbus-env.py helper v2|owned')
helper=pathlib.Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='kwin-dbus-env-') as temporary:
    root=pathlib.Path(temporary);prefix=root/'prefix'
    for name in ['lib/qt6/termux-system-egl/libEGL.so.1','lib/qt6/plugins/wayland-graphics-integration-client/libqt-plugin-termux-ahardware-buffer.so','share/glvnd/egl_vendor.d/50_mesa.json','share/vulkan/icd.d/fake.json']:
        path=prefix/name;path.parent.mkdir(parents=True,exist_ok=True);path.touch()
    services=root/'data/dbus-1/services';services.mkdir(parents=True)
    # This service intentionally captures the daemon-provided environment and
    # exits before acquiring its name. ChildExited is expected, not a retry.
    dump=root/'capture.py'
    dump.write_text('import json,os,sys\nkeys=["KWIN_ANDROID_GL_MODE","MESA_LOADER_DRIVER_OVERRIDE","GALLIUM_DRIVER","LIBGL_ALWAYS_SOFTWARE","QT_QUICK_BACKEND","VK_DRIVER_FILES","WRAPPER_VULKAN_PATH","MESA_GL_VERSION_OVERRIDE"]\njson.dump({key:os.environ.get(key) for key in keys},open(sys.argv[1],"w"))\n')
    for mode in ['zink', 'system', 'llvmpipe']:
        name='org.termux.RendererEnvironmentTest.'+mode
        (services/(name+'.service')).write_text('[D-BUS Service]\nName='+name+'\nExec=/usr/bin/python3 '+str(dump)+' '+str(root/('captured-'+mode+'.json'))+'\n')
    check=root/'check.py'
    check.write_text('''import json,sys
mode=sys.argv[1]; data=json.load(open(sys.argv[2])); expected={
 'system': {'MESA_LOADER_DRIVER_OVERRIDE':'','GALLIUM_DRIVER':'','LIBGL_ALWAYS_SOFTWARE':'0','QT_QUICK_BACKEND':'','VK_DRIVER_FILES':'','WRAPPER_VULKAN_PATH':'','MESA_GL_VERSION_OVERRIDE':''},
 'zink': {'MESA_LOADER_DRIVER_OVERRIDE':'zink','GALLIUM_DRIVER':'zink','LIBGL_ALWAYS_SOFTWARE':'0','QT_QUICK_BACKEND':''},
 'llvmpipe': {'MESA_LOADER_DRIVER_OVERRIDE':'llvmpipe','GALLIUM_DRIVER':'llvmpipe','LIBGL_ALWAYS_SOFTWARE':'1','QT_QUICK_BACKEND':'','VK_DRIVER_FILES':'','WRAPPER_VULKAN_PATH':''}}
expected['llvmpipe']['QT_QUICK_BACKEND']='software'
assert data['KWIN_ANDROID_GL_MODE']==mode,data
for key,value in expected[mode].items(): assert data[key]==value,(mode,key,data[key],value)
print(mode, 'actual D-Bus activation environment PASS',json.dumps(data))
''')
    shell=root/'session.sh'
    shell.write_text('''#!/usr/bin/env bash
set -euo pipefail
source "$1"
export PREFIX="$2"
root="$3"
for mode in zink system llvmpipe; do
    export KWIN_ANDROID_GL_MODE="$mode"
    case "$mode" in
        zink) kwin_android_configure_icd_zink "$PREFIX/share/vulkan/icd.d/fake.json" test ;;
        system) kwin_android_use_system_gles ;;
        llvmpipe) kwin_android_use_llvmpipe ;;
    esac
    kwin_android_configure_qt_clients
    # v2 lacked a shared explicit list, use its exact documented argument list
    # so the old-package negative control reaches the same real D-Bus API.
    if [ "$TEST_ENV_CONTRACT" = v2 ]; then
        KWIN_ANDROID_QT_CLIENT_ENV_VARS=(KWIN_ANDROID_GL_MODE LD_LIBRARY_PATH QT_OPENGL QT_WAYLAND_CLIENT_BUFFER_INTEGRATION QSG_RHI_BACKEND QT_QUICK_BACKEND QSG_RENDER_LOOP MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER LIBGL_ALWAYS_SOFTWARE TERMUX_ANDROID_ZINK __EGL_VENDOR_LIBRARY_FILENAMES VK_DRIVER_FILES VK_ICD_FILENAMES WRAPPER_VULKAN_PATH)
    fi
    /usr/bin/dbus-update-activation-environment "${KWIN_ANDROID_QT_CLIENT_ENV_VARS[@]}"
    rm -f "$root/captured-$mode.json"
    if /usr/bin/dbus-send --session --dest="org.termux.RendererEnvironmentTest.$mode" --print-reply --reply-timeout=1000 / org.termux.RendererEnvironmentTest.Capture > "$root/reply.txt" 2>&1; then
        echo 'capture service unexpectedly acquired a D-Bus name' >&2; exit 1
    fi
    /usr/bin/python3 "$root/check.py" "$mode" "$root/captured-$mode.json"
done
''')
    env=os.environ.copy();env.update(TEST_ENV_CONTRACT=sys.argv[2],XDG_DATA_HOME=str(root/'data'),LIBGL_ALWAYS_SOFTWARE='1',QT_QUICK_BACKEND='software',VK_DRIVER_FILES='/stale',WRAPPER_VULKAN_PATH='/stale',MESA_GL_VERSION_OVERRIDE='4.6')
    result=subprocess.run(['/usr/bin/dbus-run-session','--','/usr/bin/bash',str(shell),str(helper),str(prefix),str(root)],env=env)
    raise SystemExit(result.returncode)
