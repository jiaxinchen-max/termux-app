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
versions = {'proot': '5.1.107', 'proot-distro': '5.10.3'}
for name in ('DEBS', 'XLORIE_DEBS'):
    for line in block(name, '"').splitlines():
        if not line.strip(): continue
        filename, digest = line.split()
        package, version, abi = filename.split('_')
        manifests[filename] = digest
        versions[package] = version
files = [line.split() for line in block('HOST_FILES', "'").splitlines() if line.strip()]
mock = '#!' + sys.executable + '\n' + r'''
import importlib.metadata, json, os, pathlib, re, sys, types
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
elif cmd == 'dpkg':
    if args[0] == '--compare-versions':
        def version(v): return tuple(int(x) for x in re.findall('[0-9]+', v))
        left, op, right = args[1:]
        sys.exit(0 if (version(left) >= version(right) if op == 'ge' else version(left) < version(right)) else 1)
    if args[0] == '--verify' and args[1] in state.get('corrupt',[]): print('??5?????? changed')
elif cmd == 'sha256sum':
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
        for command in ('uname','dpkg-query','dpkg','sha256sum','proot','proot-distro','python3','curl','apt','pkg','apt-mark'):
            if command in data.get('missing_binaries', []): continue
            target = case/'usr/bin'/command
            target.write_text(mock)
            target.chmod(0o700)
        for package, relative, digest in files:
            file = case/'usr'/relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.touch()
        for asset in ('desktop_install.sh','desktop_common.sh'):
            shutil.copy(assets/asset, case/asset)
        spec = dict(DESKTOP_MODE='proot', DESKTOP_DE=de, DESKTOP_GL_MODE='system',
                    DESKTOP_LOG_FILE=str(case/'install.log'), DESKTOP_PID_FILE=str(case/'state/pid'),
                    DESKTOP_STATUS_FILE=str(case/'state/status'), DESKTOP_RELEASE_TAG='termux-hwaccel-6.6-r27')
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
    case, calls = run('satisfied')
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    case, calls = run('xfce-satisfied', de='xfce4')
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
    assert 'apt-mark hold xorg-server' in calls
    case, calls = run('dpkg-revision-satisfied', lambda data,case: data['versions'].update({'proot-distro':'5.10.3-2'}))
    assert not re.search(r'^(pkg|curl|apt) ', calls, re.M), calls
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
    assert len(re.findall(r'^curl ',calls,re.M)) == 1 and 'kwin_6.6.0-27_aarch64.deb' in calls
    def mismatch(data, case): data['versions']['kwin'] = '6.6.0-26'
    case, calls = run('version-mismatch', mismatch)
    assert len(re.findall(r'^curl ',calls,re.M)) == 1
    def altered(data, case): data['bad_hash'] = [str(case/'usr/bin/kwin_wayland')]
    case, calls = run('altered-elf', altered)
    assert len(re.findall(r'^curl ',calls,re.M)) == 1 and '--reinstall' in calls
    def damaged(data, case): data['corrupt'] = ['qt6-qtbase']
    case, calls = run('dpkg-verify-damage', damaged)
    assert len(re.findall(r'^curl ',calls,re.M)) == 1 and 'qt6-qtbase_6.12.0-1+termuxkwin1_aarch64.deb' in calls
    def old(data, case): data['versions']['proot-distro'] = '4.9.0'
    case, calls = run('old-layout-upgrade', old)
    assert 'apt install' in calls and ' proot-distro\n' in calls
    def persist_old(data, case): old(data,case); data['keep_old'] = True
    run('old-layout-remains', persist_old, code=1)
    def apt_fail(data, case): missing(data,case); data['apt_failure'] = 100
    run('apt-failure', apt_fail, code=100)
    for key, value in [('DESKTOP_MODE','native'),('DESKTOP_MODE','unknown'),('DESKTOP_GL_MODE','auto'),('DESKTOP_GL_MODE','unknown'),('DESKTOP_DE','hyprland')]:
        case, calls = run('invalid-'+key+'-'+value, lambda data,case,k=key,v=value: data.update(spec={k:v}), code=64)
        assert not re.search(r'^(pkg|curl|apt) ',calls,re.M)
    run('wrong-abi', lambda data,case: data.update(arch='x86_64'), code=1)
    run('missing-operation', code=64, operation=None, flags=False)
    run('unknown-operation', code=64, operation='unknown', flags=False)
    def existing(data,case): (case/'result').write_text('stale-success'); data['existing_result']=True
    case, calls = run('existing-marker', existing, code=64)
    assert (case/'result').read_text() == 'stale-success'
    print('PASS: '+str(count)+' restore prerequisite fixtures')
PY
