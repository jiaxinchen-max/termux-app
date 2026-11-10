import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('split_graphics', Path(__file__).with_name('split-native-graphics.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SplitGraphicsTest(unittest.TestCase):
    def source(self, root, family, missing=False):
        source = root / (family + '.tar.xz')
        entries = {}
        variants = module.VARIANTS[family][1:] if missing else module.VARIANTS[family]
        for variant in variants:
            name = 'glibc/opt/libs/' + ('mesa' if family == 'turnip' else 'd3d') + '/' + variant + '.7z'
            entries[name] = b"7z\xbc\xaf\x27\x1c" + variant.encode()
        entries['glibc/opt/libs/unused.7z'] = b'ignore'
        with tarfile.open(source, 'w:xz') as archive:
            for name, data in entries.items():
                member = tarfile.TarInfo(name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        return source, entries

    def test_each_version_is_independent_byte_identical_and_reproducible(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            all_items = []
            for family in ('turnip', 'dxvk'):
                source, originals = self.source(root, family)
                first = module.split(source, family, root / 'one')
                second = module.split(source, family, root / 'two')
                self.assertEqual(first, second)
                all_items += first
                for item in first:
                    with tarfile.open(root / 'one' / item['file']) as archive:
                        self.assertEqual([item['payload']], archive.getnames())
                        self.assertEqual(originals[item['payload']], archive.extractfile(item['payload']).read())
            self.assertEqual(13, len(all_items))
            self.assertEqual(13, len({item['id'] for item in all_items}))

    def test_missing_selected_version_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, _ = self.source(root, 'turnip', missing=True)
            with self.assertRaisesRegex(ValueError, 'expected one regular graphics archive'):
                module.split(source, 'turnip', root / 'out')

if __name__ == '__main__':
    unittest.main()
