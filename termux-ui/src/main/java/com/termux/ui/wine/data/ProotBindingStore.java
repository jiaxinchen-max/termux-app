package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.ProotBindingSnapshot;
import com.termux.ui.wine.domain.ProotComponentBinding;
import com.termux.ui.wine.domain.RuntimeTranslator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic private snapshots for configuration preparation and task replay. No active lookup. */
public final class ProotBindingStore {
    private final File root;
    public ProotBindingStore(File root) { this.root = root; }

    public synchronized void write(String id, ProotBindingSnapshot snapshot) throws IOException {
        File file = file(id);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("proot_binding_directory_failed");
        List<String> lines = new ArrayList<>();
        lines.add("schemaVersion=1");
        encoded(lines, "winePackage", snapshot.getWinePackage());
        encoded(lines, "dxWrapper", snapshot.getDxWrapper());
        encoded(lines, "translator", snapshot.getTranslator().getStorageValue());
        encoded(lines, "box64ComponentId", snapshot.getBox64ComponentId());
        encoded(lines, "recipeSha256", snapshot.getRecipeSha256());
        ProotBindingsCodec.append(lines, snapshot.getBindings());
        File temp = File.createTempFile(id + ".tmp.", ".tmp", root);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                for (String line : lines) out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new IOException("proot_binding_publish_failed");
        } finally { temp.delete(); }
    }

    public synchronized ProotBindingSnapshot read(String id) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        File file = file(id);
        if (!file.isFile()) throw new IOException("rootfs_component_prepare_required");
        if (!file.getCanonicalFile().equals(new File(root.getCanonicalFile(), file.getName()))) {
            throw new IOException("proot_binding_snapshot_escape");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (fields.size() > 256 || line.length() > 65536) throw new IOException("proot_snapshot_too_large");
                int split = line.indexOf('=');
                if (split < 1 || fields.put(line.substring(0, split), line.substring(split + 1)) != null) {
                    throw new IOException("invalid_proot_snapshot_fields");
                }
            }
        }
        if (!"1".equals(fields.get("schemaVersion"))) throw new IOException("unsupported_proot_snapshot_schema");
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, "schemaVersion", "winePackage", "dxWrapper", "translator", "box64ComponentId", "recipeSha256");
        List<ProotComponentBinding> bindings = ProotBindingsCodec.decode(fields, expected);
        if (!expected.equals(fields.keySet())) throw new IOException("invalid_proot_snapshot_fields");
        try {
            return new ProotBindingSnapshot(decoded(fields, "winePackage"), decoded(fields, "dxWrapper"),
                RuntimeTranslator.fromStorageValue(decoded(fields, "translator")),
                decoded(fields, "box64ComponentId"), decoded(fields, "recipeSha256"), bindings);
        } catch (IllegalArgumentException e) { throw new IOException("invalid_proot_snapshot", e); }
    }

    private File file(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid_proot_snapshot_id");
        }
        return new File(root, id + ".bindings");
    }
    private static void encoded(List<String> lines, String key, String value) {
        lines.add(key + "=" + Base64Text.encode(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String decoded(Map<String, String> fields, String key) {
        return new String(Base64Text.decode(fields.get(key)), StandardCharsets.UTF_8);
    }
}
