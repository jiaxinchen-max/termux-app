package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GameLogLineTest {

    private static GameLogLine.Level level(String text) {
        return GameLogLine.classify(text);
    }

    @Test
    public void shellTraceLinesAreTraceEvenWhenTheyMentionErrors() {
        assertEquals(GameLogLine.Level.TRACE, level("+ kill -0 29961"));
        assertEquals(GameLogLine.Level.TRACE, level("++ date +%s"));
        assertEquals(GameLogLine.Level.TRACE, level("+ echo 'fatal error: disk failed'"));
        assertEquals(GameLogLine.Level.TRACE, level("+"));
    }

    @Test
    public void wineChannelsMapToLevels() {
        assertEquals(GameLogLine.Level.ERROR, level("0024:err:module:import_dll Library X not found"));
        assertEquals(GameLogLine.Level.ERROR, level("err:ole:CoInitializeEx failed"));
        assertEquals(GameLogLine.Level.WARN, level("00f0:warn:heap:something odd"));
        assertEquals(GameLogLine.Level.WARN, level("0100:fixme:d3d:wined3d_swapchain stub"));
        assertEquals(GameLogLine.Level.TRACE, level("0024:trace:file:CreateFileW"));
    }

    @Test
    public void logcatBriefAndThreadtimeLettersMapToLevels() {
        assertEquals(GameLogLine.Level.ERROR, level("E/AndroidRuntime(1234): FATAL EXCEPTION"));
        assertEquals(GameLogLine.Level.WARN, level("W/Tag: slow"));
        assertEquals(GameLogLine.Level.INFO, level("I/Tag: started"));
        assertEquals(GameLogLine.Level.DEBUG, level("D/Tag: value=3"));
        assertEquals(GameLogLine.Level.TRACE, level("V/Tag: noisy"));
        assertEquals(GameLogLine.Level.ERROR,
            level("10-21 16:23:22.304   601   631 E ActivityManager: crash"));
        assertEquals(GameLogLine.Level.DEBUG,
            level("10-21 16:23:22.304   601   631 D CoreBackPreview: callback"));
    }

    @Test
    public void leadingLevelWordsAreRecognizedCaseInsensitively() {
        assertEquals(GameLogLine.Level.ERROR, level("[ERROR] launch failed"));
        assertEquals(GameLogLine.Level.ERROR, level("FATAL: out of memory"));
        assertEquals(GameLogLine.Level.WARN, level("warning: deprecated option"));
        assertEquals(GameLogLine.Level.WARN, level("WARN something"));
        assertEquals(GameLogLine.Level.INFO, level("INFO: ready"));
        assertEquals(GameLogLine.Level.DEBUG, level("debug: x"));
        assertEquals(GameLogLine.Level.TRACE, level("TRACE enter"));
    }

    @Test
    public void levelWordsInsideOtherWordsDoNotCount() {
        assertEquals(GameLogLine.Level.INFO, level("Information about the prefix"));
        assertEquals(GameLogLine.Level.INFO, level("terrorist"));
    }

    @Test
    public void errorAndWarningWordsAnywhereInAPlainLineAreDetected() {
        assertEquals(GameLogLine.Level.ERROR, level("process exited: segmentation fault"));
        assertEquals(GameLogLine.Level.ERROR, level("component_prepare failed:turnip"));
        assertEquals(GameLogLine.Level.ERROR, level("Unhandled Exception in thread"));
        assertEquals(GameLogLine.Level.WARN, level("this call is deprecated"));
    }

    @Test
    public void plainLinesAreInfo() {
        assertEquals(GameLogLine.Level.INFO, level("starting wine"));
        assertEquals(GameLogLine.Level.INFO, level(""));
    }

    @Test
    public void constructorKeepsNumberTextAndDetectedLevel() {
        GameLogLine line = new GameLogLine(42, "0024:err:x:y");
        assertEquals(42, line.number);
        assertEquals("0024:err:x:y", line.text);
        assertEquals(GameLogLine.Level.ERROR, line.level);
        assertEquals('E', line.level.badge);
    }
}
