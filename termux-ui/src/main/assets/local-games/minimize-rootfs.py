#!/usr/bin/env python3
"""Incremental, reversible trimming of a private installed pdb2 RootFS candidate.

Never operates on the live tree: clone first, plan one batch, then apply that
exact plan. Removed leaves are quarantined outside the candidate for rollback.
The original input, package receipts, Wine binaries and prefix sources survive.
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time

REVISION = 'pdb2-incremental-1'
MARKER = '.games-minimize-candidate.json'
PACKAGE = '11.16~trixie+idap.pdb2'
LIVE = 'games-shared-rootfs'
BUILD_BATCHES = ('development', 'resources', 'fonts', 'build-tools', 'gpu-drivers')  # Expand only after each batch passes device validation.
TRIM_RECEIPT = 'etc/games-rootfs-trim.json'


def require(ok, message):
    if not ok:
        raise ValueError('rootfs_minimize_' + message)


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def signature(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def save(path, value):
    path = Path(path)
    require(not path.is_symlink(), 'report_symlink')
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.tmp-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def outside(root, path):
    path = path.absolute()
    require(path.parent.resolve(strict=True) == path.parent and not within(path, root)
            and not path.is_symlink(), 'report_inside_or_aliased_root')
    source = json.loads((root / MARKER).read_text())['source']
    require(source is None or not within(path, Path(source)), 'report_inside_original_input')
    return path


def within(path, directory):
    # Android /data/data and /data/user/0 may be bind aliases, not symlinks.
    return any(p.exists() and os.path.samefile(p, directory) for p in (path, *path.parents))


def relative(name):
    require(isinstance(name, str) and name and '\\' not in name and '\x00' not in name
            and not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')),
            'path_invalid')
    return name


def leaf(root, name):
    """A deletion is lexical: never follow even an in-tree parent symlink."""
    relative(name)
    p = root
    for part in PurePosixPath(name).parts[:-1]:
        p = p / part
        require(not p.is_symlink() and p.is_dir() and p.stat().st_dev == root.stat().st_dev,
                'parent_not_private_directory:' + name)
    return root / name


def guest_file(root, name):
    """Resolve /usr-style guest links without looking at the host's /usr."""
    queue = list(PurePosixPath(relative(name)).parts)
    parts = []
    links = 0
    while queue:
        part = queue.pop(0)
        if part in ('', '.'):
            continue
        if part == '..':
            require(parts, 'guest_link_escape')
            parts.pop()
            continue
        current = root.joinpath(*parts, part)
        mode = current.lstat().st_mode
        if stat.S_ISLNK(mode):
            links += 1
            require(links <= 40, 'guest_link_cycle')
            target = os.readlink(current)
            if target.startswith('/'):
                parts = []
            queue = list(PurePosixPath(target).parts[1:] if target.startswith('/')
                         else PurePosixPath(target).parts) + queue
        else:
            require(current.stat().st_dev == root.stat().st_dev, 'guest_mount_escape')
            parts.append(part)
    result = root.joinpath(*parts)
    require(result.is_file(), 'source_not_file:' + name)
    return result


def identity(root):
    require(root.is_dir() and root != Path('/'), 'root_invalid')
    receipt = root / 'etc/games-runtime.properties'
    properties = dict(line.split('=', 1) for line in receipt.read_text().splitlines() if '=' in line)
    require(properties.get('runtimeBackend') == 'rootfs_proot' and
            properties.get('architecture') == 'aarch64', 'runtime_identity')
    packages = (root / 'var/lib/dpkg/status').read_text().split('\n\n')
    require(any('Package: hangover-wine\n' in p + '\n' and
                'Version: ' + PACKAGE + '\n' in p + '\n' and
                'Status: install ok installed\n' in p + '\n' for p in packages), 'wine_package_identity')
    updates = root / 'var/lib/dpkg/updates'
    require(not updates.is_symlink() and (not updates.exists() or not any(updates.iterdir())),
            'package_transaction_pending')
    return {'propertiesSha256': digest(receipt),
            'packageStatusSha256': digest(root / 'var/lib/dpkg/status'), 'wineVersion': PACKAGE}


def idle(root):
    aliases = {str(root), str(root).replace('/data/user/0/', '/data/data/')}
    for process in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            args = process.read_bytes().split(b'\x00')
            executable = os.fsdecode(args[0]).rsplit('/', 1)[-1]
            if executable.startswith(('wine', 'proot', 'FEX', 'box64')):
                require(not any(a.encode() in b' '.join(args) for a in aliases), 'input_running')
        except (PermissionError, ProcessLookupError, FileNotFoundError):
            pass


def tree(root, hashes=True):
    result = {}
    devices = root.stat().st_dev
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            p = Path(directory) / name
            s = p.lstat()
            require(s.st_dev == devices, 'mounted_input')
            if stat.S_ISDIR(s.st_mode):
                continue
            rel = str(p.relative_to(root))
            require(stat.S_ISREG(s.st_mode) or stat.S_ISLNK(s.st_mode), 'special_file:' + rel)
            item = {'mode': stat.S_IMODE(s.st_mode), 'bytes': s.st_size,
                    'mtimeNs': s.st_mtime_ns, 'allocated': s.st_blocks * 512}
            if stat.S_ISLNK(s.st_mode):
                item['link'] = os.readlink(p)
            elif hashes:
                item['sha256'] = digest(p)
            result[rel] = item
    return result


def protect(root, assets, snapshot=None):
    metadata = json.loads((assets / 'bundle.json').read_text())
    require(metadata['sourceComponentSha256'] ==
            'ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1', 'component_identity')
    require(digest(assets / 'manifest.json') == metadata['assets']['manifest.json'], 'manifest_digest')
    records = json.loads((assets / 'manifest.json').read_text())
    protected = {}
    def check(p, expected, size=None):
        item = snapshot.get(str(p.relative_to(root))) if snapshot is not None else None
        actual = item['sha256'] if item is not None else digest(p)
        length = item['bytes'] if item is not None else p.stat().st_size
        return actual == expected and (size is None or length == size)
    for record in records:
        if 'source' not in record:
            continue
        name = relative(record['source'])
        p = guest_file(root, name)
        require(check(p, record['sha256'], record['bytes']),
                'prefix_source_mismatch:' + name)
        protected[name] = record['sha256']
        protected[str(p.relative_to(root))] = record['sha256']
    for name, expected in metadata['runtimeFiles'].items():
        p = guest_file(root, name)
        require(check(p, expected), 'runtime_guard_mismatch:' + name)
        protected[relative(name)] = expected
        protected[str(p.relative_to(root))] = expected
    return protected


def clone(source, candidate):
    source = source.resolve(strict=True)
    candidate = candidate.absolute()
    require(candidate.parent.resolve(strict=True) == candidate.parent and not candidate.exists()
            and not candidate.is_symlink() and not within(candidate, source),
            'candidate_destination')
    require(LIVE not in candidate.parts, 'live_candidate_forbidden')
    before = identity(source)
    idle(source)
    for name in ('mnt/games/game', 'mnt/games/prefix', 'root/.wine'):
        bound = source / name
        require(not bound.is_symlink() and (not bound.exists() or
                (bound.is_dir() and not any(bound.iterdir()))), 'nonempty_game_bind_target:' + name)
    snapshot = tree(source)
    needed = sum(v['bytes'] for v in snapshot.values()) + 256 * 1024 ** 2
    require(shutil.disk_usage(candidate.parent).free >= needed, 'insufficient_space')
    candidate.mkdir(mode=0o700)
    marker = {'revision': REVISION, 'state': 'Copying', 'source': str(source),
              'identity': before, 'inputTreeSha256': signature(snapshot), 'created': time.time()}
    save(candidate / MARKER, marker)
    for child in source.iterdir():
        require(child.name != MARKER, 'candidate_as_input')
        target = candidate / child.name
        if child.is_symlink():
            target.symlink_to(os.readlink(child))
            shutil.copystat(child, target, follow_symlinks=False)
        elif child.is_dir():
            shutil.copytree(child, target, symlinks=True)
        else:
            shutil.copy2(child, target, follow_symlinks=False)
    # Independent copies may allocate differently. Compare content, links, modes and mtime.
    def content(view):
        return {k: {a: b for a, b in v.items() if a != 'allocated'} for k, v in view.items() if k != MARKER}
    metadata = lambda view: {k: {a: b for a, b in v.items() if a != 'sha256'}
                             for k, v in content(view).items()}
    require(metadata(tree(source, hashes=False)) == metadata(snapshot) and identity(source) == before,
            'input_changed')
    require(content(tree(candidate)) == content(snapshot), 'clone_changed')
    idle(source)
    marker['state'] = 'Ready'
    save(candidate / MARKER, marker)
    return marker


def candidate_root(root):
    require(not root.is_symlink() and root.absolute() == root.resolve(strict=True)
            and LIVE not in root.parts, 'live_or_aliased_candidate')
    marker = json.loads((root / MARKER).read_text())
    require(marker['revision'] == REVISION and marker['state'] == 'Ready', 'candidate_not_ready')
    if marker.get('kind') == 'UnpublishedBuild':
        build_root(root, Path(marker['termuxFiles']))
        require(marker['source'] is None, 'build_source_invalid')
    else:
        require(marker['source'] is not None and not within(root, Path(marker['source'])),
                'candidate_not_ready')
    require(identity(root) == marker['identity'], 'candidate_identity_changed')
    idle(root)
    return marker


def build_root(root, termux_files):
    require(termux_files.is_dir() and termux_files == termux_files.resolve(strict=True),
            'build_owner_alias')
    expected = termux_files / 'usr/var/lib/proot-distro/containers/tmpl-build/rootfs'
    require(root == expected and root.is_dir() and root == root.resolve(strict=True),
            'not_private_build_root')
    require(LIVE not in root.parts, 'live_build_forbidden')
    idle(root)


def policy_sha():
    h = hashlib.sha256()
    for path in (Path(__file__), Path(__file__).with_name('audit-rootfs-elf.py')):
        h.update(path.read_bytes())
    return h.hexdigest()


def verify_build(root, recipe):
    require(not root.is_symlink() and root == root.resolve(strict=True), 'build_verification_alias')
    require(not (root / TRIM_RECEIPT).is_symlink(), 'build_receipt_alias')
    require(not (root / MARKER).exists() and not (root / MARKER).is_symlink(),
            'build_not_finalized')
    receipt = json.loads((root / TRIM_RECEIPT).read_text())
    require(receipt['state'] == 'Verified' and receipt['recipeSha256'] == recipe
            and receipt['policySha256'] == policy_sha()
            and receipt['batches'] == list(BUILD_BATCHES), 'build_trim_receipt_invalid')
    require(receipt['identity'] == identity(root), 'build_trim_identity_changed')
    return receipt


def build_locales(root, termux_files):
    command = [str(termux_files / 'usr/bin/proot'), '--link2symlink', '-0', '-r', str(root),
               '-b', '/dev', '-b', '/proc', '-w', '/', '/usr/bin/env', '-i',
               'PATH=/usr/bin:/bin', '/bin/sh', '-eu', '-c',
               'test "$(LC_ALL=zh_CN.UTF-8 locale charmap)" = UTF-8; '
               'test "$(LC_ALL=zh_CN.GBK locale charmap)" = GBK; '
               'fc-match "Noto Sans CJK SC"']
    subprocess.run(command, check=True)


def build(root, termux_files, assets, recipe, report):
    """Trim an unpublished installer scratch tree; never accept a live/game tree.

    Failure makes this scratch tree ineligible for publication. The shell owner
    discards it on failure; the existing shared environment and receipt survive.
    """
    build_root(root, termux_files)
    require(re.fullmatch('[0-9a-f]{64}', recipe) is not None, 'build_recipe_invalid')
    require(not (root / MARKER).exists() and not (root / MARKER).is_symlink(),
            'interrupted_build_must_be_discarded')
    for name in ('mnt/games/game', 'mnt/games/prefix', 'root/.wine'):
        target = root / name
        require(not target.is_symlink() and (not target.exists() or
                (target.is_dir() and not any(target.iterdir()))), 'nonempty_game_bind_target:' + name)
    original_identity = identity(root)
    require('recipeSha256=' + recipe in (root / 'etc/games-runtime.properties').read_text().splitlines(),
            'build_recipe_not_installed')
    marker = {'revision': REVISION, 'state': 'Ready', 'kind': 'UnpublishedBuild',
              'source': None, 'termuxFiles': str(termux_files), 'identity': original_identity,
              'inputTreeSha256': signature(tree(root)), 'created': time.time()}
    save(root / MARKER, marker)
    result = {'schemaVersion': 1, 'state': 'Trimming', 'recipeSha256': recipe,
              'policySha256': policy_sha(), 'identity': original_identity,
              'batches': list(BUILD_BATCHES), 'plans': [], 'journals': []}
    report_created = False
    try:
        report = outside(root, report)
        require(not report.exists(), 'build_report_exists')
        save(report, result)
        report_created = True
        # Device qualification stays incremental. Installation applies the union
        # of those qualified rules once, avoiding repeated full-image hashing.
        for batch in ('runtime',):
            expected = plan(root, assets, batch)
            journal_path = report.with_name(report.name + '.' + batch + '.journal.json')
            actual = apply(root, assets, expected, journal_path)
            result['plans'].append(expected)
            result['journals'].append(actual)
            save(report, result)
        audit_path = Path(__file__).with_name('audit-rootfs-elf.py')
        spec = importlib.util.spec_from_file_location('rootfs_build_audit', audit_path)
        audit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(audit)
        audit_result = audit.scan(root)
        save(report.with_name(report.name + '.elf.json'), audit_result)
        require(not audit_result['problems'], 'build_elf_dependency_failed')
        build_locales(root, termux_files)
        snapshot = tree(root)
        protect(root, assets, snapshot)
        require(signature(snapshot) == result['journals'][-1]['outputTreeSha256'],
                'build_changed_during_verification')
        # Validate all quarantines before deleting any rollback material. Only
        # these exact leaves are discarded; unexpected data blocks publication.
        for journal in result['journals']:
            quarantine = Path(journal['quarantine'])
            actual = tree(quarantine)
            expected = {r['path']: {k: v for k, v in r.items() if k not in ('path', 'reason')}
                        for r in journal['plan']['deletions']}
            require(actual == expected, 'build_quarantine_changed')
        result['state'] = 'DiscardingVerifiedBuildQuarantines'
        save(report, result)
        for journal in result['journals']:
            quarantine = Path(journal['quarantine'])
            for record in journal['plan']['deletions']:
                leaf(quarantine, record['path']).unlink()
            for directory, _, _ in os.walk(quarantine, topdown=False, followlinks=False):
                Path(directory).rmdir()
        receipt = {k: result[k] for k in ('schemaVersion', 'recipeSha256', 'policySha256',
                                          'identity', 'batches')}
        receipt.update(state='Verified', removedFiles=sum(j.get('netRemovedFiles', len(p['deletions']))
                       for p, j in zip(result['plans'], result['journals'])),
                       logicalBytes=sum(j.get('logicalBytesReclaimed', p['logicalBytes'])
                       for p, j in zip(result['plans'], result['journals'])),
                       allocatedBytes=sum(j.get('allocatedBytesReclaimed', p['allocatedBytes'])
                       for p, j in zip(result['plans'], result['journals'])),
                       protectedSha256=signature(protect(root, assets)),
                       elfCount=audit_result['elfCount'])
        save(root / TRIM_RECEIPT, receipt)
        (root / MARKER).unlink()
        result.update(state='Verified', receipt=receipt)
        save(report, result)
        return receipt
    except BaseException:
        marker['state'] = 'Rejected'
        save(root / MARKER, marker)
        result['state'] = 'RejectedUnpublishedBuild'
        # A report path rejection must never overwrite an existing report.
        if report_created:
            try:
                if json.loads(report.read_text()).get('recipeSha256') == recipe:
                    save(report, result)
            except (OSError, ValueError):
                pass
        raise


def reason(name, batch):
    if batch == 'runtime':
        for qualified in BUILD_BATCHES:
            why = reason(name, qualified)
            if why:
                return qualified + ': ' + why
        return None
    if batch == 'development':
        if name.startswith('usr/include/wine/'):
            return 'Wine development header'
        if name.startswith('usr/lib/wine/') and name.endswith(('.a', '.la')):
            return 'Wine static development archive'
    if batch == 'resources':
        if name.startswith('usr/share/locale/') and '/LC_MESSAGES/' in name and name.endswith('.mo'):
            return 'gettext message catalog; not libc locale or Wine resources'
        if name.startswith(('usr/share/doc/', 'usr/share/man/', 'usr/share/info/')):
            return 'Non-runtime documentation, manual or info page; legal material retained'
    if batch == 'build-tools':
        if name.startswith(('usr/share/i18n/', 'var/lib/dpkg/info/', 'usr/lib/apt/',
                            'usr/share/apt/', 'etc/apt/', 'var/cache/apt/', 'var/lib/apt/',
                            'var/cache/debconf/')):
            return 'Completed-install package metadata/cache or locale generation source; rebuild for changes'
        if re.fullmatch(r'usr/(bin/(apt(?:-cache|-cdrom|-config|-get|-mark)?|dpkg(?:-deb|-divert|-maintscript-helper|-query|-realpath|-split|-statoverride|-trigger)?|localedef)|sbin/(dpkg-preconfigure|dpkg-reconfigure|locale-gen))', name):
            return 'Build-only package/locale tool; no game startup or repair consumer'
        if re.fullmatch(r'usr/lib/aarch64-linux-gnu/libapt-(pkg|private)\.so\.[0-9.]+', name):
            return 'Private APT library; all known ELF consumers are removed with APT'
    if batch == 'gpu-drivers':
        if re.fullmatch(r'usr/lib/aarch64-linux-gnu/libvulkan_(broadcom|nouveau|panfrost|radeon)\.so', name):
            return 'Non-target Vulkan ICD; keep Turnip, software, gfxstream and virtio drivers'
        if re.fullmatch(r'usr/share/vulkan/icd.d/(broadcom|nouveau|panfrost|radeon)_icd\.json', name):
            return 'Registration of the matching non-target Vulkan driver'
    if batch == 'fonts':
        if re.fullmatch(r'usr/share/fonts/opentype/noto/NotoSerifCJK-[^/]+\.ttc', name):
            return 'Unreferenced Noto Serif CJK font; prefix Sans sources remain protected'
        if name.startswith('var/cache/fontconfig/'):
            return 'Old fontconfig cache; rebuilt transactionally in an isolated stage'
    return None


def link_route(snapshot, name):
    queue = list(PurePosixPath(name).parts)
    parts, seen, route = [], set(), []
    while queue:
        part = queue.pop(0)
        if part in ('', '.'):
            continue
        if part == '..':
            if not parts:
                return route, None
            parts.pop()
            continue
        current = '/'.join(parts + [part])
        route.append(current)
        entry = snapshot.get(current, {})
        if 'link' in entry:
            state = (current, tuple(queue))
            if state in seen or len(seen) >= 40:
                return route, None
            seen.add(state)
            target = entry['link']
            if target.startswith('/'):
                parts = []
            queue = list(PurePosixPath(target).parts[1:] if target.startswith('/')
                         else PurePosixPath(target).parts) + queue
        else:
            parts.append(part)
    return route, '/'.join(parts)


def check_surviving_links(snapshot, targets):
    # Resolve in guest space, including directory aliases and intermediate links.
    # Pre-existing broken/looping links are not new regressions, but none of the
    # paths used to resolve a surviving link may be removed by this batch.
    for name, item in snapshot.items():
        if 'link' not in item or name in targets:
            continue
        route, _ = link_route(snapshot, name)
        require(not targets.intersection(route), 'surviving_link_depends_on_deletion:' + name)


def resource_retained(snapshot, selected, legal_candidates=None):
    retained = {}
    for name in selected if legal_candidates is None else legal_candidates:
        if any(re.search(r'copyright|copying|licen[cs]e|notice|authors', part, re.I)
               for part in PurePosixPath(name).parts):
            retained[name] = 'Legal, licensing or attribution material'
        elif 'link' in snapshot[name] and link_route(snapshot, name)[1] not in snapshot:
            retained[name] = 'Directory alias or pre-existing unresolved link'
    # Preserve every remaining link's complete route. This includes licenses
    # reached through non-legal filenames, directory aliases and .l2s entities.
    while True:
        targets = selected.difference(retained)
        additions = {}
        for name, item in snapshot.items():
            if 'link' in item and name not in targets:
                route, _ = link_route(snapshot, name)
                for dependency in targets.intersection(route):
                    additions[dependency] = 'Dependency of surviving guest link: ' + name
        if not additions:
            return retained
        retained.update(additions)


def plan(root, assets, batch):
    marker = candidate_root(root)
    snapshot = tree(root)
    protected = protect(root, assets, snapshot)
    selected = {name for name in snapshot if reason(name, batch)}
    if batch in ('fonts', 'runtime') and not any(name.startswith('usr/share/fonts/opentype/noto/') for name in selected):
        # No font removal means there is no stale cache to rebuild.
        selected = {name for name in selected if not name.startswith('var/cache/fontconfig/')}
    retained = {}
    if batch == 'resources':
        retained = resource_retained(snapshot, selected)
    elif batch == 'runtime' and 'resources' in BUILD_BATCHES:
        retained = resource_retained(snapshot, selected,
                                     {name for name in selected if reason(name, 'resources')})
    deletions = []
    for name, item in sorted(snapshot.items()):
        why = reason(name, batch)
        if why and name in selected and name not in retained:
            leaf(root, name)
            require(name not in protected, 'protected_deletion:' + name)
            # A development symlink may point at a live file; unlinking it must not
            # invalidate any surviving link. Scan all incoming edges below.
            deletions.append(dict(item, path=name, reason=why))
    targets = {d['path'] for d in deletions}
    check_surviving_links(snapshot, targets)
    result = {'schemaVersion': 1, 'revision': REVISION, 'root': str(root), 'batch': batch,
              'identity': marker['identity'], 'treeSha256': signature(snapshot),
              'protectedSha256': signature(protected), 'protectedFiles': len(protected),
              'deletions': deletions, 'logicalBytes': sum(d['bytes'] for d in deletions if 'link' not in d),
              'allocatedBytes': sum(d['allocated'] for d in deletions if 'link' not in d)}
    if batch in ('resources', 'runtime'):
        result['retainedByPolicy'] = [{'path': p, 'reason': why} for p, why in sorted(retained.items())]
    if batch in ('fonts', 'runtime'):
        result.update(inputLogicalBytes=sum(v['bytes'] for v in snapshot.values() if 'link' not in v),
                      inputAllocatedBytes=sum(v['allocated'] for v in snapshot.values() if 'link' not in v),
                      inputFiles=len(snapshot))
    if batch == 'runtime':
        result['policyBatches'] = list(BUILD_BATCHES)
    result['planSha256'] = signature(result)
    return result


def regenerate_font_cache(root, stage):
    marker = json.loads((root / MARKER).read_text())
    if marker.get('kind') == 'UnpublishedBuild':
        owner = Path(marker['termuxFiles'])
    else:
        source = Path(marker['source'])
        owner = source.parents[5]
        require(source == owner / 'usr/var/lib/proot-distro/games-shared-rootfs/rootfs',
                'font_cache_source_layout')
    subprocess.run([str(owner / 'usr/bin/proot'), '--kill-on-exit', '--link2symlink', '-0',
                    '-r', str(root), '-b', '/dev', '-b', '/proc', '-b', str(stage) + ':/var/cache/fontconfig',
                    '-w', '/', '/usr/bin/env', '-i', 'PATH=/usr/bin:/bin',
                    'FONTCONFIG_FILE=/etc/fonts/fonts.conf', '/usr/bin/fc-cache', '-f'], check=True)


def record_matches(path, record):
    try:
        s = path.lstat()
        if stat.S_IMODE(s.st_mode) != record['mode'] or s.st_mtime_ns != record['mtimeNs']:
            return False
        if 'link' in record:
            return stat.S_ISLNK(s.st_mode) and os.readlink(path) == record['link']
        return stat.S_ISREG(s.st_mode) and digest(path) == record['sha256']
    except OSError:
        return False


def apply(root, assets, expected, journal_path):
    journal_path = outside(root, journal_path)
    actual = plan(root, assets, expected['batch'])
    require(actual == expected, 'stale_or_changed_plan')
    quarantine = root.with_name(root.name + '.removed-' + expected['batch'])
    require(not quarantine.exists() and not quarantine.is_symlink(), 'quarantine_exists')
    require(not journal_path.exists() and not journal_path.is_symlink(), 'journal_exists')
    quarantine.mkdir(mode=0o700)
    journal = {'state': 'Applying', 'plan': expected, 'quarantine': str(quarantine)}
    save(journal_path, journal)
    try:
        for record in expected['deletions']:
            source = leaf(root, record['path'])
            target = quarantine / record['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(source, target)
        if any(r['path'].startswith('usr/share/fonts/opentype/noto/') for r in expected['deletions']):
            leaf(root, 'var/cache/fontconfig/.private-directory-check')
            stage = root.with_name(root.name + '.font-cache-' + signature(str(journal_path))[:12])
            require(not stage.exists() and not stage.is_symlink(), 'font_cache_stage_exists')
            stage.mkdir(mode=0o700)
            journal.update(state='RegeneratingFontCache', generatedCacheStage=str(stage))
            save(journal_path, journal)
            regenerate_font_cache(root, stage)
            generated = tree(stage)
            require(generated and all('/' not in name and 'link' not in entry for name, entry in generated.items()),
                    'font_cache_stage_not_flat_regular_files')
            journal['generatedFiles'] = {str(PurePosixPath('var/cache/fontconfig') / name): entry
                                         for name, entry in generated.items()}
            journal['state'] = 'InstallingGeneratedFontCache'
            save(journal_path, journal)
            cache = root / 'var/cache/fontconfig'
            cache.mkdir(parents=True, exist_ok=True)
            require(not cache.is_symlink(), 'font_cache_directory_alias')
            for name in generated:
                target = leaf(root, 'var/cache/fontconfig/' + name)
                require(not target.exists() and not target.is_symlink(), 'font_cache_target_exists')
                os.replace(stage / name, target)
            stage.rmdir()
        after = tree(root)
        protect(root, assets, after)
        journal['state'] = 'AppliedPendingRuntimeValidation'
        journal['outputTreeSha256'] = signature(after)
        if expected['batch'] in ('fonts', 'runtime'):
            journal.update(logicalBytesReclaimed=expected['inputLogicalBytes'] -
                           sum(v['bytes'] for v in after.values() if 'link' not in v),
                           allocatedBytesReclaimed=expected['inputAllocatedBytes'] -
                           sum(v['allocated'] for v in after.values() if 'link' not in v),
                           netRemovedFiles=expected['inputFiles'] - len(after))
        save(journal_path, journal)
    except BaseException:
        rollback(root, journal_path)
        raise
    return journal


def rollback(root, journal_path):
    journal_path = outside(root, journal_path)
    candidate_root(root)
    journal = json.loads(journal_path.read_text())
    require(journal['state'] in ('Applying', 'RegeneratingFontCache', 'InstallingGeneratedFontCache',
                                'AppliedPendingRuntimeValidation') and
            journal['plan']['root'] == str(root), 'rollback_identity')
    quarantine = root.with_name(root.name + '.removed-' + journal['plan']['batch'])
    require(journal['quarantine'] == str(quarantine) and not quarantine.is_symlink(), 'rollback_quarantine')
    originals = {r['path']: r for r in journal['plan']['deletions']}
    for name, record in journal.get('generatedFiles', {}).items():
        require(name.startswith('var/cache/fontconfig/'), 'rollback_generated_path')
        target = leaf(root, name)
        if not target.exists() and not target.is_symlink():
            continue
        original = originals.get(name)
        removed = quarantine / name
        if original is not None and not removed.exists() and not removed.is_symlink() and record_matches(target, original):
            continue  # A previous recovery already put this old cache back.
        require(record_matches(target, record), 'rollback_generated_cache_changed')
        target.unlink()
    for record in reversed(journal['plan']['deletions']):
        source = leaf(quarantine, record['path']) if (quarantine / record['path']).parent.exists() else None
        target = leaf(root, record['path'])
        if source is not None and (source.exists() or source.is_symlink()):
            require(not target.exists() and not target.is_symlink(), 'rollback_target_not_empty')
            if 'link' in record:
                require(source.is_symlink() and os.readlink(source) == record['link'], 'rollback_link_changed')
            else:
                require(not source.is_symlink() and digest(source) == record['sha256'], 'rollback_file_changed')
            metadata = source.lstat()
            require(stat.S_IMODE(metadata.st_mode) == record['mode'] and
                    metadata.st_mtime_ns == record['mtimeNs'], 'rollback_metadata_changed')
            os.replace(source, target)
        else:
            # A missing quarantine leaf can mean an interrupted recovery already
            # restored it. Prove that case; never report lost files as recovered.
            require(target.exists() or target.is_symlink(), 'rollback_file_missing')
            if 'link' in record:
                require(target.is_symlink() and os.readlink(target) == record['link'],
                        'rollback_restored_link_changed')
            else:
                require(not target.is_symlink() and digest(target) == record['sha256'],
                        'rollback_restored_file_changed')
            metadata = target.lstat()
            require(stat.S_IMODE(metadata.st_mode) == record['mode'] and
                    metadata.st_mtime_ns == record['mtimeNs'], 'rollback_restored_metadata_changed')
    # Only empty quarantine directories are disposable. Unexpected data is
    # retained and blocks success rather than being removed by a recursive rm.
    if quarantine.exists():
        for directory, dirs, files in os.walk(quarantine, topdown=False, followlinks=False):
            require(not files and not any((Path(directory) / d).is_symlink() for d in dirs),
                    'rollback_quarantine_not_empty')
            Path(directory).rmdir()
    if 'outputTreeSha256' in journal:
        journal['appliedTreeSha256'] = journal.pop('outputTreeSha256')
    journal['restoredFiles'] = len(journal['plan']['deletions'])
    journal['state'] = 'RolledBack'
    save(journal_path, journal)
    return journal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    c = sub.add_parser('clone')
    c.add_argument('--source', type=Path, required=True)
    c.add_argument('--candidate', type=Path, required=True)
    for command in ('build', 'verify-build'):
        c = sub.add_parser(command)
        c.add_argument('--root', type=Path, required=True)
        c.add_argument('--recipe', required=True)
        if command == 'build':
            c.add_argument('--termux-files', type=Path, required=True)
            c.add_argument('--assets', type=Path, required=True)
            c.add_argument('--report', type=Path, required=True)
    for command in ('plan', 'apply', 'rollback'):
        c = sub.add_parser(command)
        c.add_argument('--root', type=Path, required=True)
        if command != 'rollback':
            c.add_argument('--assets', type=Path, required=True)
        if command == 'plan':
            c.add_argument('--batch', choices=['development', 'resources', 'fonts', 'build-tools', 'gpu-drivers'], required=True)
            c.add_argument('--output', type=Path, required=True)
        elif command == 'apply':
            c.add_argument('--plan', type=Path, required=True)
            c.add_argument('--journal', type=Path, required=True)
        else:
            c.add_argument('--journal', type=Path, required=True)
    args = parser.parse_args()
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        root = (args.candidate if args.command == 'clone' else args.root).absolute()
        if args.command == 'verify-build':
            verify_build(root, args.recipe)
            return
        if args.command == 'build':
            build_root(root, args.termux_files)
        # The sibling lock covers cloning, planning, removal and recovery. It is
        # never stored in the RootFS or counted as a reclaimed runtime file.
        fd = os.open(str(root) + '.minimize-lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.command == 'clone':
                result = clone(args.source, root)
            elif args.command == 'build':
                result = build(root, args.termux_files, args.assets, args.recipe, args.report)
            elif args.command == 'plan':
                result = plan(root, args.assets, args.batch)
                output = outside(root, args.output)
                require(not output.exists(), 'plan_output_exists')
                save(output, result)
            elif args.command == 'apply':
                result = apply(root, args.assets, json.loads(args.plan.read_text()), args.journal)
            else:
                result = rollback(root, args.journal)
        print(json.dumps({k: v for k, v in result.items() if k not in ('deletions', 'plan')}, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
