package com.termux.localgames.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DomainValidation {

    private DomainValidation() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String optionalText(String value) {
        return value == null ? "" : value;
    }

    static int requireProgress(int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        return progress;
    }

    static List<String> immutableTextList(List<String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(requireText(value, field));
        }
        return Collections.unmodifiableList(copy);
    }

    static Map<String, String> immutableTextMap(Map<String, String> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            copy.put(requireText(entry.getKey(), field + " key"),
                requireText(entry.getValue(), field + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
