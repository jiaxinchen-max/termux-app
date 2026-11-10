import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('native_box64_package', Path(__file__).with_name('package-native-box64.py'))
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


def elf(machine):
    data = bytearray(64)
    data[:6] = b'\x7fELF\x02\x01'
    data[18:20] = machine.to_bytes(2, 'little')
    return bytes(data)


def archive(path, files):
    with tarfile.open(path, 'w') as stream:
        for name, data in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            stream.addfile(member, io.BytesIO(data))


class PackageTest(unittest.TestCase):
    def test_reproducible_exact_payload_excludes_metadata_and_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary, libraries = root / 'binary.tar', root / 'libraries.tar'
            archive(binary, {'glibc/bin/box64': elf(183), 'glibc/bin/._box64': b'metadata'})
            files = {'glibc/lib/x86_64-linux-gnu/' + name: elf(62) + name.encode() for name in pack.LIBRARIES}
            archive(libraries, dict(files, **{'glibc/bin/box64': elf(183) + b'old', 'glibc/opt/box/old.tar.xz': b'history'}))
            first, second = root / 'first.tar.xz', root / 'second.tar.xz'
            pack.package(binary, libraries, first)
            pack.package(binary, libraries, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with tarfile.open(first) as output:
                self.assertEqual(set(output.getnames()), set(files) | {'glibc/bin/box64'})
                self.assertEqual(output.extractfile('glibc/bin/box64').read(), elf(183))
                for name, content in files.items():
                    self.assertEqual(output.extractfile(name).read(), content)

    def test_missing_or_wrong_architecture_support_library_fails_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary, libraries = root / 'binary.tar', root / 'libraries.tar'
            archive(binary, {'glibc/bin/box64': elf(183)})
            files = {'glibc/lib/x86_64-linux-gnu/' + name: elf(62) for name in pack.LIBRARIES}
            target = 'glibc/lib/x86_64-linux-gnu/libgcc_s.so.1'
            for replacement in (None, elf(183)):
                broken = dict(files)
                if replacement is None:
                    del broken[target]
                else:
                    broken[target] = replacement
                archive(libraries, broken)
                output = root / 'invalid.tar.xz'
                with self.assertRaises(ValueError):
                    pack.package(binary, libraries, output)
                self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
