package com.termux.localgames.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parses user-entered arguments into values; launch code must quote each value independently. */
public final class LaunchArguments {

    private LaunchArguments() {}

    public static List<String> parse(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean tokenStarted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (character == quote) quote = 0;
                else if (character == '\\' && quote == '"' && index + 1 < value.length() &&
                    (value.charAt(index + 1) == '"' || value.charAt(index + 1) == '\\')) {
                    current.append(value.charAt(++index));
                } else current.append(character);
                tokenStarted = true;
            } else if (character == '\'' || character == '"') {
                quote = character;
                tokenStarted = true;
            } else if (character == '\\') {
                if (index + 1 < value.length() &&
                    (Character.isWhitespace(value.charAt(index + 1)) ||
                        value.charAt(index + 1) == '\'' || value.charAt(index + 1) == '"' ||
                        value.charAt(index + 1) == '\\')) {
                    current.append(value.charAt(++index));
                } else {
                    current.append(character);
                }
                tokenStarted = true;
            } else if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(character);
                tokenStarted = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("unterminated_quote");
        if (tokenStarted) arguments.add(current.toString());
        return Collections.unmodifiableList(arguments);
    }

    public static String format(List<String> arguments) {
        StringBuilder result = new StringBuilder();
        for (String argument : arguments) {
            if (argument == null) throw new IllegalArgumentException("argument must not be null");
            if (result.length() > 0) result.append(' ');
            result.append('"');
            for (int index = 0; index < argument.length(); index++) {
                char character = argument.charAt(index);
                if (character == '\\' || character == '"') result.append('\\');
                result.append(character);
            }
            result.append('"');
        }
        return result.toString();
    }
}
