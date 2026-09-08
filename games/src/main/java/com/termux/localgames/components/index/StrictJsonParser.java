package com.termux.localgames.components.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StrictJsonParser {

    private final String source;
    private int offset;

    public StrictJsonParser(String source) {
        this.source = source;
    }

    public Object parse() throws IOException {
        Object value = readValue();
        skipWhitespace();
        if (offset != source.length()) {
            throw error("Trailing JSON content");
        }
        return value;
    }

    private Object readValue() throws IOException {
        skipWhitespace();
        if (offset >= source.length()) throw error("Unexpected end of JSON");
        char token = source.charAt(offset);
        if (token == '{') return readObject();
        if (token == '[') return readArray();
        if (token == '"') return readString();
        if (token == '-' || Character.isDigit(token)) return readNumber();
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;
        if (match("null")) return null;
        throw error("Unexpected JSON token");
    }

    private Map<String, Object> readObject() throws IOException {
        offset++;
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) return object;
        while (true) {
            skipWhitespace();
            if (offset >= source.length() || source.charAt(offset) != '"') {
                throw error("Object key must be a string");
            }
            String key = readString();
            if (object.containsKey(key)) throw error("Duplicate object key: " + key);
            skipWhitespace();
            require(':');
            object.put(key, readValue());
            skipWhitespace();
            if (consume('}')) return object;
            require(',');
        }
    }

    private List<Object> readArray() throws IOException {
        offset++;
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) return array;
        while (true) {
            array.add(readValue());
            skipWhitespace();
            if (consume(']')) return array;
            require(',');
        }
    }

    private String readString() throws IOException {
        require('"');
        StringBuilder value = new StringBuilder();
        while (offset < source.length()) {
            char current = source.charAt(offset++);
            if (current == '"') return value.toString();
            if (current == '\\') {
                if (offset >= source.length()) throw error("Incomplete string escape");
                char escaped = source.charAt(offset++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(readUnicode()); break;
                    default: throw error("Invalid string escape");
                }
            } else {
                if (current < 0x20) throw error("Control character in string");
                value.append(current);
            }
        }
        throw error("Unterminated string");
    }

    private char readUnicode() throws IOException {
        if (offset + 4 > source.length()) throw error("Incomplete unicode escape");
        try {
            char value = (char) Integer.parseInt(source.substring(offset, offset + 4), 16);
            offset += 4;
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid unicode escape");
        }
    }

    private Long readNumber() throws IOException {
        int start = offset;
        if (source.charAt(offset) == '-') offset++;
        int digits = offset;
        while (offset < source.length() && Character.isDigit(source.charAt(offset))) offset++;
        if (digits == offset) throw error("Invalid number");
        if (offset < source.length() &&
            (source.charAt(offset) == '.' || source.charAt(offset) == 'e' ||
                source.charAt(offset) == 'E')) {
            throw error("Only integer JSON numbers are supported");
        }
        try {
            return Long.parseLong(source.substring(start, offset));
        } catch (NumberFormatException e) {
            throw error("Number outside long range");
        }
    }

    private boolean match(String value) {
        if (!source.regionMatches(offset, value, 0, value.length())) return false;
        offset += value.length();
        return true;
    }

    private void skipWhitespace() {
        while (offset < source.length()) {
            char value = source.charAt(offset);
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t') return;
            offset++;
        }
    }

    private boolean consume(char value) {
        if (offset < source.length() && source.charAt(offset) == value) {
            offset++;
            return true;
        }
        return false;
    }

    private void require(char value) throws IOException {
        if (!consume(value)) throw error("Expected '" + value + "'");
    }

    private IOException error(String message) {
        return new IOException(message + " at offset " + offset);
    }
}
