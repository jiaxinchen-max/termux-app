#!/usr/bin/env python3
"""Build an unpublished Ubuntu Wine image on an ARM64 Termux build device.

This command never publishes a release or replaces the installed shared root.
Run from a complete source checkout; retain the work directory as build evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tarfile

SOURCE_SHA = '8deeb1286d8227d267d261420ecd04321c9670802a3ce1b70da807a4d8d54885'
SOURCE_BYTES = 125532160
NAME = 'wine-ubuntu2604-build'


def require(value, reason):
    if not value:
        raise ValueError(reason)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def build_container_root(prefix):
    root = prefix / 'var/lib/proot-distro/containers' / NAME / 'rootfs'
    # Reject an aliased parent before guest apt can modify any existing tree.
    for path in (root, *root.parents):
        require(not path.is_symlink(), 'build_root_symlink')
        if path == prefix:
            break
    if root.exists():
        require(root.is_dir() and root.resolve(strict=True) == root, 'build_root_alias')
    return root


def prepare_hangover(work, target):
    require(not target.is_symlink() and target.is_file(), 'source_file_invalid')
    require(target.stat().st_size == SOURCE_BYTES and digest(target) == SOURCE_SHA,
            'hangover_ubuntu_source_digest')
    destination = work / 'hangover'
    require(not destination.is_symlink(), 'hangover_destination_symlink')
    with tarfile.open(target) as archive:
        members = [m for m in archive if m.isfile() and m.name.endswith('.deb')]
        require(members and len({Path(m.name).name for m in members}) == len(members),
                'hangover_deb_members')
        names = {Path(m.name).name for m in members}
        if destination.exists():
            require(destination.is_dir() and {p.name for p in destination.iterdir()} == names,
                    'hangover_destination_has_unexpected_inputs')
            for member in members:
                output = destination / Path(member.name).name
                require(output.is_file() and not output.is_symlink() and
                        digest(output) == hashlib.sha256(archive.extractfile(member).read()).hexdigest(),
                        'hangover_existing_input_changed')
            return
        destination.mkdir()
        for member in members:
            output = destination / Path(member.name).name
            require(not output.is_symlink(), 'hangover_deb_symlink')
            with archive.extractfile(member) as source, output.open('wb') as stream:
                shutil.copyfileobj(source, stream)


def remove_host_scaffolding(root):
    """PRoot-distro creates empty Android bind targets, some with mode 000.

    Only directory scaffolding may be removed. Unexpected data blocks packing.
    This runs after the build session has exited, never against an installed root.
    """
    directories = []
    def inspect(directory):
        mode = directory.lstat().st_mode
        require(stat.S_ISDIR(mode), 'host_scaffolding_contains_data')
        directories.append((directory, stat.S_IMODE(mode)))
        directory.chmod(stat.S_IMODE(mode) | 0o700)
        for child in directory.iterdir():
            inspect(child)
    try:
        for name in ('apex', 'data', 'linkerconfig', 'odm', 'product', 'system', 'system_ext', 'vendor'):
            target = root / name
            if target.exists() or target.is_symlink():
                inspect(target)
    except BaseException:
        for directory, mode in reversed(directories):
            directory.chmod(mode)
        raise
    for directory, _ in reversed(directories):
        directory.rmdir()


def install_compatibility_archive(root, archive, lock_path, namespace, documentation):
    """Install only locked compatibility files; never replace Ubuntu libraries.

    Validate the whole archive and existing destinations before writing any file.
    This operates only on the builder's private root, before trimming/packing.
    """
    lock = json.loads(lock_path.read_text())
    require(not archive.is_symlink() and archive.is_file() and
            archive.stat().st_size == lock['bytes'] and digest(archive) == lock['sha256'],
            namespace + '_archive_digest')
    payload = {}
    with tarfile.open(archive) as tar:
        for member in tar:
            name = member.name
            plugin = (namespace == 'ubuntu_media' and
                      Path(name).parent.as_posix() == 'usr/lib/aarch64-linux-gnu/gstreamer-1.0' and
                      Path(name).name.startswith('libgst') and name.endswith('.so') and member.isfile())
            require(name in lock['members'] and name not in payload and
                    (plugin or name.startswith(('usr/local/lib/', 'usr/local/share/doc/' + documentation + '/'))) and
                    all(p not in ('', '.', '..') for p in name.split('/')),
                    namespace + '_archive_member')
            expected = lock['members'][name]
            if expected['type'] == 'file':
                require(member.isfile() and member.size == expected['bytes'], namespace + '_member_type')
                data = tar.extractfile(member).read()
                require(hashlib.sha256(data).hexdigest() == expected['sha256'],
                        namespace + '_member_digest')
            else:
                require(expected['type'] == 'symlink' and member.issym() and
                        member.linkname == expected['link'] and '/' not in member.linkname and
                        member.linkname not in ('', '.', '..'), namespace + '_member_link')
                target = str(Path(name).parent / member.linkname)
                require(lock['members'].get(target, {}).get('type') == 'file', namespace + '_link_target')
                data = member.linkname
            payload[name] = data
    require(payload.keys() == lock['members'].keys(), namespace + '_missing_members')
    for name, data in payload.items():
        destination = root / name
        for parent in destination.parents:
            require(not parent.is_symlink() and (not parent.exists() or parent.is_dir()),
                    namespace + '_parent_alias')
            if parent == root:
                break
        if destination.exists() or destination.is_symlink():
            if isinstance(data, str):
                require(destination.is_symlink() and os.readlink(destination) == data,
                        namespace + '_existing_file_changed')
            else:
                require(not destination.is_symlink() and destination.is_file() and
                        digest(destination) == lock['members'][name]['sha256'],
                        namespace + '_existing_file_changed')
    for name, data in payload.items():
        destination = root / name
        if destination.exists() or destination.is_symlink():
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, str):
            destination.symlink_to(data)
        else:
            with destination.open('xb') as stream:
                stream.write(data)
            destination.chmod(0o644)
    return lock

def install_ffmpeg4(root, archive, lock_path):
    return install_compatibility_archive(root, archive, lock_path, 'ffmpeg4', 'idap-ffmpeg4')


def install_ubuntu_media(root, archive, lock_path):
    return install_compatibility_archive(root, archive, lock_path, 'ubuntu_media', 'idap-ubuntu-media')



def prepare_image_homes(root, work):
    """Keep official home scaffolding and build caches outside the shipped root.

    Unknown home contents remain a packing error. Nothing is deleted here.
    """
    for name in ('home', 'root', 'root/.cache'):
        require(not (root / name).is_symlink(), 'build_home_alias')
    ubuntu = root / 'home/ubuntu'
    cache = root / 'root/.cache/box64'
    moves = []
    if ubuntu.exists() or ubuntu.is_symlink():
        names = {'.bash_logout', '.bashrc', '.profile'}
        require(not ubuntu.is_symlink() and ubuntu.is_dir() and
                {p.name for p in ubuntu.iterdir()} == names, 'unexpected_default_home_data')
        for name in names:
            source, reference = ubuntu / name, root / 'etc/skel' / name
            require(not source.is_symlink() and source.is_file() and
                    not reference.is_symlink() and reference.is_file() and
                    digest(source) == digest(reference), 'modified_default_home_data')
        moves.append(('ubuntu', ubuntu))
    if cache.exists() or cache.is_symlink():
        require(not cache.is_symlink() and cache.is_dir() and
                all(p.is_file() and not p.is_symlink() and p.name.endswith('.box64')
                    for p in cache.iterdir()), 'unexpected_box64_cache_data')
        moves.append(('box64', cache))
    destination = work / 'build-home-scaffolding'
    require(not destination.is_symlink(), 'build_home_destination_alias')
    for name, _ in moves:
        require(not (destination / name).exists() and not (destination / name).is_symlink(),
                'build_home_destination_exists')
    if moves:
        destination.mkdir(exist_ok=True)
    records = {}
    for name, source in moves:
        records[name] = {p.name: digest(p) for p in source.iterdir()}
        source.rename(destination / name)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--hangover-archive', type=Path, required=True,
                        help='Verified Ubuntu-adapted input produced by package-hangover-ubuntu.py')
    parser.add_argument('--ffmpeg4-archive', type=Path, required=True,
                        help='Pinned Ubuntu-built FFmpeg 4 compatibility libraries')
    parser.add_argument('--ubuntu-media-archive', type=Path, required=True,
                        help='Pinned, unmodified Ubuntu GStreamer plugins and compatibility libraries')
    parser.add_argument('--reuse-build-container', action='store_true',
                        help='Explicitly reuse only the isolated, already prepared build container')
    args = parser.parse_args()
    require(os.uname().machine == 'aarch64', 'requires_arm64_termux_build_device')
    prefix = Path(os.environ['PREFIX']).resolve(strict=True)
    files = prefix.parent
    require(prefix.name == 'usr' and (prefix / 'bin/proot-distro').is_file(), 'termux_prefix_invalid')
    work = args.work.absolute()
    work.mkdir(parents=True, exist_ok=True)
    require(work == work.resolve(strict=True) and files in work.parents and
            work not in (files, prefix) and prefix not in work.parents, 'private_build_work_invalid')
    here = Path(__file__).resolve().parent
    assets = here.parents[1] / 'termux-ui/src/main/assets/local-games'
    image_lock = json.loads((here / 'ubuntu-wine-rootfs/ubuntu-oci-lock.json').read_text())
    root = build_container_root(prefix)
    if root.exists():
        require(args.reuse_build_container, 'build_container_exists_requires_explicit_reuse')
    else:
        subprocess.run([str(prefix / 'bin/proot-distro'), 'install', '--name', NAME,
                        image_lock['reference']], check=True)
    build_container_root(prefix)
    # On an explicit reuse, require the original official OCI manifest to be in the verified cache.
    cached = prefix / 'var/lib/proot-distro/cache/oci_manifests'
    require(any(json.loads(p.read_text()).get('manifest') == image_lock['manifest']
                for p in cached.glob('*.json')), 'official_base_manifest_not_found')
    prepare_hangover(work, args.hangover_archive)
    helpers = ['desktop_container_statx_setup.sh', 'desktop_container_apt_sources.sh',
               'desktop_container_statx_aarch64.bin', 'desktop_container_run_config.sh']
    for name in helpers:
        shutil.copyfile(assets / name, work / name)
    shutil.copyfile(here / 'setup-ubuntu-wine-rootfs.sh', work / 'setup-ubuntu-wine-rootfs.sh')
    package_lock = here / 'ubuntu-wine-rootfs/ubuntu-packages.tsv'
    shutil.copyfile(package_lock, work / 'ubuntu-packages.lock.tsv')
    shutil.copyfile(assets / 'runtime-rootfs-v13/wine-runtime.properties', work / 'wine-runtime.properties')
    trust = root / 'etc/ssl/certs/ca-certificates.crt'
    trust.parent.mkdir(parents=True, exist_ok=True)
    require(not trust.is_symlink(), 'build_trust_symlink')
    shutil.copyfile(prefix / 'etc/tls/cert.pem', trust)
    subprocess.run([str(prefix / 'bin/proot-distro'), 'login', NAME, '--user', 'root',
                    '--bind', str(work) + ':/run/wine-build', '--', '/bin/sh',
                    '/run/wine-build/setup-ubuntu-wine-rootfs.sh'], check=True)
    compatibility_lock = here / 'ubuntu-wine-rootfs/ffmpeg4-compatibility-lock.json'
    ffmpeg4 = install_ffmpeg4(root, args.ffmpeg4_archive, compatibility_lock)
    ubuntu_media = install_ubuntu_media(root, args.ubuntu_media_archive,
        here / 'ubuntu-wine-rootfs/ubuntu-media-lock.json')
    subprocess.run([str(prefix / 'bin/proot-distro'), 'login', NAME, '--user', 'root',
                    '--', '/sbin/ldconfig'], check=True)
    # Recompute every source file against the actual Ubuntu tree. The original
    # Debian bundle is an input reference, never the output used for packing.
    prefix_assets = work / 'candidate-prefixes/prefix-hangover-pdb2'
    subprocess.run([str(prefix / 'bin/python'),
                    str(here / 'ubuntu-wine-rootfs/requalify-prefix.py'),
                    '--source', str(here / 'prefix-assets/prefix-hangover-pdb2'),
                    '--rootfs', str(root), '--recipe', str(assets / 'runtime-rootfs-v13'),
                    '--output', str(prefix_assets), '--source-sha256', SOURCE_SHA], check=True)
    home_scaffolding = prepare_image_homes(root, work)
    inputs = {'schemaVersion': 1, 'baseImage': image_lock, 'sourceFile': args.hangover_archive.name,
              'sourceSha256': SOURCE_SHA, 'sourceBytes': SOURCE_BYTES,
              'ffmpeg4': ffmpeg4,
              'ubuntuMedia': ubuntu_media,
              'packageLockSha256': digest(package_lock),
              'homeScaffolding': home_scaffolding,
              'helpers': {name: digest(work / name) for name in helpers},
              'prefixBundleSha256': digest(prefix_assets / 'bundle.json'),
              'buildTools': {name: digest(here / name) for name in
                             ('build-ubuntu-wine-rootfs.py', 'setup-ubuntu-wine-rootfs.sh',
                              'ubuntu-wine-rootfs/requalify-prefix.py', 'minimize-rootfs.py',
                              'audit-rootfs-elf.py', 'prebuilt_rootfs.py')},
              'guestSetupSha256': digest(work / 'setup-ubuntu-wine-rootfs.sh'),
              'packages': (work / 'ubuntu-packages.tsv').read_text(),
              'packageArchiveDigests': (work / 'ubuntu-deb-sha256.txt').read_text()}
    lock = work / 'build-inputs.json'
    lock.write_text(json.dumps(inputs, sort_keys=True, indent=2) + '\n')
    # Keep exact package inputs available after the trim removes the APT cache.
    archive_lock = here / 'ubuntu-wine-rootfs/ubuntu-deb-sha256.txt'
    expected_debs = {Path(line.split()[1]).name: line.split()[0]
                     for line in archive_lock.read_text().splitlines()}
    package_inputs = list((root / 'var/cache/apt/archives').glob('*.deb'))
    for source in package_inputs:
        require(source.is_file() and not source.is_symlink() and
                digest(source) == expected_debs.get(source.name),
                'ubuntu_package_archive_digest:' + source.name)
    debs = work / 'ubuntu-package-inputs'
    debs.mkdir(exist_ok=True)
    require(not debs.is_symlink() and debs.resolve(strict=True) == debs,
            'package_inputs_directory_invalid')
    for source in package_inputs:
        require(source.is_file() and not source.is_symlink(), 'package_input_invalid')
        target = debs / source.name
        if target.exists():
            require(not target.is_symlink() and digest(source) == digest(target), 'package_input_changed')
        else:
            # Termux Python may omit os.link on Android. Keep an independent
            # copy so retaining inputs does not rely on host hard-link support.
            shutil.copyfile(source, target)
    subprocess.run([str(prefix / 'bin/python'), str(assets / 'relocate_rootfs_links.py'), str(root)], check=True)
    remove_host_scaffolding(root)
    recipe = digest(lock)
    properties = root / 'etc/wine-runtime.properties'
    require(not properties.is_symlink(), 'runtime_properties_symlink')
    properties.write_text(''.join(line + '\n' for line in properties.read_text().splitlines()
                                  if not line.startswith('recipeSha256=')) +
                          'recipeSha256=' + recipe + '\n')
    subprocess.run([str(prefix / 'bin/python'), str(here / 'minimize-rootfs.py'), 'build',
                    '--root', str(root), '--termux-files', str(files),
                    '--assets', str(prefix_assets),
                    '--recipe', recipe, '--report', str(work / 'trim-report.json')], check=True)
    subprocess.run([str(prefix / 'bin/python'), str(here / 'prebuilt_rootfs.py'), 'pack',
                    '--root', str(root), '--output', str(work / 'rootfs-ubuntu2604-hangover-pdb2-minimal-v1.tar.zst'),
                    '--descriptor', str(work / 'image.json'),
                    '--assets', str(prefix_assets)], check=True)
    print('Candidate packed. Wine/prefix device acceptance and publication remain separate gates.')


if __name__ == '__main__':
    main()
