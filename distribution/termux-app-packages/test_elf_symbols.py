"""Match real host ELF binaries to separated debug files; reject wrong pairs."""
import importlib.util
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from runtime_binary import BinaryError, snapshot

spec = importlib.util.spec_from_file_location('elf_gate', Path(__file__).with_name('verify-hangover-elf.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


@unittest.skipUnless(all(shutil.which(t) for t in ('cc', 'objcopy', 'readelf')), 'ELF toolchain required')
class ElfSymbolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'; self.runtime.mkdir()
        self.symbols = self.root / 'symbols'
        source = self.root / 'program.c'
        source.write_text('#include <stdio.h>\nint answer(void){return 42;} int main(void){puts("symbol gate");return answer()!=42;}\n')
        self.program = self.runtime / 'program'
        subprocess.run(['cc', '-g', '-O2', '-Wl,--build-id', str(source), '-o', str(self.program)], check=True)
        identity = gate.build_id(self.program)
        notes = subprocess.run(['readelf', '--notes', str(self.program)],
                               capture_output=True, text=True, check=True).stdout
        self.assertIn('Build ID: ' + identity, notes)
        self.debug = self.symbols / 'usr/lib/debug/.build-id' / identity[:2] / (identity[2:] + '.debug')
        self.debug.parent.mkdir(parents=True)
        subprocess.run(['objcopy', '--only-keep-debug', str(self.program), str(self.debug)], check=True)
        subprocess.run(['objcopy', '--strip-debug', str(self.program)], check=True)

    def tearDown(self):
        self.temp.cleanup()

    def test_real_debug_pair_maps_and_runtime_executes(self):
        report = gate.verify_tree(self.runtime, self.symbols)
        self.assertEqual(len(report['mappings']), 1)
        self.assertEqual(report['state'], 'ElfDebugMappedNotRuntimeTested')
        subprocess.run([str(self.program)], check=True, capture_output=True)

    def test_empty_debug_segments_need_no_backing_bytes(self):
        # only-keep-debug may retain a logical p_offset beyond EOF for an
        # empty segment; actual nonzero payloads must still be bounded.
        data = bytearray(self.debug.read_bytes())
        phoff, = struct.unpack_from('<Q', data, 32)
        phsize, phnum = struct.unpack_from('<HH', data, 54)
        at = next(phoff + i * phsize for i in range(phnum)
                  if struct.unpack_from('<Q', data, phoff + i * phsize + 32)[0] == 0)
        struct.pack_into('<Q', data, at + 8, len(data) + 4096)
        self.debug.write_bytes(data)
        snapshot(self.debug)
        struct.pack_into('<Q', data, at + 32, 1)
        self.debug.write_bytes(data)
        with self.assertRaisesRegex(BinaryError, 'range outside file'):
            snapshot(self.debug)

    def test_allocated_debug_layout_corruption_is_rejected(self):
        data = bytearray(self.debug.read_bytes())
        shoff, = struct.unpack_from('<Q', data, 40)
        shsize, shnum = struct.unpack_from('<HH', data, 58)
        for i in range(shnum):
            at = shoff + i * shsize
            flags, = struct.unpack_from('<Q', data, at + 8)
            if flags & 2:
                address, = struct.unpack_from('<Q', data, at + 16)
                struct.pack_into('<Q', data, at + 16, address + 1)
                break
        self.debug.write_bytes(data)
        with self.assertRaisesRegex(BinaryError, 'allocated layout differs'):
            gate.verify_tree(self.runtime, self.symbols)

    def test_missing_and_wrong_debug_file_are_rejected(self):
        self.debug.unlink()
        with self.assertRaisesRegex(BinaryError, 'debug file missing'):
            gate.verify_tree(self.runtime, self.symbols)
        shutil.copy2('/bin/true', self.debug)
        with self.assertRaisesRegex(BinaryError, 'Build-ID mismatch'):
            gate.verify_tree(self.runtime, self.symbols)


if __name__ == '__main__':
    unittest.main()
