package com.termux.localgames.data;

import java.io.ByteArrayOutputStream;

/** Small strict RFC 4648 codec kept Android API 21 and plain JVM compatible. */
final class Base64Text {

    private static final char[] ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Text() {}

    static String encode(byte[] input) {
        StringBuilder output = new StringBuilder(((input.length + 2) / 3) * 4);
        for (int offset = 0; offset < input.length; offset += 3) {
            int remaining = input.length - offset;
            int value = (input[offset] & 0xff) << 16;
            if (remaining > 1) value |= (input[offset + 1] & 0xff) << 8;
            if (remaining > 2) value |= input[offset + 2] & 0xff;
            output.append(ALPHABET[(value >>> 18) & 63]);
            output.append(ALPHABET[(value >>> 12) & 63]);
            output.append(remaining > 1 ? ALPHABET[(value >>> 6) & 63] : '=');
            output.append(remaining > 2 ? ALPHABET[value & 63] : '=');
        }
        return output.toString();
    }

    static byte[] decode(String input) {
        if (input == null || (input.length() & 3) != 0) {
            throw new IllegalArgumentException("invalid base64 length");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length() * 3 / 4);
        for (int offset = 0; offset < input.length(); offset += 4) {
            boolean last = offset + 4 == input.length();
            int a = value(input.charAt(offset));
            int b = value(input.charAt(offset + 1));
            char third = input.charAt(offset + 2);
            char fourth = input.charAt(offset + 3);
            if (a < 0 || b < 0 || (!last && (third == '=' || fourth == '=')) ||
                (third == '=' && fourth != '=')) {
                throw new IllegalArgumentException("invalid base64 padding");
            }
            int c = third == '=' ? 0 : value(third);
            int d = fourth == '=' ? 0 : value(fourth);
            if (c < 0 || d < 0) throw new IllegalArgumentException("invalid base64 character");
            if ((third == '=' && (b & 0x0f) != 0) ||
                (fourth == '=' && third != '=' && (c & 0x03) != 0)) {
                throw new IllegalArgumentException("non-canonical base64 padding");
            }
            int decoded = (a << 18) | (b << 12) | (c << 6) | d;
            output.write((decoded >>> 16) & 0xff);
            if (third != '=') output.write((decoded >>> 8) & 0xff);
            if (fourth != '=') output.write(decoded & 0xff);
        }
        return output.toByteArray();
    }

    private static int value(char input) {
        if (input >= 'A' && input <= 'Z') return input - 'A';
        if (input >= 'a' && input <= 'z') return input - 'a' + 26;
        if (input >= '0' && input <= '9') return input - '0' + 52;
        if (input == '+') return 62;
        if (input == '/') return 63;
        return -1;
    }
}
