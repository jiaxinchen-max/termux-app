package com.termux.ui.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which Termux packages provide the Xlorie X server on which ABI.
 *
 * <p>Text format, one package per line: {@code <abi> <file> <sha256>}; {@code #} starts a comment.
 * {@code abi} is the Termux architecture (aarch64, arm, x86_64, i686). Adding a new ABI is a data
 * change in the manifest, never a code change.
 */
public final class XServerPackageManifest {

    /** One downloadable package. */
    public static final class Entry {
        private final String abi;
        private final String file;
        private final String sha256;

        Entry(String abi, String file, String sha256) {
            this.abi = abi;
            this.file = file;
            this.sha256 = sha256;
        }

        public String getAbi() { return abi; }
        public String getFile() { return file; }
        public String getSha256() { return sha256; }
    }

    private final List<Entry> entries;

    private XServerPackageManifest(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public static XServerPackageManifest parse(InputStream input) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) continue;
                String[] parts = text.split("\\s+");
                if (parts.length != 3
                    || !parts[0].matches("aarch64|arm|x86_64|i686")
                    || !parts[1].matches("[A-Za-z0-9._+-]{1,128}\\.deb")
                    || !parts[2].matches("[0-9a-f]{64}")) {
                    throw new IOException("invalid_x_server_manifest_line:" + text);
                }
                entries.add(new Entry(parts[0], parts[1], parts[2]));
            }
        }
        return new XServerPackageManifest(entries);
    }

    /** Termux architecture name for an Android ABI, or null for an unknown ABI. */
    public static String termuxArchFor(String androidAbi) {
        if (androidAbi == null) return null;
        switch (androidAbi) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "arm";
            case "x86_64": return "x86_64";
            case "x86": return "i686";
            default: return null;
        }
    }

    /** The packages for an Android ABI, in install order; empty when none are published yet. */
    public List<Entry> entriesFor(String androidAbi) {
        String arch = termuxArchFor(androidAbi);
        List<Entry> result = new ArrayList<>();
        if (arch == null) return result;
        for (Entry entry : entries) if (entry.abi.equals(arch)) result.add(entry);
        return result;
    }

    public boolean isSupported(String androidAbi) { return !entriesFor(androidAbi).isEmpty(); }
}
