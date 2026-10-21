package com.termux.x11.controller.winhandler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public class PeIconExtractorTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** One icon image of a synthetic executable. */
    private static final class Image {
        final int id;
        final int size;
        final int bitCount;
        final byte[] data;

        Image(int id, int size, int bitCount, int fill, int length) {
            this.id = id;
            this.size = size;
            this.bitCount = bitCount;
            this.data = new byte[length];
            Arrays.fill(data, (byte) fill);
        }
    }

    // ---- a minimal PE32+ file: DOS header, PE headers and one .rsrc section ----

    private static final int PE_OFFSET = 0x80;
    private static final int OPTIONAL_SIZE = 240;
    private static final int SECTION_RVA = 0x1000;
    private static final int SECTION_RAW = 0x400;

    private static byte[] pe(boolean pe32Plus, Image... images) throws IOException {
        return pe(pe32Plus, true, images);
    }

    /** Resource section: root -> {GROUP_ICON(14), ICON(3)} -> ids -> language -> data entries. */
    private static byte[] pe(boolean pe32Plus, boolean withResources, Image... images) throws IOException {
        byte[] section = withResources ? resourceSection(images) : new byte[0];
        int optionalSize = pe32Plus ? OPTIONAL_SIZE : 224;
        ByteBuffer headers = ByteBuffer.allocate(SECTION_RAW).order(ByteOrder.LITTLE_ENDIAN);
        headers.putShort(0, (short) 0x5A4D);
        headers.putInt(0x3C, PE_OFFSET);
        headers.putInt(PE_OFFSET, 0x00004550);
        headers.putShort(PE_OFFSET + 6, (short) 1);
        headers.putShort(PE_OFFSET + 20, (short) optionalSize);
        int optional = PE_OFFSET + 24;
        headers.putShort(optional, (short) (pe32Plus ? 0x20B : 0x10B));
        int dataDirectories = optional + (pe32Plus ? 112 : 96);
        if (withResources) {
            headers.putInt(dataDirectories + 2 * 8, SECTION_RVA);
            headers.putInt(dataDirectories + 2 * 8 + 4, section.length);
        }
        int sectionHeader = optional + optionalSize;
        headers.put(sectionHeader, (byte) '.');
        headers.putInt(sectionHeader + 8, section.length);
        headers.putInt(sectionHeader + 12, SECTION_RVA);
        headers.putInt(sectionHeader + 16, section.length);
        headers.putInt(sectionHeader + 20, SECTION_RAW);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(headers.array());
        out.write(section);
        return out.toByteArray();
    }

    private static byte[] resourceSection(Image... images) {
        // Layout: root dir, group-type dir, icon-type dir, then per-id dirs, data entries, data.
        int payload = 0;
        for (Image image : images) payload += image.data.length + 4;
        ByteBuffer b = ByteBuffer.allocate(64 * 1024 + payload).order(ByteOrder.LITTLE_ENDIAN);
        int rootOffset = 0;
        int groupTypeDir = 16 + 2 * 8;
        int iconTypeDir = groupTypeDir + 16 + 8;
        int cursor = iconTypeDir + 16 + images.length * 8;

        // GRPICONDIR payload.
        byte[] group = new byte[6 + images.length * 14];
        ByteBuffer g = ByteBuffer.wrap(group).order(ByteOrder.LITTLE_ENDIAN);
        g.putShort(2, (short) 1);
        g.putShort(4, (short) images.length);
        for (int i = 0; i < images.length; i++) {
            int base = 6 + i * 14;
            group[base] = (byte) (images[i].size >= 256 ? 0 : images[i].size);
            group[base + 1] = group[base];
            g.putShort(base + 4, (short) 1);
            g.putShort(base + 6, (short) images[i].bitCount);
            g.putInt(base + 8, images[i].data.length);
            g.putShort(base + 12, (short) images[i].id);
        }

        // root: types 3 (ICON) and 14 (GROUP_ICON), ordered by id as in a real file
        b.putShort(rootOffset + 14, (short) 2);
        b.putInt(16, 3);
        b.putInt(20, 0x80000000 | iconTypeDir);
        b.putInt(24, 14);
        b.putInt(28, 0x80000000 | groupTypeDir);
        // group type dir -> id 1 -> language dir -> data
        b.putShort(groupTypeDir + 14, (short) 1);
        int groupIdDir = cursor;
        b.putInt(groupTypeDir + 16, 1);
        b.putInt(groupTypeDir + 20, 0x80000000 | groupIdDir);
        cursor += 16 + 8;
        b.putShort(groupIdDir + 14, (short) 1);
        b.putInt(groupIdDir + 16, 0x409);
        int groupDataEntry = cursor;
        b.putInt(groupIdDir + 20, groupDataEntry);
        cursor += 16;
        // icon type dir -> one id per image
        b.putShort(iconTypeDir + 14, (short) images.length);
        int[] idDirs = new int[images.length];
        for (int i = 0; i < images.length; i++) {
            idDirs[i] = cursor;
            b.putInt(iconTypeDir + 16 + i * 8, images[i].id);
            b.putInt(iconTypeDir + 20 + i * 8, 0x80000000 | idDirs[i]);
            cursor += 16 + 8;
        }
        int[] dataEntries = new int[images.length];
        for (int i = 0; i < images.length; i++) {
            b.putShort(idDirs[i] + 14, (short) 1);
            b.putInt(idDirs[i] + 16, 0x409);
            dataEntries[i] = cursor;
            b.putInt(idDirs[i] + 20, dataEntries[i]);
            cursor += 16;
        }
        int dataStart = (cursor + 3) & ~3;
        int at = dataStart;
        b.putInt(groupDataEntry, SECTION_RVA + at);
        b.putInt(groupDataEntry + 4, group.length);
        b.position(at);
        b.put(group);
        at += group.length;
        for (int i = 0; i < images.length; i++) {
            at = (at + 3) & ~3;
            b.putInt(dataEntries[i], SECTION_RVA + at);
            b.putInt(dataEntries[i] + 4, images[i].data.length);
            b.position(at);
            b.put(images[i].data);
            at += images[i].data.length;
        }
        return Arrays.copyOf(b.array(), at);
    }

    private File write(byte[] content) throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), content);
        return file;
    }

    private static byte[] imageOf(byte[] ico) {
        return Arrays.copyOfRange(ico, 22, ico.length);
    }

    // ---- tests ----

    @Test public void picksSmallestImageAtLeastTheTargetSize() throws IOException {
        Image small = new Image(1, 16, 32, 0x11, 40);
        Image medium = new Image(2, 48, 32, 0x22, 60);
        Image large = new Image(3, 256, 32, 0x33, 80);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(true, small, medium, large)), 40);
        assertNotNull(ico);
        assertArrayEquals(medium.data, imageOf(ico));
    }

    @Test public void fallsBackToTheLargestImageWhenAllAreSmaller() throws IOException {
        Image a = new Image(1, 16, 32, 0x11, 40);
        Image b = new Image(2, 32, 32, 0x22, 50);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(true, a, b)), 64);
        assertNotNull(ico);
        assertArrayEquals(b.data, imageOf(ico));
    }

    @Test public void prefersTheDeeperColourAtTheSameSize() throws IOException {
        Image mono = new Image(1, 32, 8, 0x11, 40);
        Image rgba = new Image(2, 32, 32, 0x22, 70);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(true, mono, rgba)), 32);
        assertNotNull(ico);
        assertArrayEquals(rgba.data, imageOf(ico));
    }

    @Test public void buildsAValidSingleImageIco() throws IOException {
        Image only = new Image(7, 48, 32, 0x5A, 100);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(true, only)), 48);
        assertNotNull(ico);
        ByteBuffer b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getShort(0));
        assertEquals(1, b.getShort(2)); // icon type
        assertEquals(1, b.getShort(4)); // one image
        assertEquals(48, b.get(6) & 0xFF);
        assertEquals(48, b.get(7) & 0xFF);
        assertEquals(32, b.getShort(12)); // bit count
        assertEquals(100, b.getInt(14)); // image size
        assertEquals(22, b.getInt(18)); // image offset
        assertEquals(22 + 100, ico.length);
    }

    @Test public void readsPe32Files() throws IOException {
        Image only = new Image(1, 32, 32, 0x44, 60);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(false, only)), 32);
        assertNotNull(ico);
        assertArrayEquals(only.data, imageOf(ico));
    }

    @Test public void executableWithoutResourcesHasNoIcon() throws IOException {
        assertNull(PeIconExtractor.extractIcon(write(pe(true, false)), 32));
    }

    @Test public void notAPeFileHasNoIcon() throws IOException {
        assertNull(PeIconExtractor.extractIcon(write("just some text, not an executable".getBytes()), 32));
        assertNull(PeIconExtractor.extractIcon(write(new byte[0]), 32));
    }

    @Test public void missingFileHasNoIcon() {
        assertNull(PeIconExtractor.extractIcon(new File(folder.getRoot(), "missing.exe"), 32));
    }

    @Test public void truncatedFileHasNoIconAndDoesNotThrow() throws IOException {
        byte[] full = pe(true, new Image(1, 32, 32, 0x44, 60));
        for (int cut : new int[] {0x30, 0x90, 0x300, 0x410, full.length - 20}) {
            assertNull("cut at " + cut, PeIconExtractor.extractIcon(write(Arrays.copyOf(full, cut)), 32));
        }
    }

    @Test public void oversizedImageIsRejected() throws IOException {
        Image huge = new Image(1, 256, 32, 0x44, PeIconExtractor.MAX_ICON_BYTES + 1);
        assertNull(PeIconExtractor.extractIcon(write(pe(true, huge)), 256));
    }

    @Test public void oversizedImageIsSkippedForTheNextBest() throws IOException {
        Image huge = new Image(1, 256, 32, 0x44, PeIconExtractor.MAX_ICON_BYTES + 1);
        Image small = new Image(2, 32, 32, 0x55, 60);
        byte[] ico = PeIconExtractor.extractIcon(write(pe(true, huge, small)), 64);
        assertNotNull(ico);
        assertArrayEquals(small.data, imageOf(ico));
    }
}
