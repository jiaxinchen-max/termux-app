package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class GameLogTailReaderTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File file(String content) throws IOException {
        File file = folder.newFile();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String texts(GameLogTailReader.Result result) {
        StringBuilder out = new StringBuilder();
        for (GameLogLine line : result.lines) out.append(line.number).append(':').append(line.text).append('|');
        return out.toString();
    }

    @Test
    public void emptyFileHasNoLines() throws IOException {
        GameLogTailReader.Result result = GameLogTailReader.read(file(""), 10);
        assertTrue(result.lines.isEmpty());
        assertEquals(0, result.totalLines);
        assertFalse(result.isTruncated());
    }

    @Test
    public void numbersLinesFromOneAndIgnoresTheTrailingNewline() throws IOException {
        GameLogTailReader.Result result = GameLogTailReader.read(file("a\nb\nc\n"), 10);
        assertEquals("1:a|2:b|3:c|", texts(result));
        assertEquals(3, result.totalLines);
    }

    @Test
    public void lastLineWithoutNewlineIsKept() throws IOException {
        assertEquals("1:a|2:b|", texts(GameLogTailReader.read(file("a\nb"), 10)));
    }

    @Test
    public void blankLinesAreLines() throws IOException {
        assertEquals("1:a|2:|3:b|", texts(GameLogTailReader.read(file("a\n\nb"), 10)));
        assertEquals("1:a|2:|", texts(GameLogTailReader.read(file("a\n\n"), 10)));
    }

    @Test
    public void crlfIsStripped() throws IOException {
        assertEquals("1:a|2:b|", texts(GameLogTailReader.read(file("a\r\nb\r\n"), 10)));
    }

    @Test
    public void onlyTheLastLinesAreReturnedWithAbsoluteNumbers() throws IOException {
        GameLogTailReader.Result result = GameLogTailReader.read(file("l1\nl2\nl3\nl4\nl5\n"), 2);
        assertEquals("4:l4|5:l5|", texts(result));
        assertEquals(5, result.totalLines);
        assertTrue(result.isTruncated());
    }

    @Test
    public void windowExactlyFittingTheFileIsNotTruncated() throws IOException {
        GameLogTailReader.Result result = GameLogTailReader.read(file("l1\nl2\nl3\n"), 3);
        assertEquals("1:l1|2:l2|3:l3|", texts(result));
        assertFalse(result.isTruncated());
    }

    @Test
    public void windowKeepsTheRightStartWhenTheFileEndsWithoutNewline() throws IOException {
        assertEquals("3:l3|4:l4|", texts(GameLogTailReader.read(file("l1\nl2\nl3\nl4"), 2)));
    }

    @Test
    public void singleLineWindow() throws IOException {
        assertEquals("3:c|", texts(GameLogTailReader.read(file("a\nb\nc\n"), 1)));
    }

    @Test
    public void overLongLinesAreCutAndMarked() throws IOException {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < GameLogTailReader.MAX_LINE_BYTES * 3; i++) longLine.append('x');
        GameLogTailReader.Result result = GameLogTailReader.read(file(longLine + "\nnext\n"), 10);
        assertEquals(2, result.lines.size());
        String first = result.lines.get(0).text;
        assertEquals(GameLogTailReader.MAX_LINE_BYTES + 1, first.length());
        assertTrue(first.endsWith(GameLogTailReader.ELLIPSIS));
        assertEquals("next", result.lines.get(1).text);
        assertEquals(2, result.lines.get(1).number);
    }

    @Test
    public void multiByteTextSurvives() throws IOException {
        assertEquals("1:键盘|2:ok|", texts(GameLogTailReader.read(file("键盘\nok\n"), 10)));
    }

    @Test
    public void linesAreClassified() throws IOException {
        GameLogTailReader.Result result = GameLogTailReader.read(file("+ sleep 1\n0024:err:x:y\nplain\n"), 10);
        assertEquals(GameLogLine.Level.TRACE, result.lines.get(0).level);
        assertEquals(GameLogLine.Level.ERROR, result.lines.get(1).level);
        assertEquals(GameLogLine.Level.INFO, result.lines.get(2).level);
    }

    @Test(expected = FileNotFoundException.class)
    public void missingFileThrows() throws IOException {
        GameLogTailReader.read(new File(folder.getRoot(), "missing.log"), 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPositiveWindowIsRejected() throws IOException {
        GameLogTailReader.read(file("a\n"), 0);
    }

    @Test
    public void resultReportsTheByteOffsetWhereTheWindowStarts() throws IOException {
        File file = file("l1\nl2\nl3\nl4\n");
        assertEquals(6L, GameLogTailReader.read(file, 2).startOffset);
        assertEquals(0L, GameLogTailReader.read(file, 4).startOffset);
        assertEquals(0L, GameLogTailReader.read(file, 10).startOffset);
        assertEquals(0L, GameLogTailReader.read(file(""), 3).startOffset);
    }

    /** Reads the tail with {@code window} lines, then pages back {@code page} lines at a time. */
    private static String pagedBack(File file, int window, int page) throws IOException {
        GameLogTailReader.Result tail = GameLogTailReader.read(file, window);
        StringBuilder out = new StringBuilder(texts(tail));
        long offset = tail.startOffset;
        int number = tail.lines.isEmpty() ? 1 : tail.lines.get(0).number;
        while (offset > 0) {
            GameLogTailReader.Page earlier = GameLogTailReader.readBefore(file, offset, number, page);
            assertFalse("a page must make progress", earlier.lines.isEmpty());
            StringBuilder prefix = new StringBuilder();
            for (GameLogLine line : earlier.lines) prefix.append(line.number).append(':').append(line.text).append('|');
            out.insert(0, prefix);
            offset = earlier.startOffset;
            number = earlier.lines.get(0).number;
        }
        assertEquals("paging back must end at line 1", 1, number);
        return out.toString();
    }

    @Test
    public void pagingBackReproducesTheFullRead() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 25; i++) content.append("line-").append(i).append(i % 4 == 0 ? "\r\n" : "\n");
        File file = file(content.toString());
        String full = texts(GameLogTailReader.read(file, 100));
        assertEquals(full, pagedBack(file, 7, 4));   // 7 + 4+4+4+4+2
        assertEquals(full, pagedBack(file, 1, 1));   // one line at a time
        assertEquals(full, pagedBack(file, 3, 100)); // a page larger than what is left
        assertEquals(full, pagedBack(file, 24, 5));  // window one line short of the whole file
    }

    @Test
    public void pagingBackWithoutATrailingNewlineAndWithBlankLines() throws IOException {
        File file = file("a\n\nb\n\n\nc");
        assertEquals(texts(GameLogTailReader.read(file, 100)), pagedBack(file, 2, 2));
    }

    @Test
    public void pagingBackKeepsTheLineCutOfOverLongLines() throws IOException {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < GameLogTailReader.MAX_LINE_BYTES * 3; i++) longLine.append('x');
        File file = file("head\n" + longLine + "\nmid\n" + longLine + "\ntail\n");
        assertEquals(texts(GameLogTailReader.read(file, 100)), pagedBack(file, 1, 2));
    }

    @Test
    public void pagingBackAcrossBufferBoundaries() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 6000; i++) content.append("a log line with some padding to cross the read buffer ").append(i).append('\n');
        File file = file(content.toString());
        GameLogTailReader.Result tail = GameLogTailReader.read(file, 50);
        GameLogTailReader.Page page = GameLogTailReader.readBefore(file, tail.startOffset, tail.lines.get(0).number, 2000);
        assertEquals(2000, page.lines.size());
        assertEquals(6000 - 50 - 2000 + 1, page.lines.get(0).number);
        assertEquals("a log line with some padding to cross the read buffer " + (6000 - 50 - 2000 + 1),
            page.lines.get(0).text);
        assertEquals(6000 - 50, page.lines.get(page.lines.size() - 1).number);
        assertTrue(page.startOffset > 0L);
    }

    @Test
    public void readBeforeTheFileStartIsEmpty() throws IOException {
        GameLogTailReader.Page page = GameLogTailReader.readBefore(file("a\nb\n"), 0L, 1, 10);
        assertTrue(page.lines.isEmpty());
        assertEquals(0L, page.startOffset);
    }

    @Test(expected = IOException.class)
    public void readBeforeRejectsAnOffsetBeyondTheFile() throws IOException {
        GameLogTailReader.readBefore(file("a\nb\n"), 100L, 3, 10);
    }

    @Test(expected = IOException.class)
    public void readBeforeRejectsAnOffsetThatIsNotALineStart() throws IOException {
        GameLogTailReader.readBefore(file("abc\ndef\n"), 2L, 2, 10);
    }

    @Test(expected = IOException.class)
    public void readBeforeRejectsALineNumberThatDoesNotMatchTheFile() throws IOException {
        // Three lines precede offset 6, so the next line cannot be number 2.
        GameLogTailReader.readBefore(file("a\nb\nc\nd\n"), 6L, 2, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void readBeforeRejectsANonPositivePage() throws IOException {
        GameLogTailReader.readBefore(file("a\n"), 2L, 2, 0);
    }
}
