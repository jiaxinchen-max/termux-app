package com.termux.localgames.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GameModelTest {

    @Test
    public void gameDefensivelyCopiesArguments() {
        List<String> arguments = new ArrayList<>(Arrays.asList("--fullscreen"));
        Game game = new Game("game-1", "Sample", "content://games/sample",
            "Sample.exe", ".", arguments, "", 0);

        arguments.add("--mutated");

        assertEquals(Arrays.asList("--fullscreen"), game.getArguments());
        try {
            game.getArguments().add("--blocked");
            fail("arguments must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void runtimeProfileDefensivelyCopiesMaps() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("DXVK_ASYNC", "1");
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("wine", "9");
        RuntimeProfile profile = new RuntimeProfile("profile-1", "wine-9",
            "turnip", "dxvk", "pulseaudio", "1280x720", "stable",
            environment, "", versions);

        environment.put("MUTATED", "1");

        assertEquals(1, profile.getEnvironment().size());
        assertEquals("9", profile.getComponentVersions().get("wine"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void gameRejectsBlankExecutable() {
        new Game("game-1", "Sample", "content://games/sample", " ", ".",
            new ArrayList<>(), "", 0);
    }
}
