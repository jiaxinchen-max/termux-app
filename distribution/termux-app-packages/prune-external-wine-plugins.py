#!/usr/bin/env python3
"""Plan and apply user-scoped optional plugin pruning to an independent tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

SEED_PACKAGES = {
    'libcapi20-3t64', 'libgphoto2-6t64', 'libgphoto2-port12t64',
    'libsane1', 'libpcsclite1', 'samba-libs', 'libodbc2', 'libv4l-0t64',
}


def require(condition, message):
    if not condition:
        raise ValueError('plugin_prune_' + message)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def leaf(root, name):
    parts = Path(name).parts
    require(parts and not name.startswith('/') and all(x not in ('.', '..') for x in parts),
            'unsafe_path')
    p = root
    for part in parts[:-1]:
        p = p / part
        require(p.is_dir() and not p.is_symlink(), 'unsafe_parent:' + name)
    return root.joinpath(*parts)


def excluded(name):
    directory = 'usr/lib/x86_64-linux-gnu/'
    return (name.startswith(tuple(directory + x for x in
                                  ('sane/', 'libgphoto2/', 'libgphoto2_port/')))
            or (name.startswith(directory) and ('.cpython-' in Path(name).name
                or Path(name).name.startswith(('libpyldb-util.', 'libpytalloc-util.')))))


def verify_added(root, additions):
    for name, entry in additions.items():
        p = leaf(root, name)
        if entry['type'] == 'symlink':
            require(p.is_symlink() and os.readlink(p) == entry['link'], 'link_changed:' + name)
        else:
            require(stat.S_ISREG(p.lstat().st_mode) and digest(p) == entry['sha256'],
                    'file_changed:' + name)


def plan(root, audit, inputs):
    root = root.resolve(strict=True)
    require(Path(audit['rootfsRoot']).resolve(strict=True) == root, 'audit_root')
    require(audit['status'] == 'PassedDirectDependencyClosure' and not audit['problems'],
            'failed_audit')
    additions = {name: dict(entry, type=entry.get('type', 'symlink' if 'link' in entry else 'file'))
                 for name, entry in inputs['addedPaths'].items()}
    verify_added(root, additions)
    records = {(str(r['owner']), r['path']): r for r in audit['records']}
    local_owners = {'rootfs', 'rootfs-x86'}
    for (owner, name), record in records.items():
        if owner in local_owners:
            require(digest(leaf(root, name)) == record['sha256'], 'stale_audit:' + name)
    roots = [k for k in records if k[0] not in local_owners or k[1] not in additions
             or (additions[k[1]].get('package') in SEED_PACKAGES and not excluded(k[1]))]
    reachable, pending = set(), list(roots)
    while pending:
        key = pending.pop()
        if key in reachable:
            continue
        if key == ('box64', 'self'):
            require(('box64', 'bin/box64') in records, 'self_without_box64')
            reachable.add(key)
            continue
        require(key in records, 'uncovered_edge:' + repr(key))
        reachable.add(key)
        for provider in records[key].get('resolved', {}).values():
            require(isinstance(provider, str) and ':' in provider, 'invalid_provider')
            pending.append(tuple(provider.split(':', 1)))
    for owner, name in reachable:
        require(owner not in local_owners or not excluded(name), 'excluded_still_required:' + name)
    removals = {}
    for name, entry in additions.items():
        if entry['type'] != 'file':
            continue
        p = leaf(root, name)
        with p.open('rb') as stream:
            is_elf = stream.read(4) == b'\x7fELF'
        if not is_elf:
            continue  # Preserve data, configuration and copyright provenance.
        keys = [key for key in records if key[0] in local_owners and key[1] == name]
        require(bool(keys), 'uncovered_added_elf:' + name)
        if not any(key in reachable for key in keys):
            removals[name] = dict(entry, reason=('UserExcludedPlugin' if excluded(name)
                                               else 'ExclusiveDependencyOfExcludedPlugins'))
    # Add only real links resolving to the exact files selected above.
    for name, entry in additions.items():
        if entry['type'] != 'symlink':
            continue
        p = leaf(root, name)
        target = p.resolve(strict=True)
        require(target.is_relative_to(root), 'link_escape:' + name)
        if target.relative_to(root).as_posix() in removals:
            removals[name] = dict(entry, reason='LinkToRemovedProvider')
    return {
        'schemaVersion': 1, 'state': 'PlannedUserScopedOptionalPluginRemoval',
        'sourceRoot': str(root), 'auditSha256': hashlib.sha256(json.dumps(audit, sort_keys=True).encode()).hexdigest(),
        'sourceAddedPaths': additions, 'roots': [list(k) for k in roots],
        'removedPaths': removals,
        'removedBytes': sum(r.get('bytes', 0) for r in removals.values()),
        'limitations': ['Static graph proof; remaining plugins and actual runtime still require verification.',
                        'Original image leaves, Wine modules, shared dependencies and non-ELF data are retained.'],
    }


def apply(root, value, audit, inputs):
    root = root.resolve(strict=True)
    require(root != Path(value['sourceRoot']).resolve(strict=True), 'original_root_forbidden')
    require(value['state'] == 'PlannedUserScopedOptionalPluginRemoval', 'invalid_plan')
    require(value == plan(Path(value['sourceRoot']), audit, inputs), 'plan_changed')
    verify_added(root, value['sourceAddedPaths'])
    # Validate all removals before the first mutation.
    for name, entry in value['removedPaths'].items():
        source = value['sourceAddedPaths'].get(name)
        require(source is not None and all(entry[k] == v for k, v in source.items()), 'undeclared_removal')
        leaf(root, name)
    for name in value['removedPaths']:
        leaf(root, name).unlink()
    return dict(value, state='AppliedInIndependentCandidate', targetRoot=str(root))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    p = sub.add_parser('plan')
    for name in ('root', 'audit', 'inputs', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p = sub.add_parser('apply')
    for name in ('root', 'plan', 'audit', 'inputs', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.operation == 'plan':
        value = plan(args.root, json.loads(args.audit.read_text()), json.loads(args.inputs.read_text()))
    else:
        value = apply(args.root, json.loads(args.plan.read_text()),
                      json.loads(args.audit.read_text()), json.loads(args.inputs.read_text()))
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'state': value['state'], 'removedPaths': len(value['removedPaths']),
                      'removedBytes': value['removedBytes']}))


if __name__ == '__main__':
    main()
