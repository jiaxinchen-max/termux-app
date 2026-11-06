#!/usr/bin/env python3
"""Integrity, failure-publication and reproducibility tests for debug splitting."""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest

from runtime_binary import BinaryError, snapshot, verify

SCRIPT = Path(__file__).with_name("split-hangover-debug.py")


def pe_fixture():
    """Small loadable-layout PE with a real COFF long-name DWARF section."""
    data = bytearray(0x600)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 0x80)
    data[64:80] = b"Wine builtin DLL"
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 0x84, 0xaa64, 2, 0, 0x600, 0, 240, 0x2022)
    op = 0x98
    struct.pack_into("<H", data, op, 0x20b)
    struct.pack_into("<II", data, op + 4, 512, 512)
    struct.pack_into("<Q", data, op + 24, 0x180000000)
    struct.pack_into("<II", data, op + 32, 4096, 512)
    struct.pack_into("<II", data, op + 56, 0x3000, 512)
    struct.pack_into("<H", data, op + 68, 3)
    struct.pack_into("<I", data, op + 108, 16)
    struct.pack_into("<8sIIIIIIHHI", data, op + 240,
                     b".text", 16, 0x1000, 512, 512, 0, 0, 0, 0, 0x60000020)
    struct.pack_into("<8sIIIIIIHHI", data, op + 280,
                     b"/4", 16, 0x2000, 512, 1024, 0, 0, 0, 0, 0x42000040)
    data[512:528] = b"\xc0\x03\x5f\xd6" * 4  # ARM64 ret, with untouched runtime bytes
    data[1024:1040] = bytes(range(16))
    strings = b".debug_info\0"
    data.extend(struct.pack("<I", 4 + len(strings)) + strings)
    return data


class IntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("llvm-objcopy-19"):
            raise unittest.SkipTest("LLVM 19 is required")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source.dll"
        self.source.write_bytes(pe_fixture())
        self.candidate = self.root / "candidate.dll"
        subprocess.run(["llvm-objcopy-19", "--strip-debug", "--strip-unneeded",
                        str(self.source), str(self.candidate)], check=True, capture_output=True)
        self.before = snapshot(self.source)
        self.after = snapshot(self.candidate)

    def tearDown(self):
        self.temp.cleanup()

    def test_pe_runtime_and_builtin_survive_real_objcopy(self):
        verify(self.before, self.after)
        self.assertGreater(self.before["debug_raw_bytes"], 0)
        self.assertEqual(self.after["debug_raw_bytes"], 0)
        self.assertTrue(self.after["wine_builtin"])
        self.assertLess(self.after["bytes"], self.before["bytes"])

    def test_runtime_instruction_corruption_is_rejected(self):
        data = bytearray(self.candidate.read_bytes())
        data[512] ^= 1
        self.candidate.write_bytes(data)
        with self.assertRaisesRegex(BinaryError, "runtime section"):
            verify(self.before, snapshot(self.candidate))

    def test_builtin_marker_corruption_is_rejected(self):
        data = bytearray(self.candidate.read_bytes())
        data[64] ^= 1
        self.candidate.write_bytes(data)
        with self.assertRaisesRegex(BinaryError, "header"):
            verify(self.before, snapshot(self.candidate))

    def test_alignment_and_loader_directory_changes_are_rejected(self):
        for name in ("file_alignment", "optional_header_sha256"):
            bad = copy.deepcopy(self.after)
            bad[name] = "corrupt"
            with self.assertRaises(BinaryError):
                verify(self.before, bad)
        bad = copy.deepcopy(self.after)
        bad["directories"][10] = (4096, 10)
        with self.assertRaises(BinaryError):
            verify(self.before, bad)

    def test_unexpected_padding_change_is_rejected(self):
        data = bytearray(self.candidate.read_bytes())
        data[530] = 0x42
        self.candidate.write_bytes(data)
        with self.assertRaisesRegex(BinaryError, "padding"):
            verify(self.before, snapshot(self.candidate))

    def test_truncated_input_and_retained_debug_are_rejected(self):
        self.candidate.write_bytes(b"MZ")
        with self.assertRaises(BinaryError):
            snapshot(self.candidate)
        with self.assertRaisesRegex(BinaryError, "debug sections remain"):
            verify(self.before, self.before)

    @unittest.skipUnless(shutil.which("cc"), "host C compiler required")
    def test_elf_runtime_runs_and_loaded_code_is_unchanged(self):
        c = self.root / "program.c"
        c.write_text("int answer(void){return 42;} int main(void){return answer()!=42;}\n")
        source, candidate = self.root / "program", self.root / "program.stripped"
        subprocess.run(["cc", "-g", str(c), "-o", str(source)], check=True)
        subprocess.run(["llvm-objcopy-19", "--strip-debug", "--strip-unneeded",
                        str(source), str(candidate)], check=True)
        verify(snapshot(source), snapshot(candidate))
        subprocess.run([str(candidate)], check=True)
        damaged = bytearray(candidate.read_bytes())
        # Loaded segment outside the mutable ELF section-table header fields.
        damaged[0x1000] ^= 1
        candidate.write_bytes(damaged)
        with self.assertRaises(BinaryError):
            verify(snapshot(source), snapshot(candidate))


class PackagingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for tool in ("llvm-objcopy-19", "llvm-readobj-19", "dpkg-deb"):
            if not shutil.which(tool):
                raise unittest.SkipTest("required tool missing: " + tool)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        package = self.root / "package"
        (package / "DEBIAN").mkdir(parents=True)
        (package / "DEBIAN/control").write_text(
            "Package: test-wine\nVersion: 1.0\nArchitecture: arm64\n"
            "Maintainer: Test <test@example.invalid>\nDescription: fixture\n"
            "Depends: libc6\n")
        (package / "DEBIAN/postinst").write_text("#!/bin/sh\nexit 0\n")
        (package / "DEBIAN/postinst").chmod(0o755)
        dll = package / "usr/lib/test-wine/test.dll"
        dll.parent.mkdir(parents=True)
        dll.write_bytes(pe_fixture())
        deb = self.root / "test-wine.deb"
        subprocess.run(["dpkg-deb", "--build", "--root-owner-group", str(package), str(deb)],
                       check=True, capture_output=True)
        self.source = self.root / "source.tar"
        with tarfile.open(self.source, "w") as archive:
            archive.add(deb, arcname=deb.name)
        self.digest = hashlib.sha256(self.source.read_bytes()).hexdigest()

    def tearDown(self):
        self.temp.cleanup()

    def invoke(self, output, *options):
        return subprocess.run([sys.executable, str(SCRIPT), "--input", str(self.source),
                               "--sha256", self.digest, "--output", str(output), *options],
                              capture_output=True, text=True)

    def test_complete_candidate_symbols_and_reproducible_archives(self):
        outputs = [self.root / "out1", self.root / "out2"]
        for output in outputs:
            result = self.invoke(output)
            self.assertEqual(result.returncode, 0, result.stderr)
        one, two = [json.loads((p / "candidate-manifest.json").read_text()) for p in outputs]
        self.assertEqual(one["artifacts"], two["artifacts"])
        self.assertEqual(one["state"], "StaticVerifiedNotRuntimeTested")
        self.assertEqual(one["packages"][0]["candidate_version"], "1.0+idap.debugsplit1")
        binary = one["packages"][0]["processed_binaries"][0]
        with tarfile.open(outputs[0] / "hangover-symbols.tar.gz") as archive:
            original = archive.extractfile(binary["symbol_path"]).read()
            self.assertEqual(hashlib.sha256(original).hexdigest(), binary["before"]["sha256"])
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(), self.digest)
        with tarfile.open(outputs[0] / "hangover-runtime-candidate.tar") as archive:
            member = next(m for m in archive if m.name.endswith(".deb"))
            candidate_deb = self.root / "candidate.deb"
            candidate_deb.write_bytes(archive.extractfile(member).read())
        extracted = self.root / "extracted"
        subprocess.run(["dpkg-deb", "--raw-extract", str(candidate_deb), str(extracted)], check=True)
        self.assertIn("Depends: libc6", (extracted / "DEBIAN/control").read_text())
        self.assertEqual((extracted / "DEBIAN/postinst").read_text(), "#!/bin/sh\nexit 0\n")
        self.assertEqual((extracted / "DEBIAN/postinst").stat().st_mode & 0o777, 0o755)
        checksums = (extracted / "DEBIAN/md5sums").read_text()
        self.assertIn(hashlib.md5((extracted / binary["path"]).read_bytes()).hexdigest(), checksums)

    def test_tool_failure_does_not_publish_output(self):
        out = self.root / "failed"
        result = self.invoke(out, "--objcopy", "false")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(out.exists())

    def test_wrong_hash_does_not_publish_output(self):
        self.digest = "0" * 64
        out = self.root / "failed"
        self.assertNotEqual(self.invoke(out).returncode, 0)
        self.assertFalse(out.exists())

    def test_unsafe_archive_member_does_not_escape_or_publish(self):
        with tarfile.open(self.source, "w") as archive:
            member = tarfile.TarInfo("../escape.deb")
            member.size = 3
            archive.addfile(member, io.BytesIO(b"bad"))
        self.digest = hashlib.sha256(self.source.read_bytes()).hexdigest()
        out = self.root / "failed"
        self.assertNotEqual(self.invoke(out).returncode, 0)
        self.assertFalse(out.exists())
        self.assertFalse((self.root.parent / "escape.deb").exists())


if __name__ == "__main__":
    unittest.main()
