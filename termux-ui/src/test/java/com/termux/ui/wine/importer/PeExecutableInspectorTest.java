package com.termux.ui.wine.importer;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeExecutableInspectorTest {

    private final PeExecutableInspector inspector = new PeExecutableInspector();

    @Test
    public void acceptsDosAndPeSignaturesAtBoundedOffset() throws Exception {
        assertTrue(inspector.isPortableExecutable(new ByteArrayInputStream(peBytes())));
    }

    @Test
    public void rejectsExtensionSpoofsAndOutOfRangeOffsets() throws Exception {
        byte[] missingPe = peBytes();
        missingPe[128] = 'N';
        assertFalse(inspector.isPortableExecutable(new ByteArrayInputStream(missingPe)));

        byte[] invalidOffset = peBytes();
        invalidOffset[0x3c] = 1;
        invalidOffset[0x3d] = 0;
        assertFalse(inspector.isPortableExecutable(new ByteArrayInputStream(invalidOffset)));
        assertFalse(inspector.isPortableExecutable(new ByteArrayInputStream(new byte[] {'M', 'Z'})));
    }

    static byte[] peBytes() {
        byte[] bytes = new byte[256];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[0x3c] = (byte) 128;
        bytes[128] = 'P';
        bytes[129] = 'E';
        return bytes;
    }

    @Test public void recognisesPe32AndPe32PlusWindowsApplications() throws Exception {
        for (boolean plus : new boolean[]{false, true}) {
            assertTrue(program(windowsProgram(plus, 2, 0x0002)));
            assertTrue(program(windowsProgram(plus, 3, 0x0022)));
        }
    }

    @Test public void excludesLibrariesDriversEfiAndNonExecutableImages() throws Exception {
        assertFalse(program(windowsProgram(true, 2, 0x2002)));
        assertFalse(program(windowsProgram(true, 1, 0x1002)));
        assertFalse(program(windowsProgram(true, 10, 0x0002)));
        assertFalse(program(windowsProgram(true, 2, 0)));
        assertFalse(program(peBytes()));
        assertFalse(program("not a program.exe".getBytes("UTF-8")));
        assertFalse(program(new byte[]{'P','K',3,4}));
    }

    @Test public void rejectsInvalidOffsetsAndTruncatedOrInvalidHeaders() throws Exception {
        byte[] good = windowsProgram(true, 2, 0x0002);
        for (int length : new int[]{0, 2, 63, 129, 151, 153, 200, 391})
            assertFalse(program(Arrays.copyOf(good, length)));
        for (int offset : new int[]{0, 63, 1024 * 1024 + 1, -1}) {
            byte[] bad = good.clone();
            java.nio.ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x3c, offset);
            assertFalse(program(bad));
        }
        byte[] bad = good.clone(); bad[152] = 0;
        assertFalse(program(bad));
        bad = good.clone(); bad[148] = 70; bad[149] = 0;
        assertFalse(program(bad));
        bad = good.clone(); bad[134] = 0;
        assertFalse(program(bad));
    }

    @Test public void handlesStreamsWhoseBulkReadAndSkipMakeNoProgress() throws Exception {
        byte[] bytes = windowsProgram(true, 3, 2);
        assertTrue(inspector.isWindowsProgram(new ByteArrayInputStream(bytes) {
            @Override public int read(byte[] b, int off, int len) { return 0; }
            @Override public long skip(long n) { return 0; }
        }));
    }

    private boolean program(byte[] bytes) throws Exception {
        return inspector.isWindowsProgram(new ByteArrayInputStream(bytes));
    }

    private byte[] windowsProgram(boolean plus, int subsystem, int characteristics) {
        byte[] bytes = new byte[1024];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short)0x5a4d).putInt(0x3c, 128).putInt(128, 0x4550);
        b.putShort(132, (short)(plus ? 0x8664 : 0x14c)).putShort(134, (short)1);
        b.putShort(148, (short)(plus ? 240 : 224)).putShort(150, (short)characteristics);
        int optional = 152;
        b.putShort(optional, (short)(plus ? 0x20b : 0x10b));
        b.putInt(optional + 16, 0x1000).putInt(optional + 32, 0x1000).putInt(optional + 36, 512);
        b.putInt(optional + 56, 0x2000).putInt(optional + 60, 512).putShort(optional + 68, (short)subsystem);
        int section = optional + (plus ? 240 : 224);
        b.putInt(section, 0x7865742e).put(section + 4, (byte)'t');
        b.putInt(section + 8, 1).putInt(section + 12, 0x1000).putInt(section + 16, 512);
        b.putInt(section + 20, 512).putInt(section + 36, 0x60000020);
        bytes[512] = (byte)0xc3;
        return bytes;
    }
}
