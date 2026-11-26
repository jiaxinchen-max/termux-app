package com.termux.ui.ide;

import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import org.junit.Test;
import static org.junit.Assert.*;

public class IdeSoraStylesTest {
    @Test public void colorsMatchSoraLineNumbersForEverySupportedLineSeparator() {
        for (String newline : new String[] {"\n", "\r\n", "\r"}) {
            String text = "class A {" + newline + " int number;" + newline + "}" + newline;
            Styles styles = IdeSyntax.analyze(text, false);
            assertEquals(new Content(text).getLineCount(), styles.spans.getLineCount());
            for (int line = 0; line < styles.spans.getLineCount(); line++) {
                assertFalse(styles.spans.read().getSpansOnLine(line).isEmpty());
                assertEquals(0, styles.spans.read().getSpansOnLine(line).get(0).getColumn());
            }
        }
    }
    @Test public void keywordInsideStringKeepsLiteralColor() {
        Styles styles = IdeSyntax.analyze("\"class\"", false);
        assertEquals(EditorColorScheme.LITERAL,
            styles.spans.read().getSpansOnLine(0).get(0).getStyle());
    }
}
