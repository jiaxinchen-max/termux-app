package com.termux.ui.ide;

import java.util.*;
import java.util.regex.*;

/** Bounded lexical scan over an immutable snapshot, never an Android Editable. */
public final class IdeSyntaxTokens {
    private static final int LIMIT = 2000;
    private static final Pattern JAVA = Pattern.compile("\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null)\\b");
    private static final Pattern XML = Pattern.compile("</?[A-Za-z_:][\\w:.-]*|[A-Za-z_:][\\w:.-]*");
    public static final class Token {
        public final int start, end;
        public final boolean literal;
        Token(int start, int end, boolean literal) { this.start = start; this.end = end; this.literal = literal; }
    }
    public static List<Token> scan(String text, boolean xml) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = (xml ? XML : JAVA).matcher(text);
        while (tokens.size() < LIMIT && matcher.find()) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            if (xml && text.charAt(matcher.start()) != '<') {
                int next = matcher.end();
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
                if (next == text.length() || text.charAt(next) != '=') continue;
            }
            tokens.add(new Token(matcher.start(), matcher.end(), false));
        }
        // Avoid recursive regex backtracking on long quoted strings or incomplete comments.
        for (int i = 0; i < text.length() && tokens.size() < LIMIT; ) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            int start = i; char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i++;
                while (i < text.length()) {
                    char next = text.charAt(i++);
                    if (next == '\\' && i < text.length()) i++;
                    else if (next == c) break;
                }
            } else if (!xml && text.startsWith("//", i)) {
                int end = text.indexOf('\n', i + 2); i = end < 0 ? text.length() : end;
            } else if (!xml && text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2); i = end < 0 ? text.length() : end + 2;
            } else if (xml && text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4); i = end < 0 ? text.length() : end + 3;
            } else { i++; continue; }
            tokens.add(new Token(start, i, true));
        }
        return tokens;
    }
    private IdeSyntaxTokens() { }
}
