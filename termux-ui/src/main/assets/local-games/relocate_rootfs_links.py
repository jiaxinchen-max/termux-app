#!/usr/bin/env python3
"""Make PRoot's host-absolute .l2s hardlink emulation survive a RootFS rename.

Guest absolute links (e.g. /usr/bin) are deliberately left alone. Only flat .l2s
targets belonging to this exact RootFS are accepted; all chains are checked before
any link is replaced. Run again with --verify after the directory is published.
"""
import argparse
import json
import os
from pathlib import Path
import stat
import sys
import uuid


def relocate(root, verify=False):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("rootfs_not_directory")
    store = root / ".l2s"
    if store.is_symlink() or (store.exists() and not store.is_dir()):
        raise ValueError("rootfs_l2s_directory_invalid")
    replacements = {}
    links = {}
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            path = Path(directory) / name
            if not path.is_symlink():
                continue
            target = os.readlink(path)
            if os.path.isabs(target) and "/.l2s/" in target:
                origin, _, member = target.partition("/.l2s/")
                # Android /data/data and /data/user/0 can be bind-mount aliases,
                # so realpath strings alone do not establish directory identity.
                if (not os.path.samefile(origin, root) or not member
                        or member in (".", "..") or "/" in member):
                    raise ValueError("rootfs_l2s_target_outside_root: " + str(path))
                if verify:
                    raise ValueError("rootfs_l2s_absolute_link: " + str(path))
                target = os.path.relpath(store / member, path.parent)
                replacements[path] = target
            # Include already-normalized links and every link in the backing store.
            candidate = Path(os.path.normpath(os.path.join(path.parent, target)))
            if candidate == store or store in candidate.parents or path.parent == store:
                links[path] = target

    for path, target in links.items():
        current = Path(os.path.normpath(os.path.join(path.parent, target)))
        seen = {path}
        for _ in range(40):
            if current.parent != store or current in seen:
                raise ValueError("rootfs_l2s_chain_invalid: " + str(path))
            seen.add(current)
            mode = current.lstat().st_mode
            if stat.S_ISREG(mode):
                break
            if not stat.S_ISLNK(mode):
                raise ValueError("rootfs_l2s_entity_invalid: " + str(current))
            edge = replacements.get(current, os.readlink(current))
            if os.path.isabs(edge):
                raise ValueError("rootfs_l2s_chain_absolute: " + str(current))
            current = Path(os.path.normpath(os.path.join(current.parent, edge)))
        else:
            raise ValueError("rootfs_l2s_chain_too_long: " + str(path))

    for path, target in replacements.items():
        temporary = path.with_name(".rootfs-link-" + uuid.uuid4().hex)
        try:
            os.symlink(target, temporary)
            os.replace(temporary, path)
        finally:
            if temporary.is_symlink():
                temporary.unlink()
    return {"normalized": len(replacements), "verifiedChains": len(links)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rootfs")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(relocate(args.rootfs, args.verify)))
    except (OSError, ValueError) as error:
        print("rootfs_link_validation_failed: " + str(error), file=sys.stderr)
        sys.exit(70)
