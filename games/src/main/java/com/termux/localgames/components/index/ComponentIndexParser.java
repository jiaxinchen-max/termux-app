package com.termux.localgames.components.index;

import com.termux.localgames.domain.GameRuntimeBackendType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ComponentIndexParser {

    private static final int MAX_INDEX_BYTES = 1024 * 1024;

    public ComponentIndex parse(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        Object rootValue = new StrictJsonParser(read(input)).parse();
        Map<String, Object> root = object(rootValue, "root");
        requireKeys(root, "root", "schemaVersion", "generatedAt", "packages");
        int schemaVersion = integer(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != 2) {
            throw new IOException("Unsupported component index schema");
        }
        String generatedAt = string(root.get("generatedAt"), "generatedAt");
        List<Object> packages = array(root.get("packages"), "packages");
        List<ComponentDescriptor> descriptors = new ArrayList<>(packages.size());
        try {
            for (Object value : packages) {
                descriptors.add(parsePackage(object(value, "package")));
            }
            return new ComponentIndex(schemaVersion, generatedAt, descriptors);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid component index", e);
        }
    }

    private static ComponentDescriptor parsePackage(Map<String, Object> item) throws IOException {
        requireKeys(item, "package", "id", "category", "type", "displayName",
            "versionName", "summary", "framework", "base", "recommended",
            "profileValue", "runtimeBackends", "version", "url", "size", "sha256");
        Set<GameRuntimeBackendType> backends = new LinkedHashSet<>();
        for (Object value : array(item.get("runtimeBackends"), "runtimeBackends")) {
            try {
                if (!backends.add(GameRuntimeBackendType.fromStorageValue(
                    string(value, "runtimeBackend")))) {
                    throw new IOException("duplicate runtimeBackend");
                }
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid runtimeBackend", error);
            }
        }
        try {
            return new ComponentDescriptor(
                string(item.get("id"), "id"),
                string(item.get("category"), "category"),
                integer(item.get("version"), "version"),
                string(item.get("url"), "url"),
                number(item.get("size"), "size"),
                string(item.get("sha256"), "sha256"),
                ComponentType.fromStorageValue(string(item.get("type"), "type")),
                string(item.get("displayName"), "displayName"),
                string(item.get("versionName"), "versionName"),
                string(item.get("summary"), "summary"),
                string(item.get("framework"), "framework"),
                bool(item.get("base"), "base"),
                bool(item.get("recommended"), "recommended"),
                optionalString(item.get("profileValue"), "profileValue"),
                backends
            );
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid component metadata", error);
        }
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_INDEX_BYTES) throw new IOException("Component index is too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) throws IOException {
        if (!(value instanceof Map)) throw new IOException(field + " must be an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String field) throws IOException {
        if (!(value instanceof List)) throw new IOException(field + " must be an array");
        return (List<Object>) value;
    }

    private static String string(Object value, String field) throws IOException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IOException(field + " must be a non-empty string");
        }
        return (String) value;
    }

    private static String optionalString(Object value, String field) throws IOException {
        if (!(value instanceof String)) throw new IOException(field + " must be a string");
        return (String) value;
    }

    private static boolean bool(Object value, String field) throws IOException {
        if (!(value instanceof Boolean)) throw new IOException(field + " must be a boolean");
        return (Boolean) value;
    }

    private static long number(Object value, String field) throws IOException {
        if (!(value instanceof Long)) throw new IOException(field + " must be an integer");
        return (Long) value;
    }

    private static int integer(Object value, String field) throws IOException {
        long number = number(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IOException(field + " is outside int range");
        }
        return (int) number;
    }

    private static void requireKeys(Map<String, Object> value, String field,
                                    String... expected) throws IOException {
        Set<String> keys = new HashSet<>(Arrays.asList(expected));
        if (!value.keySet().equals(keys)) {
            throw new IOException(field + " fields do not match schema");
        }
    }
}
