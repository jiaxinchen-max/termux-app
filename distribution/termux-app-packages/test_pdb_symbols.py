"""Symbol identity gate: mismatched, missing and corrupt artifacts must fail."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import uuid

from runtime_binary import BinaryError

SCRIPT = Path(__file__).with_name('verify-hangover-pdb.py')
spec = importlib.util.spec_from_file_location('pdb_gate', SCRIPT)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
GUID = uuid.UUID('00cba341-46ee-4f5f-96cb-ec211251ce29')


def make_readobj(path):
    """External decoder fixture; physical ARM64 plus a decoded hybrid view."""
    path.write_text('#!' + sys.executable + '''
import pathlib,struct,sys
data=pathlib.Path(sys.argv[-1]).read_bytes()
pe=struct.unpack_from('<I',data,60)[0]
machine=struct.unpack_from('<H',data,pe+4)[0]
if machine==0x14c:
 print('Format: COFF-i386')
else:
 print('Format: COFF-ARM64X\\nHybridObject {\\n  Format: COFF-ARM64EC\\n}')
''')
    path.chmod(0o755)


def image(machine):
    data = bytearray(1024)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 60, 128)
    data[64:80] = b'Wine builtin DLL'
    data[128:132] = b'PE\0\0'
    is32 = machine == 0x14c
    optional = 224 if is32 else 240
    struct.pack_into('<HHIIIHH', data, 132, machine, 1, 0, 0, 0, optional, 0x2022)
    op = 152
    struct.pack_into('<H', data, op, 0x10b if is32 else 0x20b)
    struct.pack_into('<II', data, op + 32, 4096, 512)
    struct.pack_into('<II', data, op + 56, 8192, 512)
    directory = 96 if is32 else 112
    struct.pack_into('<I', data, op + directory - 4, 16)
    struct.pack_into('<II', data, op + directory + 6 * 8, 0x1020, 28)
    struct.pack_into('<8sIIIIIIHHI', data, op + optional,
                     b'.text', 256, 4096, 512, 512, 0, 0, 0, 0, 0x60000020)
    record = b'RSDS' + GUID.bytes_le + struct.pack('<I', 1) + b'test.pdb\0'
    struct.pack_into('<IIHHIIII', data, 544, 0, 0, 0, 0, 2, len(record), 4160, 576)
    data[576:576 + len(record)] = record
    return data


def export_image(named=False):
    data = image(0x14c)
    op = 152
    struct.pack_into('<I', data, op + 224 + 8, 512)
    struct.pack_into('<II', data, op + 96, 0x1080, 128)
    struct.pack_into('<IIHHIIIIIII', data, 640, 0, 0, 0, 0, 0x10ee, 1,
                     3, int(named), 0x10a8, 0x110c if named else 0, 0x1110 if named else 0)
    struct.pack_into('<III', data, 680, 0x1000, 0, 0x10da)
    data[730:745] = b'KERNEL32.Sleep\0'
    data[750:759] = b'test.dll\0'
    if named:
        struct.pack_into('<I', data, 780, 0x1120)
        struct.pack_into('<H', data, 784, 0)
        data[800:807] = b'Export\0'
    return data


class ExportTests(unittest.TestCase):
    def test_ordinal_only_holes_and_forwarders(self):
        inventory = gate.export_inventory(export_image())
        self.assertEqual(inventory['named_count'], 0)
        self.assertEqual([s['kind'] for s in inventory['slots']], ['code', 'hole', 'forwarder'])
        self.assertEqual(inventory['slots'][2]['forwarder'], 'KERNEL32.Sleep')
        self.assertEqual(inventory['module'], 'test.dll')
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'ordinal.dll'
            tool = Path(tmp) / 'readobj'
            path.write_bytes(export_image())
            # Regression: ordinal-only tables must not reach LLVM's broken
            # named-table decoder. Other loader decoders still run.
            tool.write_text('#!' + sys.executable + "\nimport sys\nassert '--coff-exports' not in sys.argv\nassert '--coff-imports' in sys.argv\nprint('Format: COFF-i386')\n")
            tool.chmod(0o755)
            self.assertEqual(gate.loader_metadata(path, str(tool))[0], 'COFF-i386')

    def test_named_export_and_invalid_ordinal(self):
        data = export_image(True)
        self.assertEqual(gate.export_inventory(data)['slots'][0]['names'], ['Export'])
        struct.pack_into('<H', data, 784, 3)
        with self.assertRaisesRegex(BinaryError, 'invalid ordinal'):
            gate.export_inventory(data)

    def test_export_bounds_and_forwarder_termination(self):
        for offset, value in [(640 + 28, 0xffff0000), (680, 0xffff0000)]:
            data = export_image()
            struct.pack_into('<I', data, offset, value)
            with self.assertRaises(BinaryError):
                gate.export_inventory(data)
        data = export_image()
        data[730:768] = b'X' * 38
        with self.assertRaisesRegex(BinaryError, 'unterminated'):
            gate.export_inventory(data)


class SymbolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runtime = self.root / 'runtime'
        self.runtime.mkdir()
        (self.runtime / 'arm64x.dll').write_bytes(image(0xaa64))
        (self.runtime / 'i386.dll').write_bytes(image(0x14c))
        self.symbols = self.root / 'symbols'
        self.symbols.mkdir()
        (self.symbols / 'test.pdb').write_bytes(b'fixture')
        self.tool = self.root / 'pdbutil'
        self.readobj = self.root / 'readobj'
        make_readobj(self.readobj)
        self.set_summary(GUID, 1)

    def tearDown(self):
        self.temp.cleanup()

    def set_summary(self, guid, age):
        self.tool.write_text('#!' + sys.executable + '\nprint(' +
                             repr('  GUID: {' + str(guid) + '}\n  Age: ' + str(age)) + ')\n')
        self.tool.chmod(0o755)

    def test_matching_guids_and_ages_publish_symbol_only_status(self):
        report = gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))
        self.assertEqual(len(report['images']), 2)
        self.assertEqual(report['state'], 'SymbolsVerifiedNotRuntimeTested')
        self.assertEqual(report['images'][0]['symbols'][0]['symbol']['path'], 'test.pdb')
        self.assertEqual(report['images'][0]['image']['machine'], 0xaa64)
        self.assertEqual(report['images'][0]['loader_format'], 'COFF-ARM64X')

    def test_same_filename_wrong_guid_or_age_is_rejected(self):
        for guid, age in [(uuid.uuid4(), 1), (GUID, 2)]:
            self.set_summary(guid, age)
            with self.assertRaisesRegex(BinaryError, 'matching PDB missing'):
                gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))

    def test_missing_architecture_and_missing_debug_directory_fail(self):
        (self.runtime / 'i386.dll').unlink()
        with self.assertRaisesRegex(BinaryError, 'architecture missing'):
            gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))
        data = image(0xaa64)
        struct.pack_into('<II', data, 152 + 112 + 6 * 8, 0, 0)
        (self.runtime / 'arm64x.dll').write_bytes(data)
        with self.assertRaisesRegex(BinaryError, 'no PDB identity'):
            gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))

    def test_corrupt_codeview_and_directory_pointer_fail(self):
        for offset in [576, 152 + 112 + 6 * 8, 544 + 20]:
            data = image(0xaa64)
            data[offset] ^= 0xff
            (self.runtime / 'arm64x.dll').write_bytes(data)
            with self.assertRaises(BinaryError):
                gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))

    def test_failed_tool_does_not_publish_report(self):
        output = self.root / 'report.json'
        result = subprocess.run([sys.executable, str(SCRIPT), '--runtime', str(self.runtime),
                                 '--pdb-root', str(self.symbols), '--pdbutil', '/bin/false',
                                 '--readobj', str(self.readobj),
                                 '--output', str(output)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())

    def test_existing_report_is_never_overwritten(self):
        output = self.root / 'report.json'
        output.write_text('previous verified report')
        result = subprocess.run([sys.executable, str(SCRIPT), '--runtime', str(self.runtime),
                                 '--pdb-root', str(self.symbols), '--pdbutil', str(self.tool),
                                 '--readobj', str(self.readobj),
                                 '--output', str(output)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(), 'previous verified report')

    def test_missing_hybrid_view_and_native_only_decoder_are_rejected(self):
        for output in ['Format: COFF-ARM64X', 'Format: COFF-ARM64']:
            self.readobj.write_text('#!' + sys.executable + '\nprint(' + repr(output) + ')\n')
            with self.assertRaises(BinaryError):
                gate.verify_tree(self.runtime, self.symbols, str(self.tool), str(self.readobj))


if __name__ == '__main__':
    unittest.main()
