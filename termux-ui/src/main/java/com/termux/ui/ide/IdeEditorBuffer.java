package com.termux.ui.ide;

import io.github.rosemoe.sora.text.Content;

/** Retains the host's editor size limit for typing, paste, snippets and replacements. */
public final class IdeEditorBuffer extends Content {
    public static final int MAX_LENGTH = 128 * 1024;

    public IdeEditorBuffer(CharSequence text) { super(text); }

    @Override public void insert(int line, int column, CharSequence text) {
        super.insert(line, column, fit(text, MAX_LENGTH - length()));
    }
    @Override public void replace(int startLine, int startColumn, int endLine, int endColumn,
                                  CharSequence text) {
        int removed = getIndexer().getCharIndex(endLine, endColumn)
            - getIndexer().getCharIndex(startLine, startColumn);
        super.replace(startLine, startColumn, endLine, endColumn,
            fit(text, MAX_LENGTH - length() + removed));
    }
    private static CharSequence fit(CharSequence text, int capacity) {
        if (text == null || text.length() <= capacity) return text;
        int end = Math.max(0, capacity);
        // Never leave half a surrogate pair at the truncation boundary.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.subSequence(0, end);
    }
}
