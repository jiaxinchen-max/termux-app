#!/usr/bin/env python3
"""Build an isolated, statically verified Hangover candidate and symbols archive.

No install scripts are executed. No catalog, input, or installed runtime is
modified. PE symbols are retained as original unstripped images because LLVM's
COFF --only-keep-debug expects a Microsoft debug directory absent in Wine DWARF.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile

from runtime_binary import BinaryError, snapshot, verify


def sha(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def command(args, env=None):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, env=env)
    if result.returncode:
        raise BinaryError("command failed: " + str(args[0]) + "\n" + result.stderr)
    return result.stdout


def tool(name):
    path = shutil.which(name)
    if not path:
        raise BinaryError("required tool missing: " + name)
    return str(Path(path).resolve())


def llvm_metadata(readobj, path):
    output = command([readobj, "--coff-load-config", "--coff-imports", "--coff-exports",
                      "--coff-basereloc", "--unwind", path])
    # Unwind dumps decorate addresses with optional COFF symbol names. Stripping
    # removes those names, not the function address or unwind data. Compare the
    # addresses and every other decoded field; runtime section bytes are also
    # independently checked by verify().
    output = re.sub(r"(?m)^(\s*Function: ).+ \((0x[0-9A-Fa-f]+)\)$", r"\1\2", output)
    return "\n".join(line for line in output.splitlines() if not line.startswith("File: "))


def normalized_archive(root, output, epoch, compressed=False):
    def normalize(info):
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        info.mtime = epoch
        info.pax_headers = {}
        return info
    with output.open("wb") as stream:
        if compressed:
            with gzip.GzipFile(fileobj=stream, filename="", mode="wb", mtime=epoch, compresslevel=6) as gz:
                with tarfile.open(fileobj=gz, mode="w|", format=tarfile.GNU_FORMAT) as archive:
                    for entry in sorted(root.iterdir()):
                        archive.add(entry, arcname=entry.name, filter=normalize)
        else:
            with tarfile.open(fileobj=stream, mode="w|", format=tarfile.GNU_FORMAT) as archive:
                for entry in sorted(root.iterdir()):
                    archive.add(entry, arcname=entry.name, filter=normalize)


def regular_files(root):
    # Do not follow guest symlinks, including a malicious symlinked directory.
    for directory, directories, files in os.walk(root, followlinks=False):
        directories[:] = sorted(d for d in directories if not (Path(directory) / d).is_symlink())
        for name in sorted(files):
            path = Path(directory) / name
            if path.is_symlink():
                continue
            if not stat.S_ISREG(path.stat().st_mode):
                raise BinaryError("unsupported package entry: " + str(path))
            yield path


def fields(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line[0].isspace() and ": " in line:
            key, value = line.split(": ", 1)
            result[key] = value
    for key in ("Package", "Version", "Architecture"):
        if key not in result or not re.fullmatch(r"[A-Za-z0-9.+:~_-]+", result[key]):
            raise BinaryError("missing or unsafe package field: " + key)
    return result


def update_control(root, version):
    control = root / "DEBIAN/control"
    total = sum((p.stat().st_size + 1023) // 1024 for p in regular_files(root)
                if "DEBIAN" not in p.relative_to(root).parts[:1])
    text = control.read_text()
    text = re.sub(r"(?m)^Version: .*$", "Version: " + version, text)
    if re.search(r"(?m)^Installed-Size: ", text):
        text = re.sub(r"(?m)^Installed-Size: .*$", "Installed-Size: " + str(total), text)
    else:
        text = "Installed-Size: " + str(total) + "\n" + text
    control.write_text(text)
    # dpkg checks the new candidate, not hashes for the original unstripped files.
    checksums = []
    for path in sorted(regular_files(root)):
        rel = path.relative_to(root)
        if rel.parts[0] != "DEBIAN":
            checksums.append(sha(path, "md5") + "  " + rel.as_posix() + "\n")
    (root / "DEBIAN/md5sums").write_text("".join(checksums))
    return total


def extract_deb(dpkg, package, destination):
    # dpkg-deb extracts data/control without executing maintainer scripts. Check
    # the trusted, digest-pinned package's members first for host path traversal
    # and unexpected ownership that root-owner-group would silently change.
    for option in ("--fsys-tarfile", "--ctrl-tarfile"):
        proc = subprocess.Popen([dpkg, option, str(package)], stdout=subprocess.PIPE)
        try:
            with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
                seen = set()
                for member in archive:
                    parts = Path(member.name).parts
                    if member.name.startswith("/") or ".." in parts or member.name in seen:
                        raise BinaryError("unsafe or duplicate deb member")
                    seen.add(member.name)
                    if member.uid != 0 or member.gid != 0:
                        raise BinaryError("non-root deb ownership is not supported")
                    if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                        raise BinaryError("special deb entry is not supported")
            # tar iteration stops at its end marker; drain producer padding so
            # a healthy dpkg-deb is not killed or reported as a broken pipe.
            while proc.stdout.read(1024 * 1024):
                pass
            status = proc.wait()
        except BaseException:
            proc.terminate()
            proc.wait()
            raise
        finally:
            proc.stdout.close()
        if status:
            raise BinaryError("deb member verification failed")
    command([dpkg, "--raw-extract", package, destination])


def split_package(package, work, runtime, symbols, args, tools):
    root = work / package.name
    extract_deb(tools["dpkg"], package, root)
    original_control = fields(root / "DEBIAN/control")
    version = original_control["Version"] + "+idap." + args.revision
    changed = []
    before_bytes = 0
    after_bytes = 0
    for path in regular_files(root):
        relative = path.relative_to(root)
        if relative.parts[0] == "DEBIAN":
            continue
        before_bytes += path.stat().st_size
        with path.open("rb") as stream:
            magic = stream.read(4)
        if not (magic.startswith(b"MZ") or magic == b"\x7fELF"):
            after_bytes += path.stat().st_size
            continue
        before = snapshot(path)
        if not before["debug_raw_bytes"]:
            after_bytes += path.stat().st_size
            continue
        symbol = symbols / original_control["Package"] / relative
        symbol.parent.mkdir(parents=True, exist_ok=True)
        if before["format"] == "PE":
            shutil.copy2(path, symbol)
            symbol_kind = "original-unstripped-pe"
        else:
            symbol = symbol.with_name(symbol.name + ".debug")
            command([tools["objcopy"], "--only-keep-debug", path, symbol])
            extracted = snapshot(symbol)
            old_debug = [(s["name"], s["sha256"]) for s in before["sections"] if s["debug"]]
            new_debug = [(s["name"], s["sha256"]) for s in extracted["sections"] if s["debug"]]
            if old_debug != new_debug:
                raise BinaryError("ELF debug extraction changed debug contents")
            symbol_kind = "elf-separate-debug"
        candidate = path.with_name(path.name + ".idap-debugsplit")
        if candidate.exists() or candidate.is_symlink():
            raise BinaryError("candidate name collides with package content")
        original_mode = stat.S_IMODE(path.stat().st_mode)
        command([tools["objcopy"], "--strip-debug", "--strip-unneeded", path, candidate])
        after = snapshot(candidate)
        padding_changes = verify(before, after)
        if before["format"] == "PE":
            metadata = llvm_metadata(tools["readobj"], path)
            if metadata != llvm_metadata(tools["readobj"], candidate):
                raise BinaryError("LLVM PE loader metadata changed: " + str(relative))
        else:
            metadata = "ELF allocated sections and program segments verified"
        os.chmod(candidate, original_mode)
        os.replace(candidate, path)
        after_bytes += path.stat().st_size
        changed.append({"path": relative.as_posix(), "before": before, "after": after,
                        "symbol_path": symbol.relative_to(symbols).as_posix(),
                        "symbol_sha256": sha(symbol), "symbol_kind": symbol_kind,
                        "loader_metadata_sha256": hashlib.sha256(metadata.encode()).hexdigest(),
                        "executable_tail_padding_changes": padding_changes})
        if len(changed) % 100 == 0:
            print(original_control["Package"], "verified", len(changed), "binaries", flush=True)
    installed_kib = update_control(root, version)
    for directory, directories, files in os.walk(root, topdown=False, followlinks=False):
        for name in directories + files:
            os.utime(Path(directory) / name, (args.epoch, args.epoch), follow_symlinks=False)
        os.utime(directory, (args.epoch, args.epoch))
    output = runtime / (original_control["Package"] + "_" + version + "_" +
                        original_control["Architecture"] + ".deb")
    env = dict(os.environ, SOURCE_DATE_EPOCH=str(args.epoch), DPKG_DEB_THREADS_MAX="1", LC_ALL="C")
    command([tools["dpkg"], "--build", "--root-owner-group", "-Zxz", "-z6", root, output], env)
    result = {"source": package.name, "source_sha256": sha(package),
              "package": original_control["Package"], "source_version": original_control["Version"],
              "candidate_version": version, "architecture": original_control["Architecture"],
              "source_file_bytes": before_bytes, "candidate_file_bytes": after_bytes,
              "candidate_installed_size_kib": installed_kib, "processed_binaries": changed,
              "output": output.name, "output_sha256": sha(output), "output_bytes": output.stat().st_size}
    print(result["package"], "file bytes", before_bytes, "->", after_bytes, flush=True)
    return result


def run(args):
    if not re.fullmatch(r"[0-9a-f]{64}", args.sha256):
        raise BinaryError("input SHA-256 must be explicit and lowercase")
    if not re.fullmatch(r"[a-z0-9][a-z0-9.+~]{0,40}", args.revision) or args.epoch < 0:
        raise BinaryError("invalid revision or epoch")
    source = args.input.resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or output.is_symlink():
        raise BinaryError("output must be a new directory")
    original_hash = sha(source)
    if original_hash != args.sha256:
        raise BinaryError("input SHA-256 mismatch")
    tools = {"objcopy": tool(args.objcopy), "readobj": tool(args.readobj), "dpkg": tool("dpkg-deb")}
    versions = {key: command([value, "--version"]).splitlines()[:4] for key, value in tools.items()}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".hangover-debugsplit-", dir=output.parent) as temporary:
        work = Path(temporary)
        inputs, runtime, symbols, publish = (work / p for p in ("input", "runtime", "symbols", "publish"))
        for directory in (inputs, runtime, symbols, publish):
            directory.mkdir()
        with tarfile.open(source) as archive:
            members = archive.getmembers()
            if not members or len(members) > 128:
                raise BinaryError("unexpected Hangover source member count")
            names = set()
            for member in members:
                if (not member.isfile() or not re.fullmatch(r"[A-Za-z0-9._+~:-]+", member.name)
                        or member.name in (".", "..") or member.name in names):
                    raise BinaryError("source must contain unique flat regular files")
                names.add(member.name)
                with archive.extractfile(member) as src, (inputs / member.name).open("wb") as dst:
                    shutil.copyfileobj(src, dst)
        packages = sorted(inputs.glob("*.deb"))
        if not packages:
            raise BinaryError("source contains no Debian packages")
        results = [split_package(p, work, runtime, symbols, args, tools) for p in packages]
        for path in inputs.iterdir():
            if path.suffix != ".deb":
                shutil.copy2(path, runtime / path.name)
        if sha(source) != original_hash:
            raise BinaryError("source changed during processing")
        manifest = {"schemaVersion": 1, "state": "StaticVerifiedNotRuntimeTested",
                    "source_sha256": original_hash, "source_bytes": source.stat().st_size,
                    "tools": tools, "tool_versions": versions, "epoch": args.epoch,
                    "symbol_policy": "Original PE images with DWARF; separate ELF debug files; never installed with runtime",
                    "packages": results}
        text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        (symbols / "symbols-manifest.json").write_text(text)
        payload = publish / "hangover-runtime-candidate.tar"
        debug = publish / "hangover-symbols.tar.gz"
        normalized_archive(runtime, payload, args.epoch)
        normalized_archive(symbols, debug, args.epoch, compressed=True)
        manifest["artifacts"] = [{"name": p.name, "sha256": sha(p), "bytes": p.stat().st_size}
                                 for p in (payload, debug)]
        (publish / "candidate-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        os.rename(publish, output)
    print("Candidate published locally:", output, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--objcopy", default="llvm-objcopy-19")
    parser.add_argument("--readobj", default="llvm-readobj-19")
    parser.add_argument("--revision", default="debugsplit1")
    parser.add_argument("--epoch", type=int, default=0)
    args = parser.parse_args()
    try:
        run(args)
    except (BinaryError, OSError, ValueError, tarfile.TarError, UnicodeError) as error:
        print("FAILED:", error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
