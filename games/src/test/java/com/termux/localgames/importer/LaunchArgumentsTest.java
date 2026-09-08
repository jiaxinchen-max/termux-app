package com.termux.localgames.importer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LaunchArgumentsTest {

    @Test
    public void separatesValuesAndPreservesQuotedSpacesAndEmptyArguments() {
        assertEquals(Arrays.asList("-windowed", "player one", "", "a b",
                "C:\\Games\\game", "C:\\Program Files\\Game"),
            LaunchArguments.parse("-windowed \"player one\" '' a\\ b " +
                "C:\\Games\\game \"C:\\Program Files\\Game\""));
        assertEquals(Collections.emptyList(), LaunchArguments.parse("  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnterminatedQuote() {
        LaunchArguments.parse("--name \"unfinished");
    }

    @Test
    public void formattedArgumentsRoundTripQuotesEmptyValuesAndWindowsPaths() {
        java.util.List<String> arguments = Arrays.asList("", "hello world",
            "C:\\Games\\Sample", "say\"hello");
        assertEquals(arguments, LaunchArguments.parse(LaunchArguments.format(arguments)));
    }
}
