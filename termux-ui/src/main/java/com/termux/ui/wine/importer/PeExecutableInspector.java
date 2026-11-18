package com.termux.ui.wine.importer;

import java.io.IOException;
import java.io.InputStream;

/** Bounded DOS/PE inspection; file names do not determine the content type. */
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

    /** Windows GUI/console applications, excluding DLLs, drivers and other PE subsystems. */
    public boolean isWindowsProgram(InputStream input) throws IOException {
        if (!isPortableExecutable(input)) return false;
        byte[] coff = new byte[20];
        if (!readFully(input, coff, 0, coff.length)) return false;
        int characteristics = unsignedShort(coff, 18);
        if (unsignedShort(coff, 0) == 0 || unsignedShort(coff, 2) == 0
                || (characteristics & 0x0002) == 0 || (characteristics & 0x3000) != 0) return false;
        int optionalSize = unsignedShort(coff, 16);
        if (optionalSize < 96) return false;
        // SizeOfOptionalHeader is a 16-bit field, so this allocation/read is bounded to 64 KiB.
        byte[] optional = new byte[optionalSize];
        if (!readFully(input, optional, 0, optional.length)) return false;
        int magic = unsignedShort(optional, 0);
        if (magic != 0x10b && magic != 0x20b) return false;
        if (magic == 0x20b && optionalSize < 112) return false;
        int subsystem = unsignedShort(optional, 68);
        return subsystem == 2 || subsystem == 3;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
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
