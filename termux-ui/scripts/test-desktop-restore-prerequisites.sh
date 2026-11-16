#!/bin/sh
# Host-only restore preflight fixtures: no package manager, network, container or device access.
set -eu
PROJECT_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
python3 - "$PROJECT_ROOT" <<'PY'
import json, os, pathlib, re, shutil, subprocess, sys, tempfile
root = pathlib.Path(sys.argv[1])
assets = root / 'termux-ui/src/main/assets/local-games'
source = (assets / 'desktop_install.sh').read_text()
def block(name, quote):
    return re.search(r'^' + name + '=' + quote + r'(.*?)' + quote, source, re.M | re.S).group(1)
manifests = {}
versions = {'proot': '5.1.107', 'proot-distro': '5.10.3',
            **{p: '1.0' for p in ('python', 'dbus', 'dconf', 'gsettings-desktop-schemas', 'librsvg')}}
for name in ('DEBS', 'XLORIE_DEBS', 'GNOME_RUNTIME_DEBS', 'GNOME_SHELL_SUPPORT_DEBS'):
    for line in block(name, '"').splitlines():
        if not line.strip(): continue
        filename, digest = line.split()
        package, version, abi = filename.split('_')
        manifests[filename] = digest
        versions[package] = version
files = [line.split() for line in block('HOST_FILES', "'").splitlines() if line.strip()]
mock = '#!' + sys.executable + '\n' + r'''
import json, os, pathlib, re, sys, types
base = pathlib.Path(os.environ['FIXTURE'])
state = json.loads((base/'state.json').read_text())
cmd = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with (base/'calls').open('a') as out: out.write(cmd+' '+' '.join(args)+'\n')
def save(): (base/'state.json').write_text(json.dumps(state))
if cmd == 'uname': print(state.get('arch','aarch64'))
elif cmd == 'dpkg-query':
    package = args[-1]
    if package not in state['versions']: sys.exit(1)
    print('install ok installed' if args[1] == '-f=${Status}' else state['versions'][package], end='')
elif cmd == 'dpkg-deb':
    package = pathlib.Path(args[1]).stem
    print(package if args[2] == 'Package' else '0.60.0-1+embedded.1')
elif cmd == 'apt-mark':
    if state.get('hold_failure'): sys.exit(42)
elif cmd == 'dpkg':
    if args[0] == '-i':
        if state.get('registration_failure'): sys.exit(43)
        if not state.get('registration_ineffective'):
            state['versions'][pathlib.Path(args[1]).stem] = '0.60.0-1+embedded.1'; save()
    if args[0] == '--audit' and state.get('audit_failure'): print('not configured')

    if args[0] == '--compare-versions':
        def version(v): return tuple(int(x) for x in re.findall('[0-9]+', v))
        left, op, right = args[1:]
        sys.exit(0 if (version(left) >= version(right) if op == 'ge' else version(left) < version(right)) else 1)
    if args[0] == '--verify' and args[1] in state.get('corrupt',[]): print('??5?????? changed')
elif cmd == 'sha256sum':
    if args == ['-c', '-']:
        for line in sys.stdin:
            expected, path = line.split()
            valid = state['manifests'].get(pathlib.Path(path).name) == expected
            if state.get('download_bad_hash'): valid = False
            if not valid: sys.exit(1)
        sys.exit(0)
    path = pathlib.Path(args[0])
    digest = state['manifests'].get(path.name)
    if digest is None: digest = next((d for p, f, d in state['files'] if str(path) == str(base/'usr'/f)), None)
    if digest is None: sys.exit(1)
    if str(path) in state.get('bad_hash',[]): digest = '0'*64
    print(digest+'  '+str(path))
elif cmd == 'proot-distro':
    # The real 5.10.3 CLI does not implement --version, and may install packages
    # before parsing help. No host prerequisite path may enter it.
    sys.exit(91)
elif cmd == 'python3':
    # The host preflight also derives trusted launch scripts; its transformation is
    # exercised by test_desktop_restore_local.py, not this package-manager fixture.
    if len(args) == 4 and args[:2] == ['-I','-B'] and pathlib.Path(args[2]).name == 'desktop_initialize.py' and args[3] == 'prepare-plasma':
        sys.exit(0)
    import importlib.metadata
    assert len(args) == 5 and args[:3] == ['-I','-B','-c'], args
    assert os.environ['PREFIX'] == str(base/'usr')
    assert args[4] == state['versions']['proot-distro'].split('-')[0]
    constants = types.ModuleType('proot_distro.constants')
    constants.PROGRAM_VERSION = state.get('program_version', args[4])
    constants.IS_TERMUX = state.get('is_termux', True)
    constants.CONTAINERS_DIR = state.get('containers_dir', str(base/'usr/var/lib/proot-distro/containers'))
    parser = types.ModuleType('proot_distro.parser')
    class Parser:
        def parse_known_args(self, argv):
            assert argv == ['install','-n','desktop-archive-probe','debian:trixie'], argv
            result = types.SimpleNamespace(command='install', custom_container_name='desktop-archive-probe', image_ref='debian:trixie')
            for key, value in state.get('parser_fields', {}).items(): setattr(result, key, value)
            return result, state.get('parser_unknown', [])
    parser.build_parser = Parser
    sys.modules['proot_distro'] = types.ModuleType('proot_distro')
    sys.modules['proot_distro.constants'] = constants
    sys.modules['proot_distro.parser'] = parser
    importlib.metadata.version = lambda package: state.get('metadata_version', args[4])
    sys.argv = ['-c', args[4]]
    exec(compile(args[3], '<actual-prerequisite-probe>', 'exec'))
elif cmd == 'curl': pathlib.Path(args[args.index('-o')+1]).write_text('fixture-deb')
elif cmd == 'apt':
    if state.get('apt_failure'): sys.exit(state['apt_failure'])
    for arg in args:
        if arg.endswith('.deb'):
            package, version, abi = pathlib.Path(arg).name.split('_')
            state['versions'][package] = version
        elif arg in ('python','dbus','dconf','gsettings-desktop-schemas','librsvg'):
            if arg in state.get('keep_missing_standard', []): continue
            state['versions'][arg] = '1.0'
            for binary in {'python':['python3'], 'dbus':['dbus-daemon','dbus-run-session']}.get(arg, []):
                target = base/'usr/bin'/binary
                target.write_text(pathlib.Path(sys.argv[0]).read_text()); target.chmod(0o700)
        elif arg == 'mesa-demos' and not state.get('keep_missing_eglinfo'):
            target = base/'usr/bin/eglinfo'
            target.write_text(pathlib.Path(sys.argv[0]).read_text()); target.chmod(0o700)
        elif arg in ('proot','proot-distro') and not state.get('keep_old'):
            state['versions'][arg] = '5.1.107' if arg == 'proot' else '5.10.3'
            if arg == 'proot-distro':
                if not state.get('keep_bad_probe'):
                    for key in ('metadata_version','program_version','is_termux','containers_dir','parser_fields','parser_unknown'):
                        state.pop(key, None)
                target = base/'usr/bin/python3'
                if not target.exists() and not state.get('keep_missing_python'):
                    target.write_text(pathlib.Path(sys.argv[0]).read_text()); target.chmod(0o700)
            if arg == 'proot':
                target = base/'usr/bin/proot'
                if not target.exists() and not state.get('keep_missing_proot'):
                    target.write_text(pathlib.Path(sys.argv[0]).read_text()); target.chmod(0o700)
    state['bad_hash'] = []; state['corrupt'] = []; save()
'''
with tempfile.TemporaryDirectory(prefix='desktop-prereq-', dir='/tmp') as temporary:
    base = pathlib.Path(temporary)
    count = 0
    def run(name, mutate=None, code=0, flags=True, operation='host-prerequisites', de='kwin_plasma'):
        global count
        count += 1
        case = base/name
        (case/'usr/bin').mkdir(parents=True)
        (case/'usr/tmp').mkdir()
        (case/'state').mkdir()
        (case/'calls').write_text('')
        data = {'versions':dict(versions), 'files':files, 'manifests':manifests}
        if mutate: mutate(data, case)
        (case/'state.json').write_text(json.dumps(data))
        for command in ('uname','dpkg-query','dpkg','dpkg-deb','sha256sum','proot','proot-distro','python3','curl','apt','pkg','apt-mark','pulseaudio','pactl','eglinfo','dbus-daemon','dbus-run-session'):
            if command in data.get('missing_binaries', []): continue
            target = case/'usr/bin'/command
            target.write_text(mock)
            target.chmod(0o600 if command in data.get('non_executable_binaries', []) else 0o700)
        for package, relative, digest in files:
            file = case/'usr'/relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.touch()
        embedded = case/'usr/share/embedded-api'
        embedded.mkdir(parents=True, exist_ok=True)
        for package in ('termux-api', 'upower'):
            if package not in data.get('missing_embedded', []): (embedded/(package+'.deb')).touch()
        for asset in ('desktop_install.sh','desktop_common.sh','desktop_rendering.sh','desktop_gnome_launch.py',
                      'desktop_usb_storage.py','desktop_ubuntu_gnome.py','desktop_install_glycin.py','desktop_install_gio.py'):
            shutil.copy(assets/asset, case/asset)
        spec = dict(DESKTOP_MODE='proot', DESKTOP_DE=de,
                    DESKTOP_SPEC_VERSION='3', DESKTOP_HOST_GL_MODE='llvmpipe' if de=='kwin_plasma' else 'system',
                    DESKTOP_GUEST_GL_MODE='llvmpipe' if de=='kwin_plasma' else 'default', DESKTOP_USB_STORAGE='off',
                    DESKTOP_LOG_FILE=str(case/'install.log'), DESKTOP_PID_FILE=str(case/'state/pid'),
                    DESKTOP_STATUS_FILE=str(case/'state/status'), DESKTOP_RELEASE_TAG='termux-hwaccel-6.7-r4')
        if de == 'ubuntu_gnome':
            spec.update(DESKTOP_CONTAINER_BASE='ubuntu:resolute', DESKTOP_CONTAINER_CODENAME='resolute',
                        DESKTOP_GUEST_GL_MODE='llvmpipe', DESKTOP_REFRESH_RATE='60',
                        DESKTOP_GNOME_SESSION_PROFILE='ubuntu', DESKTOP_GNOME_BACKEND='android',
                        DESKTOP_GNOME_RENDERER='system', DESKTOP_GNOME_SESSION_STARTUP='direct',
                        DESKTOP_GNOME_APPLICATION_RENDERER='llvmpipe', DESKTOP_GNOME_DISPLAY_GL_MODE='',
                        DESKTOP_GNOME_DISPLAY_BUFFER_MODE='')
        spec.update(data.get('spec', {}))
        (case/'spec').write_text(''.join(k+'='+v+'\n' for k,v in spec.items()))
        (case/'state/status').write_text('running\n')
        command = ['sh',str(case/'desktop_install.sh'),str(case/'spec')]
        if operation: command += ['--operation='+operation]
        if flags: command += ['--task-id='+name, '--result-file='+str(case/'result')]
        env = dict(os.environ, PREFIX=str(case/'usr'), FIXTURE=str(case), TMPDIR=str(case/'usr/tmp'))
        completed = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert completed.returncode == code, (name, completed.returncode, completed.stdout)
        assert (case/'state/status').read_text() == 'running\n', name+' mutated desktop status'
        calls = (case/'calls').read_text()
        assert not re.search(r'^proot-distro(?: |$)', calls, re.M), name+' called the proot-distro CLI'
        assert not (case/'usr/var/lib/proot-distro').exists(), name+' created a container'
        if flags and operation == 'host-prerequisites' and not data.get('existing_result'):
            result = (case/'result').read_text()
            assert 'taskId='+name+'\n' in result
            assert 'exitCode='+str(code)+'\n' in result
            assert 'state='+('succeeded' if code == 0 else 'failed')+'\n' in result
        return case, calls
    case, calls = run('fresh-api-registration')
    assert 'dpkg -i '+str(case/'usr/share/embedded-api/termux-api.deb') in calls
    assert 'apt-mark hold termux-api' in calls
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M)
    for name, changes, code in [
        ('registration-failure', {'registration_failure':True},43),
        ('registration-ineffective', {'registration_ineffective':True},1),
        ('hold-failure', {'hold_failure':True},42),
        ('audit-failure', {'audit_failure':True},1),
        ('missing-api-deb', {'missing_embedded':['termux-api']},1),
    ]:
        run(name, lambda data,case,c=changes: data.update(c), code=code)
    def upstream(data, case):
        data['versions'].update({'termux-api':'0.60.0', 'upower':'1.91.5'})
        data['missing_binaries'] = ['eglinfo']
    case, calls = run('upstream-api-and-upower', upstream)
    assert calls.index('apt install') < calls.index('dpkg -i ')
    assert 'apt-mark hold upower' in calls
    assert json.loads((case/'state.json').read_text())['versions']['termux-api'] == '0.60.0-1+embedded.1'
    case, calls = run('satisfied')
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    case, calls = run('xfce-satisfied', de='xfce4')
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    assert 'apt-mark hold xorg-server' in calls
    case, calls = run('dpkg-revision-satisfied', lambda data,case: data['versions'].update({'proot-distro':'5.10.3-2'}))
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    # Fresh hosts must have initialization diagnostics before extraction. Exercise both
    # supported archive desktops and modes, without installing any guest packages.
    for de in ('kwin_plasma', 'xfce4'):
        for mode in ('zink', 'llvmpipe'):
            def no_egl(data, case, mode=mode, de=de):
                data.update(missing_binaries=['eglinfo'], spec={
                    'DESKTOP_HOST_GL_MODE':mode,
                    'DESKTOP_GUEST_GL_MODE':'llvmpipe' if de=='kwin_plasma' else 'default'})
            case, calls = run(de+'-'+mode+'-missing-eglinfo', no_egl, de=de)
            assert re.search(r'^apt install .* mesa-demos$', calls, re.M), calls
            assert (case/'usr/bin/eglinfo').is_file()
            assert not re.search(r'^curl ', calls, re.M), calls
        def system_no_egl(data, case):
            data.update(missing_binaries=['eglinfo'], spec={'DESKTOP_HOST_GL_MODE':'system'})
        case, calls = run(de+'-system-no-eglinfo', system_no_egl, de=de)
        assert not re.search(r'^(pkg|apt|curl) ', calls, re.M), calls
    case, calls = run('eglinfo-not-executable', lambda data,case:
        data.update(non_executable_binaries=['eglinfo']))
    assert re.search(r'^apt install .* mesa-demos$', calls, re.M), calls
    case, calls = run('eglinfo-repair-ineffective', lambda data,case:
        data.update(missing_binaries=['eglinfo'], keep_missing_eglinfo=True), code=1)
    assert 'host EGL diagnostics are missing' in (case/'install.log').read_text()
    # Execute the production probe against mismatched module metadata, runtime paths,
    # and parser contracts. Reinstallation must repair each or report failure.
    for name, changes in [
        ('metadata-version', {'metadata_version':'5.10.2'}),
        ('program-version', {'program_version':'5.10.2'}),
        ('not-termux', {'is_termux':False}),
        ('wrong-container-path', {'containers_dir':'/tmp/unrelated-containers'}),
        ('parser-command', {'parser_fields':{'command':'login'}}),
        ('parser-name', {'parser_fields':{'custom_container_name':'debian'}}),
        ('parser-image', {'parser_fields':{'image_ref':'debian:forky'}}),
        ('parser-unknown', {'parser_unknown':['-n']}),
    ]:
        case, calls = run(name+'-repaired', lambda data,case,c=changes: data.update(c))
        assert re.search(r'^apt install .* proot-distro$', calls, re.M), calls
        assert not re.search(r'^curl ', calls, re.M), calls
        run(name+'-remains', lambda data,case,c=changes: data.update(c, keep_bad_probe=True), code=1)
    for name, binary, keep in [('missing-python','python3','keep_missing_python'), ('missing-proot','proot','keep_missing_proot')]:
        case, calls = run(name+'-repaired', lambda data,case,b=binary: data.update(missing_binaries=[b]))
        assert re.search(r'^apt install ', calls, re.M), calls
        assert not re.search(r'^curl ', calls, re.M), calls
        run(name+'-remains', lambda data,case,b=binary,k=keep: data.update(missing_binaries=[b], **{k:True}), code=1)
    def missing(data, case): data['versions'].pop('kwin')
    case, calls = run('missing', missing)
    assert len(re.findall(r'^curl ',calls,re.M)) == 1 and 'kwin_6.7.5-4_aarch64.deb' in calls
    def mismatch(data, case): data['versions']['kwin'] = '6.6.0-26'
    case, calls = run('version-mismatch', mismatch)
    assert len(re.findall(r'^curl ',calls,re.M)) == 1
    def altered(data, case): data['bad_hash'] = [str(case/'usr/bin/kwin_wayland')]
    case, calls = run('altered-elf', altered)
    # Runtime digest/verification checks were removed; local changes do not request a reinstall.
    assert not re.search(r'^(curl|apt|pkg|sha256sum) ',calls,re.M), calls
    def damaged(data, case): data['corrupt'] = ['qt6-qtbase']
    case, calls = run('dpkg-verify-damage', damaged)
    assert not re.search(r'^(curl|apt|pkg|sha256sum|dpkg --verify) ',calls,re.M), calls
    def old(data, case): data['versions']['proot-distro'] = '4.9.0'
    case, calls = run('old-layout-upgrade', old)
    assert 'apt install' in calls and ' proot-distro\n' in calls
    def persist_old(data, case): old(data,case); data['keep_old'] = True
    run('old-layout-remains', persist_old, code=1)
    def apt_fail(data, case): missing(data,case); data['apt_failure'] = 100
    run('apt-failure', apt_fail, code=100)
    for key, value in [('DESKTOP_MODE','native'),('DESKTOP_MODE','unknown'),('DESKTOP_HOST_GL_MODE','auto'),('DESKTOP_GUEST_GL_MODE','unknown'),('DESKTOP_DE','hyprland')]:
        case, calls = run('invalid-'+key+'-'+value, lambda data,case,k=key,v=value: data.update(spec={k:v}), code=64)
        assert not re.search(r'^(pkg|curl|apt) ',calls,re.M)
    run('wrong-abi', lambda data,case: data.update(arch='x86_64'), code=1)
    run('missing-operation', code=64, operation=None, flags=False)
    run('unknown-operation', code=64, operation='unknown', flags=False)
    def existing(data,case): (case/'result').write_text('stale-success'); data['existing_result']=True
    case, calls = run('existing-marker', existing, code=64)
    assert (case/'result').read_text() == 'stale-success'
    case, calls = run('gnome-satisfied', de='ubuntu_gnome')
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    launcher = case/'usr/libexec/termux-gnome'
    for source, target in [('desktop_gnome_launch.py','launch.py'), ('desktop_usb_storage.py','desktop_usb_storage.py'),
                           ('desktop_ubuntu_gnome.py','desktop_ubuntu_gnome.py'), ('desktop_install_glycin.py','desktop_install_glycin.py'),
                           ('desktop_install_gio.py','desktop_install_gio.py')]:
        assert (launcher/target).read_bytes() == (assets/source).read_bytes()
    # Both GNOME release groups must be selected on a clean host; no KWin/Xlorie DEBs.
    def missing_gnome(data, case):
        for filename in manifests:
            if filename.startswith(('gjs_', 'gnome-shell_', 'mutter_', 'spidermonkey_', 'accountsservice_',
                                    'gnome-settings-daemon-schemas_', 'libgdm_', 'polkit_')):
                data['versions'].pop(filename.split('_')[0], None)
    case, calls = run('gnome-two-releases', missing_gnome, de='ubuntu_gnome')
    downloads = '\n'.join(line for line in calls.splitlines() if line.startswith('curl '))
    assert len(downloads.splitlines()) == 8, downloads
    for fragment in ('/gnome-50-resolute-20261116-r1/mutter_50.1-4_',
                     '/gnome-usb-20261030-r1/accountsservice_23.13.9_'):
        assert fragment in downloads, downloads
    assert not re.search(r'(kwin_|xorg-server_|xwayland_|qt6-)', downloads), downloads
    assert len(re.findall(r'^apt install ', calls, re.M)) == 1, calls
    def missing_standard(data, case):
        for package in ('python','dbus','dconf','gsettings-desktop-schemas','librsvg'): data['versions'].pop(package)
        data['missing_binaries'] = ['dbus-daemon','dbus-run-session']
    case, calls = run('gnome-standard-host-packages', missing_standard, de='ubuntu_gnome')
    assert re.search(r'^apt install .* python dbus dconf gsettings-desktop-schemas librsvg$', calls, re.M), calls
    assert not re.search(r'^curl ', calls, re.M), calls
    def ineffective_standard(data, case):
        missing_standard(data, case); data['keep_missing_standard'] = ['dconf']
    run('gnome-standard-ineffective', ineffective_standard, de='ubuntu_gnome', code=1)
    def failed_gnome(data, case):
        missing_gnome(data, case); data['apt_failure'] = 100
    run('gnome-apt-failure', failed_gnome, de='ubuntu_gnome', code=100)
    def corrupt_gnome_download(data, case):
        missing_gnome(data, case); data['download_bad_hash'] = True
    case, calls = run('gnome-download-corrupt', corrupt_gnome_download, de='ubuntu_gnome', code=65)
    assert not re.search(r'^apt install ', calls, re.M), calls
    print('PASS: '+str(count)+' restore prerequisite fixtures')
PY
