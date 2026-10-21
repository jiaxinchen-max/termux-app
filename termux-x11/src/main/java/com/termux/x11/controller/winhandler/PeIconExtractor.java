package com.termux.x11.controller.winhandler;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the main icon of a Windows PE executable (the first RT_GROUP_ICON and its RT_ICON images)
 * and returns it as a single-image .ico file, which {@code BitmapFactory} decodes directly. Only
 * the headers, the resource directory and the chosen image are read, so a multi-hundred-MB game
 * executable costs a few KB of I/O. Any malformed, truncated or oversized input yields {@code null}.
 */
public final class PeIconExtractor {
    /** Largest single icon image accepted (a 256x256 32-bit DIB is about 270 KB). */
    static final int MAX_ICON_BYTES = 512 * 1024;
    private static final int MAX_GROUP_BYTES = 16 * 1024;
    private static final int MAX_DIR_ENTRIES = 4096;
    private static final int MAX_GROUP_ENTRIES = 64;
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;

    private PeIconExtractor() { }

    /** The best icon of {@code exe} for an icon about {@code targetPx} wide, as .ico bytes, or null. */
    @Nullable
    public static byte[] extractIcon(File exe, int targetPx) {
        try (RandomAccessFile file = new RandomAccessFile(exe, "r")) {
            return extract(file, targetPx);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    static byte[] extract(RandomAccessFile file, int targetPx) throws IOException {
        long length = file.length();
        if (length < 0x40) return null;
        byte[] dos = read(file, 0, 0x40, length);
        if (u16(dos, 0) != 0x5A4D) return null; // "MZ"
        long peOffset = u32(dos, 0x3C);
        byte[] coff = read(file, peOffset, 24, length);
        if (u32(coff, 0) != 0x00004550L) return null; // "PE\0\0"
        int sectionCount = u16(coff, 6);
        int optionalSize = u16(coff, 20);
        if (sectionCount <= 0 || sectionCount > 96 || optionalSize < 96) return null;
        long optionalOffset = peOffset + 24;
        int magic = u16(read(file, optionalOffset, 2, length), 0);
        int dataDirectoryOffset;
        if (magic == 0x10B) dataDirectoryOffset = 96;
        else if (magic == 0x20B) dataDirectoryOffset = 112;
        else return null;
        if (optionalSize < dataDirectoryOffset + 3 * 8) return null;
        byte[] resourceEntry = read(file, optionalOffset + dataDirectoryOffset + 2 * 8, 8, length);
        long resourceRva = u32(resourceEntry, 0);
        if (resourceRva == 0) return null;

        byte[] sectionTable = read(file, optionalOffset + optionalSize, sectionCount * 40, length);
        Sections sections = new Sections(sectionTable, sectionCount);
        long resourceBase = sections.toFileOffset(resourceRva);
        if (resourceBase < 0) return null;
        Resources resources = new Resources(file, length, resourceBase, sections);

        byte[] group = resources.firstOfType(RT_GROUP_ICON, MAX_GROUP_BYTES);
        if (group == null || group.length < 6) return null;
        int count = u16(group, 4);
        if (u16(group, 2) != 1 || count <= 0 || count > MAX_GROUP_ENTRIES
            || group.length < 6 + count * 14) return null;

        List<GroupEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 14;
            int width = group[base] & 0xFF;
            int height = group[base + 1] & 0xFF;
            entries.add(new GroupEntry(group, base, width == 0 ? 256 : width, height == 0 ? 256 : height,
                u16(group, base + 6), (int) u32(group, base + 8), u16(group, base + 12)));
        }
        entries.sort((a, b) -> compare(a, b, targetPx));
        for (GroupEntry entry : entries) {
            if (entry.bytes <= 0 || entry.bytes > MAX_ICON_BYTES) continue;
            byte[] image = resources.idOfType(RT_ICON, entry.id, MAX_ICON_BYTES);
            if (image != null && image.length > 0) return buildIco(entry, image);
        }
        return null;
    }

    /** Smallest image that is at least {@code targetPx}, then the deepest colour; else the largest. */
    private static int compare(GroupEntry a, GroupEntry b, int targetPx) {
        boolean aBig = a.size() >= targetPx;
        boolean bBig = b.size() >= targetPx;
        if (aBig != bBig) return aBig ? -1 : 1;
        if (aBig) {
            if (a.size() != b.size()) return Integer.compare(a.size(), b.size());
        } else if (a.size() != b.size()) {
            return Integer.compare(b.size(), a.size());
        }
        return Integer.compare(b.bitCount, a.bitCount);
    }

    private static byte[] buildIco(GroupEntry entry, byte[] image) {
        byte[] ico = new byte[22 + image.length];
        ico[2] = 1; // type: icon
        ico[4] = 1; // one image
        System.arraycopy(entry.raw, entry.rawOffset, ico, 6, 4); // width, height, colours, reserved
        ico[10] = entry.raw[entry.rawOffset + 4]; // planes
        ico[11] = entry.raw[entry.rawOffset + 5];
        ico[12] = entry.raw[entry.rawOffset + 6]; // bit count
        ico[13] = entry.raw[entry.rawOffset + 7];
        put32(ico, 14, image.length);
        put32(ico, 18, 22);
        System.arraycopy(image, 0, ico, 22, image.length);
        return ico;
    }

    private static final class GroupEntry {
        final byte[] raw;
        final int rawOffset;
        final int width;
        final int height;
        final int bitCount;
        final int bytes;
        final int id;

        GroupEntry(byte[] raw, int rawOffset, int width, int height, int bitCount, int bytes, int id) {
            this.raw = raw;
            this.rawOffset = rawOffset;
            this.width = width;
            this.height = height;
            this.bitCount = bitCount;
            this.bytes = bytes;
            this.id = id;
        }

        int size() {
            return Math.max(width, height);
        }
    }

    private static final class Sections {
        private final long[] virtualAddress;
        private final long[] virtualSize;
        private final long[] rawPointer;
        private final long[] rawSize;

        Sections(byte[] table, int count) {
            virtualAddress = new long[count];
            virtualSize = new long[count];
            rawPointer = new long[count];
            rawSize = new long[count];
            for (int i = 0; i < count; i++) {
                int base = i * 40;
                virtualSize[i] = u32(table, base + 8);
                virtualAddress[i] = u32(table, base + 12);
                rawSize[i] = u32(table, base + 16);
                rawPointer[i] = u32(table, base + 20);
            }
        }

        /** File offset of {@code rva}, or -1 when no section holds it. */
        long toFileOffset(long rva) {
            for (int i = 0; i < virtualAddress.length; i++) {
                long span = Math.max(virtualSize[i], rawSize[i]);
                if (rva >= virtualAddress[i] && rva < virtualAddress[i] + span) {
                    return rawPointer[i] + (rva - virtualAddress[i]);
                }
            }
            return -1;
        }
    }

    /** Three-level resource tree walker: type -> id/name -> language -> data. */
    private static final class Resources {
        private final RandomAccessFile file;
        private final long length;
        private final long base;
        private final Sections sections;

        Resources(RandomAccessFile file, long length, long base, Sections sections) {
            this.file = file;
            this.length = length;
            this.base = base;
            this.sections = sections;
        }

        @Nullable
        byte[] firstOfType(int type, int maxBytes) throws IOException {
            long[][] typeDir = entries(base);
            long typeOffset = find(typeDir, type);
            if (typeOffset < 0) return null;
            long[][] idDir = entries(base + (typeOffset & 0x7FFFFFFFL));
            if (idDir.length == 0 || (idDir[0][1] & 0x80000000L) == 0) return null;
            return firstLanguage(idDir[0][1], maxBytes);
        }

        @Nullable
        byte[] idOfType(int type, int id, int maxBytes) throws IOException {
            long typeOffset = find(entries(base), type);
            if (typeOffset < 0) return null;
            long idOffset = find(entries(base + (typeOffset & 0x7FFFFFFFL)), id);
            if (idOffset < 0 || (idOffset & 0x80000000L) == 0) return null;
            return firstLanguage(idOffset, maxBytes);
        }

        @Nullable
        private byte[] firstLanguage(long subdirectory, int maxBytes) throws IOException {
            long[][] languages = entries(base + (subdirectory & 0x7FFFFFFFL));
            if (languages.length == 0 || (languages[0][1] & 0x80000000L) != 0) return null;
            byte[] dataEntry = read(file, base + languages[0][1], 8, length);
            long dataRva = u32(dataEntry, 0);
            long size = u32(dataEntry, 4);
            if (size <= 0 || size > maxBytes) return null;
            long offset = sections.toFileOffset(dataRva);
            if (offset < 0) return null;
            return read(file, offset, (int) size, length);
        }

        /** {nameOrId, offset} pairs of the directory at {@code offset}. */
        private long[][] entries(long offset) throws IOException {
            byte[] header = read(file, offset, 16, length);
            int count = u16(header, 12) + u16(header, 14);
            if (count > MAX_DIR_ENTRIES) throw new IOException("too many resource entries");
            byte[] raw = read(file, offset + 16, count * 8, length);
            long[][] result = new long[count][2];
            for (int i = 0; i < count; i++) {
                result[i][0] = u32(raw, i * 8);
                result[i][1] = u32(raw, i * 8 + 4);
            }
            return result;
        }

        /** Offset field of the entry with integer id {@code id}, or -1. */
        private static long find(long[][] entries, int id) {
            for (long[] entry : entries) {
                if ((entry[0] & 0x80000000L) == 0 && entry[0] == id) return entry[1];
            }
            return -1;
        }
    }

    private static byte[] read(RandomAccessFile file, long offset, int size, long length) throws IOException {
        if (offset < 0 || size < 0 || offset + size > length) throw new IOException("out of range");
        byte[] data = new byte[size];
        file.seek(offset);
        file.readFully(data);
        return data;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static void put32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
        data[offset + 2] = (byte) (value >> 16);
        data[offset + 3] = (byte) (value >> 24);
    }
}
