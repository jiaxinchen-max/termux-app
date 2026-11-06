package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.ProotComponentBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared non-executable fields for setup, launch and persisted frozen bindings. */
public final class ProotBindingsCodec {
    private static final String[] FIELDS = {"componentId", "version", "sha256", "versionDirectory",
        "contentRelativePath", "guestTarget", "role", "revision"};

    public static void append(List<String> lines, List<ProotComponentBinding> bindings) {
        List<ProotComponentBinding> checked = ProotComponentBinding.immutableBindings(bindings);
        lines.add("bindingCount=" + checked.size());
        for (int i = 0; i < checked.size(); i++) {
            ProotComponentBinding b = checked.get(i);
            String[] values = {b.getComponentId(), Integer.toString(b.getVersion()), b.getSha256(),
                b.getVersionDirectory(), b.getContentRelativePath(), b.getGuestTarget(),
                b.getRole().name(), Integer.toString(ProotComponentBinding.REVISION)};
            for (int j = 0; j < FIELDS.length; j++) {
                lines.add("binding." + i + "." + FIELDS[j] + "=" +
                    Base64Text.encode(values[j].getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    public static List<ProotComponentBinding> decode(Map<String, String> fields,
                                                    Set<String> expected) throws IOException {
        expected.add("bindingCount");
        int count;
        String countText = fields.getOrDefault("bindingCount", "");
        if (!countText.matches("0|[1-9][0-9]?")) throw new IOException("invalid_proot_binding_count");
        try { count = Integer.parseInt(countText); }
        catch (NumberFormatException e) { throw new IOException("invalid_proot_binding_count", e); }
        if (count < 0 || count > ProotComponentBinding.MAX_BINDINGS) {
            throw new IOException("invalid_proot_binding_count");
        }
        List<ProotComponentBinding> result = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String[] values = new String[FIELDS.length];
                for (int j = 0; j < FIELDS.length; j++) {
                    String key = "binding." + i + "." + FIELDS[j];
                    expected.add(key);
                    String raw = fields.get(key);
                    if (raw == null) throw new IOException("missing_proot_binding_field:" + key);
                    values[j] = new String(Base64Text.decode(raw), StandardCharsets.UTF_8);
                    if (!Base64Text.encode(values[j].getBytes(StandardCharsets.UTF_8)).equals(raw)) {
                        throw new IOException("invalid_proot_binding_utf8");
                    }
                }
                if (!Integer.toString(ProotComponentBinding.REVISION).equals(values[7])) {
                    throw new IOException("unsupported_proot_binding_revision");
                }
                int version = Integer.parseInt(values[1]);
                if (!Integer.toString(version).equals(values[1])) throw new IOException("invalid_proot_binding_version");
                result.add(new ProotComponentBinding(values[0], version,
                    values[2], values[3], values[4], values[5],
                    ProotComponentBinding.Role.valueOf(values[6])));
            }
            return ProotComponentBinding.immutableBindings(result);
        } catch (IllegalArgumentException e) { throw new IOException("invalid_proot_binding", e); }
    }

    private ProotBindingsCodec() {}
}
