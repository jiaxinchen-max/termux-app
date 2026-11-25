package com.termux.ui.ide;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdeEditorSessionTest {
    @Test public void discardingAllDraftsLeavesOriginalBuffersAndCursorsIndependent() {
        IdeEditorSession session = new IdeEditorSession();
        IdeEditorSession.Document java = new IdeEditorSession.Document("new Java", "old Java");
        IdeEditorSession.Document xml = new IdeEditorSession.Document("new XML", "old XML");
        java.cursor = 3; xml.cursor = 6;
        session.documents.put("A.java", java); session.documents.put("screen.xml", xml);
        session.active = "screen.xml";
        assertTrue(session.dirty());
        session.discard();
        assertFalse(session.dirty());
        assertEquals("old Java", java.text); assertEquals("old XML", xml.text);
        assertEquals(3, java.cursor); assertEquals(6, xml.cursor);
    }
    @Test public void savingOneDocumentDoesNotClearAnotherDocumentsUnsavedWarning() {
        IdeEditorSession session = new IdeEditorSession();
        IdeEditorSession.Document first = new IdeEditorSession.Document("changed", "original");
        IdeEditorSession.Document second = new IdeEditorSession.Document("changed too", "other");
        session.documents.put("A.java", first); session.documents.put("B.java", second);
        first.saved = first.text;
        assertTrue(session.dirty()); assertTrue(second.dirty()); assertFalse(first.dirty());
        session.documents.remove("B.java"); assertFalse(session.dirty());
    }
}
