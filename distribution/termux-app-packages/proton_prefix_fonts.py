"""Build-time Proton font defaults. Never executed by the Android app."""
import re

SANS = ('NotoSansCJK-Regular.ttc', 'Noto Sans CJK SC')
SERIF = ('NotoSerifCJK-Regular.ttc', 'Noto Serif CJK SC')
BASE = r'Software\\Microsoft\\Windows NT\\CurrentVersion\\'
WOW = r'Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion\\'
EXTERNAL = r'Software\\Wine\\Fonts\\External Fonts'
REPLACEMENTS = r'Software\\Wine\\Fonts\\Replacements'
SANS_FAMILIES = ('Tahoma', 'Arial', 'Arial Black', 'Verdana', 'Segoe UI',
                 'Segoe UI Semibold', 'Microsoft Sans Serif', 'MS Sans Serif',
                 'MS Shell Dlg', 'MS Shell Dlg 2', 'Lucida Sans Unicode')
SERIF_FAMILIES = ('Times New Roman', 'Palatino Linotype', 'Georgia')


def sections(text):
    """Merge repeated Wine hive sections, retaining last-value semantics."""
    result = {}
    current = None
    for line in re.sub(r'\\\n[ \t]*', '', text).splitlines():
        if line.startswith('['):
            current = line[1:line.index(']')]
            result.setdefault(current, {})
        elif current is not None and (line.startswith('"') or line.startswith('@=')):
            match = re.match(r'("(?:[^"\\]|\\.)*"|@)=(.*)$', line)
            if not match:
                raise ValueError('font_registry_value_invalid')
            result[current][match[1]] = match[2]
    return result


def quote(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def replace_sections(text, replacements):
    """Keep unrelated hive bytes intact; emit each managed section exactly once."""
    headers = list(re.finditer(r'^\[([^\n]*)\][^\n]*\n', text, re.M))
    output = [text[:headers[0].start()] if headers else text]
    emitted = set()
    for i, match in enumerate(headers):
        end = headers[i+1].start() if i+1 < len(headers) else len(text)
        key = match[1]
        if key not in replacements:
            output.append(text[match.start():end])
        elif key not in emitted:
            output.append('[' + key + ']\n' + ''.join(
                name + '=' + value + '\n' for name, value in replacements[key].items()) + '\n')
            emitted.add(key)
    for key in replacements:
        if key in emitted:
            continue
        output.append('[' + key + ']\n' + ''.join(
            name + '=' + value + '\n' for name, value in replacements[key].items()) + '\n')
    return ''.join(output)


def normalize(text, hive, font_paths):
    if hive not in ('system.reg', 'user.reg'):
        return text
    # Windows font lookup ignores filename case; Unix host files do not.
    files = {path.rsplit('/', 1)[-1].casefold(): path.rsplit('/', 1)[-1]
             for path in font_paths if path.startswith('drive_c/windows/Fonts/')}
    for filename, _ in (SANS, SERIF):
        if filename.casefold() not in files:
            raise ValueError('proton_cjk_font_missing:' + filename)
    existing = sections(text)
    updates = {}
    font_keys = [BASE+'Fonts', WOW+'Fonts', r'Software\\Microsoft\\Windows\\CurrentVersion\\Fonts']
    if hive == 'user.reg':
        font_keys = [EXTERNAL]
    for key in font_keys:
        mapped = {}
        for name, value in existing.get(key, {}).items():
            if name == '"SymbolicLinkValue"':
                continue
            if value.startswith('"'):
                # Decode only Wine string escaping, never interpret \xNNNN font names.
                filename = value[1:-1].replace('\\\\', '\\').rsplit('\\', 1)[-1]
                filename = files.get(filename.casefold())
                if filename:
                    mapped[name] = quote('C:\\windows\\Fonts\\' + filename) if key == EXTERNAL else quote(filename)
            else:
                raise ValueError('proton_font_path_not_string:' + name)
        if key != EXTERNAL:
            for filename, family in (SANS, SERIF):
                mapped[quote(family + ' (TrueType)')] = quote(filename)
        updates[key] = mapped
    if hive == 'system.reg':
        for base in (BASE, WOW):
            substitutes = dict(existing.get(base+'FontSubstitutes', {}))
            substitutes.pop('"SymbolicLinkValue"', None)
            for family in ('Segoe UI', 'Segoe UI Semibold'):
                substitutes[quote(family)] = quote(SANS[1])
            updates[base+'FontSubstitutes'] = substitutes
    if hive == 'user.reg':
        replacements = dict(existing.get(REPLACEMENTS, {}))
        for family in ('Segoe UI', 'Segoe UI Semibold'):
            replacements[quote(family)] = quote(SANS[1])
        # Preserve real Verdana and Times/Palatino Latin glyphs. Missing CJK
        # glyphs are handled by explicit links, not by replacing whole families.
        updates[REPLACEMENTS] = replacements
    for base in (BASE, WOW):
        links = dict(existing.get(base+'FontLink\\\\SystemLink', {}))
        links.pop('"SymbolicLinkValue"', None)
        for families, (filename, family) in ((SANS_FAMILIES, SANS), (SERIF_FAMILIES, SERIF)):
            for name in families:
                # Wine hive syntax, not REGEDIT5 hex(7) syntax.
                links[quote(name)] = 'str(7):"' + filename + ',' + family + '\\0"'
        updates[base+'FontLink\\\\SystemLink'] = links
    return replace_sections(text, updates)


def repair_bundle(source, output):
    """Regenerate only registry bytes in an existing Proton build artifact."""
    import copy
    import gzip
    import hashlib
    import io
    import json
    import tarfile
    digest = lambda data: hashlib.sha256(data).hexdigest()
    metadata = json.loads((source/'bundle.json').read_text())
    if metadata['winePackage'] != 'glibc_proton_11.0' or metadata['schemaVersion'] != 3:
        raise ValueError('proton_bundle_required')
    for name in ('manifest.json', 'delta.tgz'):
        if digest((source/name).read_bytes()) != metadata['assets'][name]:
            raise ValueError('input_asset_mismatch:' + name)
    records = json.loads((source/'manifest.json').read_text())
    by_name = {r['path']: r for r in records}
    if len(by_name) != len(records):
        raise ValueError('duplicate_manifest_path')
    updated = {}
    with tarfile.open(source/'delta.tgz') as archive:
        members = archive.getmembers()
        if len({m.name for m in members}) != len(members):
            raise ValueError('duplicate_archive_path')
        payload = {m.name: archive.extractfile(m).read() for m in members if m.isfile()}
    for hive in ('system.reg', 'user.reg'):
        data = normalize(payload[hive].decode(), hive, by_name).encode()
        updated[hive] = data
        record = by_name[hive]
        if 'source' in record:
            raise ValueError('registry_must_be_in_delta')
        record.update(bytes=len(data), sha256=digest(data))
    output.mkdir(parents=True, exist_ok=False)
    with (output/'delta.tgz').open('xb') as raw:
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode='w') as archive:
                for member in members:
                    member = copy.copy(member)
                    data = updated.get(member.name, payload.get(member.name))
                    if member.isfile():
                        member.size = len(data)
                    archive.addfile(member, io.BytesIO(data) if member.isfile() else None)
    (output/'manifest.json').write_text(json.dumps(records, separators=(',', ':'))+'\n')
    metadata['prefixComponentVersion'] = 2
    metadata['expandedBytes'] = sum(r['bytes'] for r in records)
    metadata['assets'] = {name: digest((output/name).read_bytes()) for name in ('manifest.json', 'delta.tgz')}
    metadata['provenance']['fontConfigurationVersion'] = 2
    metadata['provenance']['nativeAcceptance'] = 'Pending'
    (output/'bundle.json').write_text(json.dumps(metadata, indent=2)+'\n')
    return metadata


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repair_bundle(args.source, args.output)
