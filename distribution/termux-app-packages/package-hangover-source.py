#!/usr/bin/env python3
"""Package a source-built Wine deb, matching PDBs and unchanged emulator inputs.

Creates local candidates only; no install, catalog change or remote publication.
"""
import argparse
from email.parser import Parser
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile

from runtime_binary import BinaryError, snapshot


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


pack = module('hangover_packaging', 'split-hangover-debug.py')
symbols_gate = module('hangover_symbols', 'verify-hangover-pdb.py')


def package_fields(path):
    output = pack.command(['dpkg-deb', '-f', path, 'Package', 'Version', 'Architecture'])
    fields = dict(line.split(': ', 1) for line in output.splitlines())
    return fields['Package'], fields['Version'], fields['Architecture']


def runtime_inventory(root):
    result = {}
    for path in sorted((root / 'usr').rglob('*')):
        if path.is_symlink():
            result[path.relative_to(root).as_posix()] = 'symlink:' + os.readlink(path)
        elif path.is_file():
            result[path.relative_to(root).as_posix()] = 'file:' + oct(path.stat().st_mode & 0o7777)
    if not result:
        raise BinaryError('empty Wine runtime inventory')
    return result


def run(args):
    lock = json.loads(args.lock.read_text())
    if lock['schemaVersion'] != 1 or lock['crossDebug'] != 'pdb':
        raise BinaryError('unsupported source lock')
    if pack.sha(args.original) != lock['originalComponent']['sha256']:
        raise BinaryError('original component SHA-256 mismatch')
    wine_hash, debug_hash = pack.sha(args.wine_deb), pack.sha(args.dbgsym_deb)
    expected = lock['debianRevision']
    if package_fields(args.wine_deb) != ('hangover-wine', expected, 'arm64'):
        raise BinaryError('source Wine package identity mismatch')
    if package_fields(args.dbgsym_deb) != ('hangover-wine-dbgsym', expected, 'arm64'):
        raise BinaryError('ELF dbgsym package identity mismatch')
    output = args.output.absolute()
    if output.exists() or output.is_symlink():
        raise BinaryError('output must be a new directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.hangover-source-', dir=output.parent) as temporary:
        work = Path(temporary)
        original, payload, symbols, publish = [work / p for p in ('original', 'payload', 'symbols', 'publish')]
        for directory in (original, payload, symbols, publish):
            directory.mkdir()
        with tarfile.open(args.original) as archive:
            members = archive.getmembers()
            if not 1 <= len(members) <= 128:
                raise BinaryError('unexpected original member count')
            names = set()
            for member in members:
                if (not member.isfile() or not re.fullmatch(r'[A-Za-z0-9._+~:-]+', member.name)
                        or member.name in ('.', '..') or member.name in names):
                    raise BinaryError('unsafe original archive member')
                names.add(member.name)
                with archive.extractfile(member) as src, (original / member.name).open('xb') as dst:
                    shutil.copyfileobj(src, dst)
        wine_inputs = sorted(original.glob('hangover-wine_*.deb'))
        if len(wine_inputs) != 1:
            raise BinaryError('expected exactly one original Wine deb')
        old_root, new_root = work / 'old-root', work / 'new-root'
        pack.extract_deb('dpkg-deb', wine_inputs[0], old_root)
        pack.extract_deb('dpkg-deb', args.wine_deb, new_root)
        old_inventory = runtime_inventory(old_root)
        new_inventory = runtime_inventory(new_root)
        missing = [path for path, kind in old_inventory.items() if new_inventory.get(path) != kind]
        if missing:
            raise BinaryError('Wine runtime paths removed or changed type/permissions: ' + repr(missing[:20]))
        added = sorted(new_inventory.keys() - old_inventory.keys())
        if added:
            raise BinaryError('Wine runtime paths added outside baseline: ' + repr(added[:20]))
        old_control = Parser().parsestr((old_root / 'DEBIAN/control').read_text())
        new_control = Parser().parsestr((new_root / 'DEBIAN/control').read_text())
        control_fields = ('Pre-Depends', 'Depends', 'Recommends', 'Conflicts',
                          'Replaces', 'Provides', 'Multi-Arch')
        for field in control_fields:
            if old_control.get(field) != new_control.get(field):
                raise BinaryError('Wine package contract changed: ' + field)
        for name in ('preinst', 'postinst', 'prerm', 'postrm', 'triggers', 'conffiles'):
            left, right = old_root / 'DEBIAN' / name, new_root / 'DEBIAN' / name
            if left.exists() != right.exists() or (left.exists() and
                    (left.read_bytes() != right.read_bytes() or
                     left.stat().st_mode & 0o7777 != right.stat().st_mode & 0o7777)):
                raise BinaryError('Wine package maintenance metadata changed: ' + name)
        report = symbols_gate.verify_tree(new_root / 'usr/lib/wine', args.pdb_root,
                                          args.pdbutil, args.readobj)
        for image in report['images']:
            old_path = old_root / 'usr/lib/wine' / image['path']
            if not old_path.is_file():
                continue  # Additional modules are reported by the inventory.
            old = snapshot(old_path)
            if symbols_gate.export_inventory(old_path.read_bytes()) != image['exports']:
                raise BinaryError('Wine export capability changed: ' + image['path'])
            if old['machine'] in (0xaa64, 0xa641, 0xa64e):
                old_family, _ = symbols_gate.loader_metadata(old_path, args.readobj)
                if old_family != image['loader_format']:
                    raise BinaryError('Wine hybrid loader format changed: ' + image['path'])
            for key in ('machine', 'section_alignment', 'file_alignment', 'wine_builtin'):
                if old.get(key) != image['image'][key]:
                    raise BinaryError('Wine PE architecture/alignment/marker changed: ' +
                                      image['path'] + ':' + key)
        # Copy only the PDBs referenced by the installed runtime, preserving their
        # relative paths and checking again after copying to freeze this snapshot.
        referenced = {entry['symbol']['path']: entry['symbol']
                      for image in report['images'] for entry in image['symbols']}
        for relative, info in sorted(referenced.items()):
            destination = symbols / 'pdb' / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.pdb_root / relative, destination)
            if pack.sha(destination) != info['sha256']:
                raise BinaryError('PDB changed during packaging')
        shutil.copy2(args.dbgsym_deb, symbols / args.dbgsym_deb.name)
        shutil.copy2(args.wine_deb, payload / args.wine_deb.name)
        if (pack.sha(symbols / args.dbgsym_deb.name) != debug_hash or
                pack.sha(payload / args.wine_deb.name) != wine_hash):
            raise BinaryError('source Debian packages changed during packaging')
        reused = []
        for path in sorted(original.iterdir()):
            if path == wine_inputs[0]:
                continue
            shutil.copy2(path, payload / path.name)
            reused.append({'name': path.name, 'sha256': pack.sha(path), 'bytes': path.stat().st_size})
        reused_packages = {package_fields(p)[0] for p in payload.glob('*.deb') if p.name != args.wine_deb.name}
        if reused_packages != {'hangover-libarm64ecfex', 'hangover-libwow64fex', 'hangover-wowbox64'}:
            raise BinaryError('unexpected reused emulator packages')
        (symbols / 'pdb-manifest.json').write_text(json.dumps(report, indent=2) + '\n')
        (symbols / 'source.lock.json').write_text(json.dumps(lock, indent=2) + '\n')
        def size(root):
            return sum(p.stat().st_size for p in pack.regular_files(root))
        manifest = {'schemaVersion': 1, 'state': 'SymbolsVerifiedNotRuntimeTested',
                    'source_lock': lock, 'wine_deb_sha256': wine_hash,
                    'elf_dbgsym_sha256': debug_hash, 'reused': reused,
                    'wine_regular_file_bytes_before': size(old_root),
                    'wine_regular_file_bytes_after': size(new_root),
                    'runtime_inventory_before': len(old_inventory),
                    'runtime_inventory_after': len(new_inventory),
                    'baseline_package_contract': 'MatchedInventoryModesDependenciesMaintainerMetadata',
                    'pdb_files': len(referenced),
                    'limitations': ['Loader, ABI and device runtime acceptance still required.',
                                    'ELF dbgsym Build-ID mappings require a separate gate.',
                                    'Ordinal-only exports use a bounded physical-view decoder; hybrid export coverage is pending.',
                                    'FEX/Box64 emulator debs are reused, not rebuilt.',
                                    'APT dependency versions and build logs must accompany this candidate.']}
        runtime_tar = publish / 'hangover-runtime-source-candidate.tar'
        symbols_tar = publish / 'hangover-source-symbols.tar.gz'
        pack.normalized_archive(payload, runtime_tar, lock['sourceDateEpoch'])
        pack.normalized_archive(symbols, symbols_tar, lock['sourceDateEpoch'], compressed=True)
        manifest['artifacts'] = [{'name': p.name, 'sha256': pack.sha(p), 'bytes': p.stat().st_size}
                                 for p in (runtime_tar, symbols_tar)]
        if (pack.sha(args.original) != lock['originalComponent']['sha256'] or
                pack.sha(args.wine_deb) != wine_hash or pack.sha(args.dbgsym_deb) != debug_hash):
            raise BinaryError('source inputs changed before publication')
        (publish / 'candidate-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        os.rename(publish, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('original', 'wine-deb', 'dbgsym-deb', 'pdb-root', 'lock', 'output'):
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--pdbutil', default='llvm-pdbutil')
    parser.add_argument('--readobj', default='llvm-readobj')
    args = parser.parse_args()
    try:
        run(args)
    except (BinaryError, OSError, ValueError, KeyError, tarfile.TarError) as error:
        print('FAILED:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
