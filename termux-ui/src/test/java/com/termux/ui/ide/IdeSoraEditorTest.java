package com.termux.ui.ide;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import io.github.rosemoe.sora.text.Content;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Runs editor lifecycle/undo against the Android API26 implementation. No APK installation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class IdeSoraEditorTest {
    @Test public void releasingAndReopeningDocumentPreservesUndoAndUtf16Selection() {
        Context context = RuntimeEnvironment.getApplication();
        IdeCodeEditor first = new IdeCodeEditor(context);
        first.setText("// \uD83D\uDE00\nclass A {}\n");
        Content buffer = first.getText();
        int offset = buffer.toString().indexOf("A");
        first.setSelection(offset);
        assertEquals(offset, first.getSelectionStart());
        buffer.insert(1, 9, " int field; ");
        assertTrue(first.canUndo());
        first.release();
        IdeCodeEditor reopened = new IdeCodeEditor(context);
        try {
            reopened.setText(buffer);
            assertSame(buffer, reopened.getText());
            assertTrue(reopened.canUndo());
            reopened.undo();
            assertEquals("// \uD83D\uDE00\nclass A {}\n", reopened.getText().toString());
            reopened.redo();
            assertTrue(reopened.getText().toString().contains("int field;"));
        } finally { reopened.release(); }
    }
    @Test public void scrollRestorationWaitsForViewSizeAndAsyncLayout() throws Exception {
        IdeCodeEditor editor = new IdeCodeEditor(RuntimeEnvironment.getApplication());
        try {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 200; i++) text.append("line ").append(i).append('\n');
            editor.setText(text);
            editor.restoreScroll(320);
            int width = android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY);
            int height = android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY);
            editor.measure(width, height); editor.layout(0, 0, 800, 400);
            for (int i = 0; i < 100; i++) {
                Thread.sleep(10);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                if (editor.isEditable() && editor.getOffsetY() == 320) break;
            }
            assertEquals(320, editor.getOffsetY());
        } finally { editor.release(); }
    }
    @Test public void keyboardConnectionDisablesFullscreenAndPersonalizedLearning() throws Exception {
        IdeCodeEditor editor = new IdeCodeEditor(RuntimeEnvironment.getApplication());
        try {
            // Sora measures lines on a worker and disables input until the UI callback runs.
            for (int i = 0; i < 100; i++) {
                Thread.sleep(10);
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                if (editor.isEditable()) break;
            }
            assertTrue("Sora layout did not finish", editor.isEditable());
            EditorInfo info = new EditorInfo();
            assertNotNull(editor.onCreateInputConnection(info));
            assertTrue((info.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
            assertTrue((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        } finally { editor.release(); }
    }
}
