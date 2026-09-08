package com.termux.localgames.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the KEY=value token format used by existing TermuxBox configurations. */
public final class RuntimeEnvironment {

    private RuntimeEnvironment() {}

    public static Map<String, String> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyMap();
        List<String> tokens = tokenize(text);
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : tokens) {
            int separator = token.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("invalid environment entry");
            String key = token.substring(0, separator);
            String value = token.substring(separator + 1);
            if (value.isEmpty()) throw new IllegalArgumentException("empty environment value");
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate environment key: " + key);
            }
        }
        RuntimeProfile validated = new RuntimeProfile("validation", "wine", "graphics", "dx",
            "audio", "1x1", "preset", result, "", Collections.emptyMap());
        return validated.getEnvironment();
    }

    public static String format(Map<String, String> environment) {
        if (environment == null) throw new IllegalArgumentException("environment must not be null");
        List<String> keys = new ArrayList<>(environment.keySet());
        Collections.sort(keys);
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            if (result.length() > 0) result.append('\n');
            result.append(key).append('=').append(quote(environment.get(key)));
        }
        return result.toString();
    }

    private static List<String> tokenize(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaping) {
                token.append(character);
                escaping = false;
            } else if (character == '\\' && quote != '\'') {
                escaping = true;
            } else if (quote != 0) {
                if (character == quote) quote = 0;
                else token.append(character);
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (escaping || quote != 0) throw new IllegalArgumentException("unterminated environment quote");
        if (token.length() > 0) result.add(token.toString());
        return result;
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("empty environment value");
        if (!containsWhitespaceOrQuote(value)) return value;
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static boolean containsWhitespaceOrQuote(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || character == '\'' || character == '"' ||
                character == '\\') return true;
        }
        return false;
    }
}
