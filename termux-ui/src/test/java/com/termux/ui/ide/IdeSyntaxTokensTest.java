package com.termux.ui.ide;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class IdeSyntaxTokensTest {
    @Test(timeout=2000) public void longUnterminatedLiteralIsBoundedAndDoesNotOverflowStack() {
        char[] content = new char[128 * 1024]; Arrays.fill(content, 'a'); content[0] = '"';
        List<IdeSyntaxTokens.Token> tokens = IdeSyntaxTokens.scan(new String(content), false);
        assertEquals(1, tokens.size()); assertTrue(tokens.get(0).literal);
        assertEquals(content.length, tokens.get(0).end);
    }
    @Test public void escapedQuoteStaysInsideLiteralAndCommentReachesNewline() {
        String source = "\"a\\\"b\" // comment\nclass Demo {}";
        List<IdeSyntaxTokens.Token> tokens = IdeSyntaxTokens.scan(source, false);
        assertTrue(tokens.stream().anyMatch(t -> t.literal && t.start == 0 && t.end == 6));
        assertTrue(tokens.stream().anyMatch(t -> t.literal && source.substring(t.start, t.end).equals("// comment")));
        assertTrue(tokens.stream().anyMatch(t -> !t.literal && source.substring(t.start, t.end).equals("class")));
    }
    @Test public void xmlCommentsAndAttributeValuesAreRecognized() {
        String source = "<!-- note --><TextView text=\"hello\" />";
        List<IdeSyntaxTokens.Token> tokens = IdeSyntaxTokens.scan(source, true);
        assertTrue(tokens.stream().anyMatch(t -> t.literal && source.substring(t.start, t.end).equals("<!-- note -->")));
        assertTrue(tokens.stream().anyMatch(t -> t.literal && source.substring(t.start, t.end).equals("\"hello\"")));
    }
    @Test(timeout=2000) public void longPlainXmlTextDoesNotBacktrack() {
        char[] content = new char[128 * 1024]; Arrays.fill(content, 'a');
        assertTrue(IdeSyntaxTokens.scan(new String(content), true).isEmpty());
    }
    @Test public void manyKeywordsHaveABoundedSpanCount() {
        StringBuilder source = new StringBuilder(); for (int i=0; i<20000; i++) source.append("class ");
        assertTrue(IdeSyntaxTokens.scan(source.toString(), false).size() <= 2000);
    }
}
