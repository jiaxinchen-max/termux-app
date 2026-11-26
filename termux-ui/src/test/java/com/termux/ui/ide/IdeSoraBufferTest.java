package com.termux.ui.ide;

import io.github.rosemoe.sora.text.Content;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real editor buffer, not a duplicate of its undo implementation. */
public class IdeSoraBufferTest {
    @Test public void buffersKeepIndependentUndoHistoryWhenSwitchingDocuments() {
        IdeEditorSession.Document first = new IdeEditorSession.Document("class A {}", "class A {}");
        IdeEditorSession.Document second = new IdeEditorSession.Document("class B {}", "class B {}");
        first.buffer = new Content(first.text); second.buffer = new Content(second.text);
        first.buffer.insert(0, 9, " int a; ");
        second.buffer.insert(0, 9, " int b; ");
        first.buffer.undo();
        assertEquals("class A {}", first.buffer.toString());
        assertEquals("class B { int b; }", second.buffer.toString());
        first.buffer.redo();
        assertEquals("class A { int a; }", first.buffer.toString());
    }
    @Test public void utf16PositionsRoundTripWithEmojiAndMixedLineEndings() {
        Content text = new Content("// \uD83D\uDE00\r\nclass A {}\n");
        int offset = text.toString().indexOf("A");
        io.github.rosemoe.sora.text.CharPosition position = text.getIndexer().getCharPosition(offset);
        assertEquals(1, position.line); assertEquals(6, position.column);
        assertEquals(offset, text.getIndexer().getCharIndex(position.line, position.column));
        assertEquals("// \uD83D\uDE00\r\nclass A {}\n", text.toString());
    }
    @Test public void pasteLimitDoesNotSplitEmojiAndReplacementUsesFreedSpace() {
        char[] chars = new char[IdeEditorBuffer.MAX_LENGTH - 1];
        java.util.Arrays.fill(chars, 'a');
        IdeEditorBuffer buffer = new IdeEditorBuffer(new String(chars));
        buffer.insert(0, buffer.length(), "\uD83D\uDE00");
        assertEquals(chars.length, buffer.length());
        buffer.replace(0, 2, "\uD83D\uDE00");
        assertEquals(chars.length, buffer.length());
        assertTrue(buffer.toString().startsWith("\uD83D\uDE00"));
        buffer.undo();
        assertEquals(new String(chars), buffer.toString());
        buffer.insert(0, buffer.length(), "end");
        assertEquals(IdeEditorBuffer.MAX_LENGTH, buffer.length());
        assertTrue(buffer.toString().endsWith("e"));
    }
    @Test public void discardingDraftDropsTheUndoBufferThatContainedDiscardedText() {
        IdeEditorSession session = new IdeEditorSession();
        IdeEditorSession.Document doc = new IdeEditorSession.Document("edited", "original");
        doc.buffer = new Content(doc.text);
        session.documents.put("A.java", doc);
        session.discard();
        assertNull(doc.buffer); assertEquals("original", doc.text); assertFalse(doc.dirty());
    }
}
