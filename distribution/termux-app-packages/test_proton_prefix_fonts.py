"""Proton asset regression checks, including the real shipped registry payload."""
import hashlib
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

import proton_prefix_fonts as fonts

HERE = Path(__file__).resolve().parent
ASSET = HERE/'native-prefix-assets/glibc_proton_11.0'
FONT_PATHS = ['drive_c/windows/Fonts/'+filename for filename in
              ('NotoSansCJK-Regular.ttc', 'NotoSerifCJK-Regular.ttc', 'Arial.TTF', 'Times.TTF')]


class ProtonFontTests(unittest.TestCase):
    def test_stale_paths_are_relocated_only_when_font_is_packaged(self):
        text = ('WINE REGISTRY Version 2\n#arch=win64\n\n['+fonts.BASE+'Fonts]\n'
                '"Arial (TrueType)"="Z:\\\\usr\\\\share\\\\fonts\\\\arial.ttf"\n'
                '"Missing (TrueType)"="Z:\\\\usr\\\\share\\\\fonts\\\\missing.ttf"\n')
        result = fonts.sections(fonts.normalize(text, 'system.reg', FONT_PATHS))
        self.assertEqual(result[fonts.BASE+'Fonts']['"Arial (TrueType)"'], '"Arial.TTF"')
        self.assertNotIn('"Missing (TrueType)"', result[fonts.BASE+'Fonts'])

    def test_unrelated_multiline_hive_data_is_unchanged(self):
        unrelated = '[Software\\\\Game] 123\n#time=abc\n"settings"=hex:01,\\\n  02,03\n\n'
        text = 'WINE REGISTRY Version 2\n#arch=win64\n\n'+unrelated
        result = fonts.normalize(text, 'user.reg', FONT_PATHS)
        self.assertIn(unrelated, result)
        self.assertTrue(result.startswith('WINE REGISTRY Version 2\n#arch=win64\n'))

    def test_latin_replacements_and_font_families_keep_their_type(self):
        text = ('WINE REGISTRY Version 2\n['+fonts.REPLACEMENTS+']\n'
                '"Segoe UI"="Times New Roman"\n"Palatino Linotype"="Times New Roman"\n')
        result = fonts.sections(fonts.normalize(text, 'user.reg', FONT_PATHS))
        self.assertEqual(result[fonts.REPLACEMENTS]['"Segoe UI"'], '"Noto Sans CJK SC"')
        self.assertEqual(result[fonts.REPLACEMENTS]['"Palatino Linotype"'], '"Times New Roman"')
        for base in (fonts.BASE, fonts.WOW):
            links = result[base+'FontLink\\\\SystemLink']
            self.assertEqual(links['"Times New Roman"'],
                             'str(7):"NotoSerifCJK-Regular.ttc,Noto Serif CJK SC\\0"')
            self.assertEqual(links['"Verdana"'],
                             'str(7):"NotoSansCJK-Regular.ttc,Noto Sans CJK SC\\0"')

    def test_missing_cjk_font_fails_build(self):
        with self.assertRaisesRegex(ValueError, 'proton_cjk_font_missing'):
            fonts.normalize('WINE REGISTRY Version 2\n', 'system.reg', FONT_PATHS[1:])

    def test_duplicate_sections_merged_without_losing_unmanaged_values(self):
        key = fonts.BASE+'FontSubstitutes'
        text = ('WINE REGISTRY Version 2\n['+key+']\n"Custom"="Arial"\n'
                '"Segoe UI"="wrong"\n['+key+']\n"Segoe UI"="Times New Roman"\n')
        result = fonts.normalize(text, 'system.reg', FONT_PATHS)
        self.assertEqual(result.count('['+key+']'), 1)
        self.assertEqual(fonts.sections(result)[key]['"Custom"'], '"Arial"')
        self.assertEqual(fonts.normalize(result, 'system.reg', FONT_PATHS), result)

    def test_real_template_has_usable_font_paths_and_wine_multistring_links(self):
        metadata = json.loads((ASSET/'bundle.json').read_text())
        manifest = {r['path']: r for r in json.loads((ASSET/'manifest.json').read_text())}
        self.assertEqual(metadata['prefixComponentVersion'], 2)
        self.assertEqual(metadata['windowsUser'], 'steamuser')
        self.assertEqual(sum(r['bytes'] for r in manifest.values()), metadata['expandedBytes'])
        for name in ('manifest.json', 'delta.tgz'):
            self.assertEqual(hashlib.sha256((ASSET/name).read_bytes()).hexdigest(), metadata['assets'][name])
        available = {path.rsplit('/', 1)[-1] for path in manifest if path.startswith('drive_c/windows/Fonts/')}
        with tarfile.open(ASSET/'delta.tgz') as archive:
            for hive in ('system.reg', 'user.reg'):
                raw = archive.extractfile(hive).read()
                self.assertEqual(len(raw), manifest[hive]['bytes'])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), manifest[hive]['sha256'])
                text = raw.decode()
                self.assertNotIn('Z:\\\\usr\\\\share\\\\fonts', text)
                self.assertNotIn('Z:\\\\home\\\\user\\\\workspace', text)
                result = fonts.sections(text)
                for base in (fonts.BASE, fonts.WOW):
                    links = result[base+'FontLink\\\\SystemLink']
                    for family in fonts.SANS_FAMILIES+fonts.SERIF_FAMILIES:
                        value = links[fonts.quote(family)]
                        self.assertTrue(value.startswith('str(7):"'))
                        self.assertIn(value.split('"', 1)[1].split(',', 1)[0], available)
                if hive == 'system.reg':
                    for base in (fonts.BASE, fonts.WOW):
                        for family in ('Segoe UI', 'Segoe UI Semibold'):
                            self.assertEqual(result[base+'FontSubstitutes'][fonts.quote(family)],
                                             '"Noto Sans CJK SC"')
                        for value in result[base+'Fonts'].values():
                            self.assertIn(value.strip('"'), available)
                else:
                    self.assertEqual(result[fonts.REPLACEMENTS]['"Segoe UI"'], '"Noto Sans CJK SC"')

    def test_repacking_preserves_every_nonregistry_file_and_source(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)/'candidate'
            fonts.repair_bundle(ASSET, output)
            before = {r['path']: r for r in json.loads((ASSET/'manifest.json').read_text())}
            after = {r['path']: r for r in json.loads((output/'manifest.json').read_text())}
            self.assertEqual(before.keys(), after.keys())
            for path in before:
                if path not in ('system.reg', 'user.reg'):
                    self.assertEqual(before[path], after[path])
            with tarfile.open(ASSET/'delta.tgz') as a, tarfile.open(output/'delta.tgz') as b:
                self.assertEqual(a.getnames(), b.getnames())
                for member in a:
                    if member.isfile() and member.name not in ('system.reg', 'user.reg'):
                        self.assertEqual(a.extractfile(member).read(), b.extractfile(member.name).read())


if __name__ == '__main__':
    unittest.main()
