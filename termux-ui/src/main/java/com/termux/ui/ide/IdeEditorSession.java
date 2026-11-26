package com.termux.ui.ide;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** View-independent drafts: changing tabs never writes a file or loses another draft. */
public final class IdeEditorSession {
    public static final class Document {
        public String text, saved;
        public int cursor, scroll;
        // Content owns undo history, but no Activity/View reference after editor.release().
        public io.github.rosemoe.sora.text.Content buffer;
        public Document(String text, String saved) { this.text = text; this.saved = saved; }
        public boolean dirty() { return !text.equals(saved); }
    }
    public final LinkedHashMap<String, Document> documents = new LinkedHashMap<>();
    public final LinkedHashSet<String> expanded = new LinkedHashSet<>();
    public String active;
    public boolean treeVisible = true, logVisible;
    public boolean dirty() {
        for (Document document : documents.values()) if (document.dirty()) return true;
        return false;
    }
    public void discard() { for (Document document : documents.values()) { document.text = document.saved; document.buffer = null; } }
}
