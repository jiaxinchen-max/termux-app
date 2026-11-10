#!/usr/bin/env python3
"""Audit PRoot Wine/Box64 ELF edges without executing target code.

The wrapper lock is an explicit reviewed mapping, not a same-name ARM64 guess.
Output includes the native closure and guest symlink routes for trim protection.
This proves direct dependency availability, not dlopen or symbol compatibility.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import stat

spec = importlib.util.spec_from_file_location('rootfs_audit', Path(__file__).with_name('audit-rootfs-elf.py'))
a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a)
m = a.m
RECEIPT = 'etc/wine-external-wine-dependencies.json'
EMULATED_DIRS = ('lib/x86_64-linux-gnu', 'usr/lib/x86_64-linux-gnu',
                 'usr/x86_64-linux-gnu/lib', 'usr/lib/box64-x86_64-linux-gnu')
NATIVE_DIRS = ('usr/local/lib/aarch64-linux-gnu', 'usr/local/lib',
               'lib/aarch64-linux-gnu', 'usr/lib/aarch64-linux-gnu', 'lib', 'usr/lib')


def require(ok, reason):
    if not ok:
        raise ValueError('external_wine_' + reason)


def resolve(root, name):
    """Return the actual file and every lexical guest link on its route."""
    m.relative(name)
    pending = name.split('/')
    parts, route = [], []
    while pending:
        part = pending.pop(0)
        if part in ('', '.'):
            continue
        if part == '..':
            require(bool(parts), 'link_escape')
            parts.pop()
            continue
        p = root.joinpath(*parts, part)
        mode = p.lstat().st_mode
        if stat.S_ISLNK(mode):
            require(len(route) < 40, 'link_cycle')
            route.append(p.relative_to(root).as_posix())
            edge = os.readlink(p)
            if edge.startswith('/'):
                parts = []
            pending = edge.split('/') + pending
        else:
            parts.append(part)
    p = root.joinpath(*parts)
    require(p.is_file(), 'provider_not_file')
    return p, route


def load_wrappers(lock):
    value = json.loads(lock.read_text())
    require(value['backend'] == 'rootfs_proot' and value['nativeMachine'] == 183,
            'wrapper_backend')
    require(re.fullmatch('[0-9a-f]{40}', value['box64Commit']) is not None, 'wrapper_commit')
    files = {}
    for name, expected in value['sourceFiles'].items():
        m.relative(name)
        p = lock.parent / name
        require(not p.is_symlink() and p.is_file() and m.digest(p) == expected,
                'wrapper_source_digest:' + name)
        files[name] = p.read_text()
    table = files[value['libraryList']]
    declared = dict(re.findall(r'^GO\("([^"\n]+)",\s*(\w+)\)', table, re.M))
    for guest, record in value['wrappers'].items():
        require(declared.get(guest) == record['id'], 'wrapper_not_declared:' + guest)
        source = files[record['source']]
        if record.get('self'):
            require(guest == 'ld-linux-x86-64.so.2' and 'dlopen(NULL,' in source,
                    'unknown_self_wrapper')
        else:
            native = record['native']
            require('/' not in native and native not in ('', '.', '..'), 'native_soname')
            require(re.search(r'const char\s*\*\s*\w+Name\s*=\s*"' + re.escape(native) + r'"', source)
                    is not None, 'wrapper_native_not_declared:' + guest)
            for alias in record.get('nativeAliases', []):
                require('/' not in alias and re.search(r'^#define ALTNAME[0-9]*\s+"' + re.escape(alias) + r'"', source, re.M)
                        is not None, 'wrapper_alias_not_declared:' + guest)
    return value


def scan(root, runtimes, box64, wrappers):
    require(wrappers['backend'] == 'rootfs_proot', 'wrong_backend')
    require(m.digest(box64 / 'bin/box64') == wrappers['box64Sha256'], 'box64_digest')
    records, problems, protected, cache, seen = [], [], {}, {}, set()
    roots = {name: p.resolve(strict=True) for name, p in runtimes.items()}
    roots['box64'] = box64.resolve(strict=True)

    def decode(p):
        if p not in cache:
            cache[p] = a.elf(p)
        return cache[p]

    def keep(p, route):
        for name in route:
            protected[name] = {'type': 'symlink', 'link': os.readlink(root / name)}
        protected[p.relative_to(root).as_posix()] = {
            'type': 'file', 'sha256': m.digest(p), 'bytes': p.stat().st_size}

    def provider(base, name, directories, machine):
        for directory in directories:
            try:
                p, route = resolve(base, directory + '/' + name)
                data = decode(p)
                if (data and data['machine'] == machine and data['class'] == 64
                        and data['type'] == 3 and data.get('soname') == name):
                    return p, route
            except (OSError, ValueError):
                pass
        return None

    def native(name, directories=NATIVE_DIRS):
        if name.startswith('/'):
            directories = [str(Path(name).parent).lstrip('/')]
            name = Path(name).name
        selected = provider(root, name, directories, 183)
        if selected is None:
            raise ValueError('missing_native_provider:' + name)
        p, route = selected
        keep(p, route)
        visit('rootfs', p, 183)
        return 'rootfs:' + p.relative_to(root).as_posix()

    def visit(owner, path, machine):
        key = (owner, str(path))
        if key in seen:
            return
        seen.add(key)
        data = decode(path)
        require(data is not None and data['machine'] == machine and data['class'] == 64,
                'provider_architecture:' + str(path))
        base = root if owner in ('rootfs', 'rootfs-x86') else roots[owner]
        relative = path.relative_to(base).as_posix()
        record = dict(data, owner=owner, path=relative, sha256=m.digest(path), resolved={})
        records.append(record)
        search = []
        for entry in data['runpath'] or data['rpath']:
            if entry.startswith('/data/') or '/home/builder' in entry or '/work/' in entry:
                problems.append({'owner': owner, 'path': relative, 'reason': 'wrong_backend_search_path', 'value': entry})
            elif entry and (machine == 183 or owner == 'rootfs-x86'):
                origin = '/' + path.parent.relative_to(base).as_posix()
                expanded = entry.replace('${ORIGIN}', origin).replace('$ORIGIN', origin)
                lib = 'lib/aarch64-linux-gnu' if machine == 183 else 'lib/x86_64-linux-gnu'
                expanded = expanded.replace('${LIB}', lib).replace('$LIB', lib)
                platform = 'aarch64' if machine == 183 else 'x86_64'
                expanded = expanded.replace('${PLATFORM}', platform).replace('$PLATFORM', platform)
                if expanded.startswith('/') and '$' not in expanded:
                    search.append(os.path.normpath(expanded).lstrip('/'))
                else:
                    problems.append({'owner': owner, 'path': relative, 'reason': 'unsupported_search_path', 'value': entry})
            elif entry:
                # No implicit approximation of arbitrary RPATH semantics.
                problems.append({'owner': owner, 'path': relative, 'reason': 'unsupported_search_path', 'value': entry})
        if machine == 183:
            native_needs = list(data['needed'])
            if data.get('interpreter'):
                native_needs.append(data['interpreter'])
            for name in native_needs:
                try:
                    record['resolved'][name] = native(name, [*search, *NATIVE_DIRS])
                except (OSError, ValueError) as error:
                    problems.append({'owner': owner, 'path': relative, 'needed': name, 'reason': str(error)})
            return
        if data.get('interpreter') not in (None, '/lib64/ld-linux-x86-64.so.2'):
            problems.append({'owner': owner, 'path': relative, 'reason': 'wrong_interpreter', 'value': data['interpreter']})
        with path.open('rb') as stream:
            if b'/data/data/com.termux/files/usr/glibc' in stream.read():
                problems.append({'owner': owner, 'path': relative, 'reason': 'fixed_termux_glibc_path'})
        # Literal load names are conservative dlopen requirements for Wine Unix
        # modules; package/plugin loads still require separate runtime checks.
        load_names = set(data['needed'])
        if relative.startswith('lib/wine/x86_64-unix/'):
            load_names.update(x.decode('ascii') for x in re.findall(
                rb'(?<![A-Za-z0-9_./+-])(lib[A-Za-z0-9_+-]+\.so(?:\.[A-Za-z0-9_+.-]+)?)\x00', path.read_bytes()))
        record['literalLoadNames'] = sorted(load_names - set(data['needed']))
        for name in sorted(load_names):
            try:
                if name in ('ntdll.so', 'win32u.so') and relative.startswith('lib/wine/x86_64-unix/'):
                    selected = provider(base, name, ['lib/wine/x86_64-unix'], 62)
                    require(selected is not None, 'wine_private_module_missing:' + name)
                    p, _ = selected
                    require(decode(p).get('soname') == name, 'wine_private_soname:' + name)
                    record['resolved'][name] = owner + ':' + p.relative_to(base).as_posix()
                    visit(owner, p, 62)
                elif name in wrappers['wrappers']:
                    mapping = wrappers['wrappers'][name]
                    if mapping.get('self'):
                        record['resolved'][name] = 'box64:self'
                    else:
                        choices = [mapping['native'], *mapping.get('nativeAliases', [])]
                        selected = next((n for n in choices if provider(root, n, NATIVE_DIRS, 183)), None)
                        require(selected is not None, 'missing_native_provider:' + '|'.join(choices))
                        record['resolved'][name] = native(selected)
                else:
                    selected = provider(root, name, [*search, *EMULATED_DIRS], 62)
                    if selected is not None:
                        p, route = selected
                        keep(p, route)
                        record['resolved'][name] = 'rootfs-x86:' + p.relative_to(root).as_posix()
                        visit('rootfs-x86', p, 62)
                    else:
                        selected = provider(roots['box64'], name, ['lib/x86_64-linux-gnu'], 62)
                        require(selected is not None, 'unmapped_or_missing_emulated_provider:' + name)
                        p, _ = selected
                        record['resolved'][name] = 'box64:' + p.relative_to(roots['box64']).as_posix()
                        visit('box64', p, 62)
            except (OSError, ValueError) as error:
                problems.append({'owner': owner, 'path': relative, 'needed': name, 'reason': str(error)})

    visit('box64', box64 / 'bin/box64', 183)
    for owner, base in roots.items():
        if owner == 'box64':
            continue
        for directory in ('bin', 'lib/wine/x86_64-unix'):
            for path in sorted((base / directory).rglob('*')):
                if path.is_symlink() or not path.is_file():
                    continue
                data = decode(path)
                if data and data['type'] in (2, 3):
                    visit(owner, path, 62)
    # Every bundled emulated library/plugin needs closure, even if reached by
    # a package-specific plugin loader rather than a Wine DT_NEEDED edge.
    for directory in EMULATED_DIRS:
        for path in sorted((root / directory).rglob('*')):
            if path.is_symlink() or not path.is_file():
                continue
            data = decode(path)
            if data and data['type'] in (2, 3):
                require(data['machine'] == 62, 'emulated_directory_architecture:' + str(path))
                actual, route = resolve(root, path.relative_to(root).as_posix())
                keep(actual, route)
                visit('rootfs-x86', actual, 62)
    require(all(any(r['owner'] == name for r in records) for name in runtimes), 'empty_runtime')
    return {'schemaVersion': 1, 'backend': 'rootfs_proot',
            'status': 'PassedDirectDependencyClosure' if not problems else 'FailedDirectDependencyClosure',
            'box64Commit': wrappers['box64Commit'], 'box64Sha256': wrappers['box64Sha256'],
            'rootfsRoot': str(root.resolve()),
            'emulatedDirectories': list(EMULATED_DIRS),
            'runtimes': {k: str(v) for k, v in roots.items()},
            'records': records, 'problems': problems, 'protectedPaths': protected,
            'limitations': ['Direct ELF availability only; symbol versions, dlopen and actual media playback need runtime checks.',
                            'Only selected x86_64 Unix execution is covered; unused i386-unix modules are not executed.',
                            'Wrapper source mapping must also match the installed Box64 build configuration.']}


def compare_capabilities(candidate, baseline):
    """Conservative gate: dependency removal needs explicit source review.

    Modern glibc merges pthread/dl/rt, so those edges alone are not capabilities.
    This inventory is a rejection gate, never proof of feature equivalence.
    """
    modules = lambda base: {p.name: p for p in (base / 'lib/wine/x86_64-unix').glob('*.so') if p.is_file()}
    current, original = modules(candidate), modules(baseline)
    require(bool(current) and bool(original), 'empty_capability_inventory')
    removed, dynamic_removed = [], []
    merged = {'libpthread.so.0', 'libdl.so.2', 'librt.so.1'}
    def library_names(path):
        # A disappearance is only a review trigger, never proof of dlopen use.
        return {x.decode('ascii') for x in re.findall(
            rb'(?<![A-Za-z0-9_./+-])(lib[A-Za-z0-9_+-]+\.so(?:\.[A-Za-z0-9_+.-]+)?)\x00', path.read_bytes())}
    for name in sorted(current.keys() & original.keys()):
        before, after = a.elf(original[name]), a.elf(current[name])
        require(before and after and before['machine'] == after['machine'] == 62,
                'capability_inventory_architecture')
        dropped = set(before['needed']) - set(after['needed']) - merged
        if dropped:
            removed.append({'module': name, 'removedDependencies': sorted(dropped)})
        names = library_names(original[name]) - library_names(current[name]) - merged
        if names:
            dynamic_removed.append({'module': name, 'removedLibraryNames': sorted(names)})
    missing = sorted(original.keys() - current.keys())
    return {'status': 'ReviewRequired' if missing or removed or dynamic_removed else 'NoRemovedModuleOrDependency',
            'baseline': str(baseline), 'candidate': str(candidate),
            'missingUnixModules': missing, 'unreviewedRemovedDependencies': removed,
            'unreviewedRemovedLibraryNames': dynamic_removed,
            'limitation': 'Presence and direct dependency comparison cannot prove complete capability equivalence.'}


def capability_inventory(base):
    return {p.name: m.digest(p) for p in sorted((base / 'lib/wine/x86_64-unix').glob('*.so'))
            if p.is_file() and not p.is_symlink()}


def apply_capability_review(comparison, candidate, baseline, path):
    """Accept only an exact, hash-bound source review of dependency changes.

    Missing Unix modules remain a hard error. This review cannot waive them.
    Neither SONAME substitutions nor compiler optimizations imply equivalence
    without per-module evidence of the retained implementation.
    """
    require(comparison['status'] == 'ReviewRequired' and not comparison['missingUnixModules'],
            'capability_review_cannot_waive_missing_modules')
    require(not path.is_symlink() and path.is_file(), 'capability_review_file')
    review = json.loads(path.read_text())
    require(review['schemaVersion'] == 1 and review['state'] == 'SourceReviewedNoFeatureRemoval',
            'capability_review_state')
    require(review['baselineElfs'] == capability_inventory(baseline)
            and review['candidateElfs'] == capability_inventory(candidate), 'capability_review_stale')
    expected = {}
    for item in comparison['unreviewedRemovedDependencies']:
        expected.setdefault(item['module'], {})['removedDependencies'] = item['removedDependencies']
    for item in comparison['unreviewedRemovedLibraryNames']:
        expected.setdefault(item['module'], {})['removedLibraryNames'] = item['removedLibraryNames']
    actual = {}
    for item in review['changes']:
        name = item['module']
        require(name not in actual and len(item['reason'].strip()) >= 20 and item['evidence'],
                'capability_review_incomplete')
        actual[name] = {k: item[k] for k in ('removedDependencies', 'removedLibraryNames') if item.get(k)}
        for evidence in item['evidence']:
            source = path.parent / evidence['path']
            m.relative(evidence['path'])
            require(not source.is_symlink() and source.is_file()
                    and m.digest(source) == evidence['sha256'], 'capability_review_evidence_changed')
    require(actual == expected, 'capability_review_differences_changed')
    return dict(comparison, status='SourceReviewedNoFeatureRemoval',
                reviewedChanges=review['changes'], reviewSha256=m.digest(path),
                limitation=comparison['limitation'] + ' Hash-bound source review is not runtime acceptance.')


def seal(root, report):
    """Persist a verified native keep set in an unpublished build tree only."""
    require(root != Path('/') and not root.is_symlink() and root.absolute() == root.resolve(strict=True)
            and m.LIVE not in root.parts, 'live_or_aliased_seal_root')
    require(report['status'] == 'PassedDirectDependencyClosure' and not report['problems'],
            'cannot_seal_failed_audit')
    require(report.get('capabilityComparison', {}).get('status') in ('NoRemovedModuleOrDependency', 'SourceReviewedNoFeatureRemoval'),
            'capability_preservation_not_checked')
    receipt = m.leaf(root, RECEIPT)
    require(not receipt.exists() and not receipt.is_symlink(), 'retention_receipt_exists')
    protected = report['protectedPaths']
    require(bool(protected), 'empty_native_closure')
    for record in report['records']:
        if record['owner'] in ('rootfs', 'rootfs-x86'):
            continue
        path = m.leaf(Path(report['runtimes'][record['owner']]), record['path'])
        require(not path.is_symlink() and m.digest(path) == record['sha256'],
                'seal_runtime_changed:' + record['path'])
    for name, item in protected.items():
        p = m.leaf(root, name)
        if item['type'] == 'file':
            require(not p.is_symlink() and p.is_file() and m.digest(p) == item['sha256']
                    and p.stat().st_size == item['bytes'], 'seal_source_changed:' + name)
        else:
            require(item['type'] == 'symlink' and p.is_symlink()
                    and os.readlink(p) == item['link'], 'seal_link_changed:' + name)
    value = {'schemaVersion': 1, 'state': 'Verified', 'backend': 'rootfs_proot',
             'box64Commit': report['box64Commit'], 'box64Sha256': report['box64Sha256'],
             'protectedPaths': protected,
             'runtimeElfs': [{k: r[k] for k in ('owner', 'path', 'sha256')}
                             for r in report['records'] if r['owner'] not in ('rootfs', 'rootfs-x86')]}
    value['wrapperLockSha256'] = report.get('wrapperLockSha256')
    value['capabilityReviewSha256'] = report.get('capabilityComparison', {}).get('reviewSha256')
    m.save(receipt, value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--runtime', type=Path, action='append', required=True)
    parser.add_argument('--baseline-runtime', type=Path, help='Compare the first runtime with its unchanged baseline; required for sealing')
    parser.add_argument('--capability-review', type=Path, help='Exact hash-bound source review of removed dependency edges; cannot waive missing modules')
    parser.add_argument('--box64', type=Path, required=True)
    parser.add_argument('--wrapper-lock', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seal', action='store_true', help='Write native keep receipt only after a successful audit of a private candidate')
    args = parser.parse_args()
    require(not args.output.exists(), 'output_exists')
    require(not m.within(args.output.absolute(), args.root.resolve(strict=True)), 'output_inside_candidate')
    result = scan(args.root.resolve(strict=True), {str(i): p for i, p in enumerate(args.runtime)},
                  args.box64, load_wrappers(args.wrapper_lock))
    result['wrapperLockSha256'] = m.digest(args.wrapper_lock)
    require(not args.capability_review or args.baseline_runtime, 'capability_review_without_baseline')
    if args.baseline_runtime:
        comparison = compare_capabilities(args.runtime[0], args.baseline_runtime)
        if args.capability_review:
            comparison = apply_capability_review(comparison, args.runtime[0], args.baseline_runtime, args.capability_review)
        result['capabilityComparison'] = comparison
        if comparison['status'] == 'ReviewRequired':
            result['problems'].append({'owner': '0', 'reason': 'runtime_capability_preservation_requires_review',
                                       'details': comparison})
            result['status'] = 'FailedCapabilityPreservationOrDependencyClosure'
    m.save(args.output, result)
    if args.seal and not result['problems']:
        seal(args.root.resolve(strict=True), result)
    print(json.dumps({'status': result['status'], 'elfCount': len(result['records']),
                      'problems': result['problems'], 'protectedPaths': len(result['protectedPaths'])}))
    return 1 if result['problems'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
