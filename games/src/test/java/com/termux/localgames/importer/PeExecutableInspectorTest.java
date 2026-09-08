package com.termux.localgames.importer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;

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
}
