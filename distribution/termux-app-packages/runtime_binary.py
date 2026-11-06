"""Read-only, fail-closed checks for stripped Wine PE and ELF binaries.

This module never rewrites PE headers. RVAs and all runtime bytes must survive
objcopy. Only debug metadata and demonstrably unused executable tail padding
may change. Dynamic ARM64X relocations are covered by their containing runtime
section bytes and unchanged data directory RVAs, in addition to LLVM decoding.
"""
import hashlib
import struct


class BinaryError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise BinaryError("binary range outside file")
    return data[offset:offset + size]


def unpack(fmt, data, offset):
    return struct.unpack(fmt, checked(data, offset, struct.calcsize(fmt)))


def cstring(data, offset):
    if offset < 0 or offset >= len(data):
        raise BinaryError("invalid string table offset")
    end = data.find(b"\0", offset)
    if end < 0:
        raise BinaryError("unterminated binary name")
    return data[offset:end].decode("ascii")


def pe_snapshot(data):
    if checked(data, 0, 2) != b"MZ":
        raise BinaryError("not a PE image")
    pe, = unpack("<I", data, 60)
    if pe < 80 or checked(data, pe, 4) != b"PE\0\0":
        raise BinaryError("missing PE signature or Wine DOS header")
    machine, count, timestamp, symbol_offset, symbols, optional_size, flags = unpack(
        "<HHIIIHH", data, pe + 4)
    if machine not in (0x14c, 0x8664, 0xaa64, 0xa641, 0xa64e):
        raise BinaryError("unsupported PE architecture")
    if not 0 < count <= 96:
        raise BinaryError("invalid PE section count")
    op = pe + 24
    optional = bytearray(checked(data, op, optional_size))
    magic, = unpack("<H", optional, 0)
    directories_offset = {0x10b: 96, 0x20b: 112}.get(magic)
    if directories_offset is None:
        raise BinaryError("unsupported PE optional header")
    directory_count, = unpack("<I", optional, directories_offset - 4)
    if directory_count > 16:
        raise BinaryError("unsupported PE data directories")
    directories = [unpack("<II", optional, directories_offset + 8 * i)
                   for i in range(directory_count)]
    if len(directories) > 4 and directories[4] != (0, 0):
        raise BinaryError("signed PE image cannot be repackaged")
    # Initialized data includes removed debug sections. Image/header size and
    # checksum are recomputed by objcopy, but bounded and checked below.
    for offset, size in ((8, 4), (56, 4), (60, 4), (64, 4)):
        optional[offset:offset + size] = b"\0" * size
    strings = b""
    if symbol_offset:
        string_start = symbol_offset + symbols * 18
        length, = unpack("<I", data, string_start)
        if length < 4:
            raise BinaryError("invalid COFF string table")
        strings = checked(data, string_start, length)
    sections = []
    for index in range(count):
        entry = checked(data, op + optional_size + 40 * index, 40)
        raw_name, virtual_size, rva, size, pointer, reloc, lines, nreloc, nlines, attrs = (
            struct.unpack("<8sIIIIIIHHI", entry))
        name = raw_name.rstrip(b"\0").decode("ascii")
        if name.startswith("/"):
            try:
                offset = int(name[1:])
            except ValueError as error:
                raise BinaryError("invalid long COFF section name") from error
            if offset < 4:
                raise BinaryError("invalid long COFF section offset")
            name = cstring(strings, offset)
        payload = checked(data, pointer, size) if size else b""
        debug = name.startswith((".debug", ".zdebug", ".stab"))
        meaningful = min(virtual_size, size) if virtual_size else size
        tail = payload[meaningful:]
        sections.append({
            "name": name, "rva": rva, "virtual_size": virtual_size,
            "raw_size": size, "attributes": attrs, "debug": debug,
            "content_sha256": digest(payload[:meaningful]),
            "raw_sha256": digest(payload), "tail_sha256": digest(tail),
            "tail_byte_values": sorted(set(tail)),
            "relocation_metadata": [reloc, lines, nreloc, nlines],
        })
    if len({s["name"] for s in sections}) != len(sections):
        raise BinaryError("duplicate PE section names")
    section_alignment, file_alignment = unpack("<II", data, op + 32)
    image_size, header_size = unpack("<II", data, op + 56)
    if not section_alignment or not file_alignment or header_size > len(data):
        raise BinaryError("invalid PE size or alignment")
    if header_size < op + optional_size + 40 * count:
        raise BinaryError("PE headers overlap sections")
    for section in sections:
        if not section["debug"] and section["rva"] + max(
                section["virtual_size"], section["raw_size"]) > image_size:
            raise BinaryError("runtime PE section exceeds image size")
    return {
        "format": "PE", "machine": machine, "bytes": len(data),
        "sha256": digest(data), "dos_stub_sha256": digest(data[:pe]),
        "wine_builtin": checked(data, 64, 16) == b"Wine builtin DLL",
        "timestamp": timestamp, "flags": flags,
        "section_alignment": section_alignment, "file_alignment": file_alignment,
        "optional_header_sha256": digest(optional),
        "directories": directories, "sections": sections, "coff_symbols": symbols,
        "debug_raw_bytes": sum(s["raw_size"] for s in sections if s["debug"]),
    }


def elf_snapshot(data):
    if checked(data, 0, 6) != b"\x7fELF\x02\x01":
        raise BinaryError("only little-endian ELF64 is supported")
    header = unpack("<HHIQQQIHHHHHH", data, 16)
    kind, machine, version, entry, phoff, shoff, flags, ehsize, phsize, phnum, shsize, shnum, names = header
    if machine not in (183, 62) or not shnum or not 0 <= names < shnum:
        raise BinaryError("unsupported ELF architecture or section table")
    if ehsize != 64 or shsize != 64 or (phnum and phsize != 56):
        raise BinaryError("invalid ELF header size")
    section_headers = [unpack("<IIQQQQIIQQ", data, shoff + i * shsize) for i in range(shnum)]
    names_header = section_headers[names]
    table = checked(data, names_header[4], names_header[5])
    sections = []
    for h in section_headers:
        name, stype, attrs, addr, offset, size, link, info, align, entsize = h
        name = cstring(table, name)
        payload = checked(data, offset, size) if stype != 8 else b""
        sections.append({"name": name, "type": stype, "flags": attrs,
                         "address": addr, "size": size, "alignment": align,
                         "entry_size": entsize, "sha256": digest(payload),
                         "debug": name.startswith((".debug", ".zdebug", ".stab"))})
    normalized = bytearray(data)
    normalized[40:48] = b"\0" * 8  # e_shoff
    normalized[60:64] = b"\0" * 4  # e_shnum, e_shstrndx
    segments = []
    for index in range(phnum):
        h = unpack("<IIQQQQQQ", data, phoff + index * phsize)
        stype, attrs, offset, vaddr, paddr, filesz, memsz, alignment = h
        segments.append({"header": h, "sha256": digest(checked(normalized, offset, filesz) if filesz else b"")})
    return {"format": "ELF", "bytes": len(data), "sha256": digest(data),
            "header": [kind, machine, version, entry, phoff, flags, ehsize, phsize, phnum],
            "segments": segments, "sections": sections,
            "debug_raw_bytes": sum(s["size"] for s in sections if s["debug"])}


def snapshot(path):
    data = path.read_bytes()
    if data.startswith(b"MZ"):
        return pe_snapshot(data)
    if data.startswith(b"\x7fELF"):
        return elf_snapshot(data)
    raise BinaryError("unsupported binary format: " + str(path))


def verify(before, after):
    if before["format"] != after["format"]:
        raise BinaryError("binary format changed")
    if after["debug_raw_bytes"]:
        raise BinaryError("debug sections remain in runtime candidate")
    if after["bytes"] > before["bytes"]:
        raise BinaryError("candidate grew after stripping")
    if before["format"] == "ELF":
        if before["header"] != after["header"] or before["segments"] != after["segments"]:
            raise BinaryError("ELF runtime segments changed")
        allocated = lambda item: [s for s in item["sections"] if s["flags"] & 2]
        if allocated(before) != allocated(after):
            raise BinaryError("ELF allocated sections changed")
        return []
    immutable = ("machine", "dos_stub_sha256", "wine_builtin", "timestamp",
                 "section_alignment", "file_alignment", "optional_header_sha256")
    for key in immutable:
        if before[key] != after[key]:
            raise BinaryError("PE runtime header changed: " + key)
    if (before["flags"] ^ after["flags"]) & ~0x208:
        raise BinaryError("PE runtime characteristics changed")
    for index, directory in enumerate(before["directories"]):
        other = after["directories"][index]
        if directory != other:
            raise BinaryError("PE data directory changed: " + str(index))
    runtime = [s for s in before["sections"] if not s["debug"]]
    if [s["name"] for s in runtime] != [s["name"] for s in after["sections"]]:
        raise BinaryError("PE runtime section set or order changed")
    padding_changes = []
    for left, right in zip(runtime, after["sections"]):
        keys = ("name", "rva", "virtual_size", "raw_size", "attributes",
                "content_sha256", "relocation_metadata")
        if any(left[k] != right[k] for k in keys):
            raise BinaryError("PE runtime section changed: " + left["name"])
        if left["tail_sha256"] != right["tail_sha256"]:
            # LLVM COFFWriter fills executable padding with INT3. Accept only
            # zero -> INT3 outside VirtualSize, never changes to real contents.
            if not (left["attributes"] & 0x20000000 and
                    left["tail_byte_values"] == [0] and
                    right["tail_byte_values"] == [0xcc]):
                raise BinaryError("unexpected PE raw padding change: " + left["name"])
            padding_changes.append(left["name"])
    return padding_changes
