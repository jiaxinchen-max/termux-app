package com.termux.localgames.importer;

import java.io.IOException;
import java.io.InputStream;

/** Minimal bounded DOS + PE signature check. */
public final class PeExecutableInspector {

    private static final int DOS_HEADER_SIZE = 64;
    private static final int MAX_PE_OFFSET = 1024 * 1024;

    public boolean isPortableExecutable(InputStream input) throws IOException {
        byte[] header = new byte[DOS_HEADER_SIZE];
        if (!readFully(input, header, 0, header.length)) return false;
        if (header[0] != 'M' || header[1] != 'Z') return false;

        int offset = (header[0x3c] & 0xff) |
            ((header[0x3d] & 0xff) << 8) |
            ((header[0x3e] & 0xff) << 16) |
            ((header[0x3f] & 0xff) << 24);
        if (offset < DOS_HEADER_SIZE || offset > MAX_PE_OFFSET) return false;
        if (!skipFully(input, offset - DOS_HEADER_SIZE)) return false;

        byte[] signature = new byte[4];
        return readFully(input, signature, 0, signature.length) &&
            signature[0] == 'P' && signature[1] == 'E' &&
            signature[2] == 0 && signature[3] == 0;
    }

    private static boolean readFully(InputStream input, byte[] target, int offset, int length)
        throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(target, offset + read, length - read);
            if (count < 0) return false;
            if (count == 0) {
                int value = input.read();
                if (value < 0) return false;
                target[offset + read] = (byte) value;
                read++;
            } else {
                read += count;
            }
        }
        return true;
    }

    private static boolean skipFully(InputStream input, int bytes) throws IOException {
        int skipped = 0;
        while (skipped < bytes) {
            long count = input.skip(bytes - skipped);
            if (count > 0) {
                skipped += (int) count;
            } else if (input.read() < 0) {
                return false;
            } else {
                skipped++;
            }
        }
        return true;
    }
}
