package com.termux.ui.wine.activity;

import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.LaunchSpec;
import com.termux.ui.wine.runtime.GlibcTermuxBoxBackend;
import com.termux.ui.wine.runtime.LaunchSpecFactory;
import com.termux.ui.data.GameStoragePaths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class GameControllerSettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void independentEditsReadFreshDataAndPreservePendingMapperEvenWhenOff() throws Exception {
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(temporary.newFolder());
        RuntimeProfile original = RuntimeProfilePresets.create("wine-test", RuntimeProfilePreset.RECOMMENDED)
            .withInputProfileId("xinput:3");
        repository.save(original);
        GameControllerSettings settings = new GameControllerSettings(repository, original.getId());
        settings.setMapper("dinput");
        settings.setInputApi(true, false);
        assertEquals("dinput:9", settings.setTouchProfile(9).getInputProfileId());
        assertEquals("dinput", settings.setTouchProfile(0).getInputProfileId());
        assertFalse(settings.read().isXinputEnabled());
        assertTrue(settings.read().isDinputEnabled());
        settings.setInputApi(false, false);
        settings.setInputApi(true, true);
        RuntimeProfile saved = new GameControllerSettings(repository, original.getId()).read();
        assertTrue(saved.isXinputEnabled());
        assertFalse(saved.isDinputEnabled());
        assertEquals("dinput", saved.getInputProfileId());
        assertEquals(original.getEnvironment(), saved.getEnvironment());
        assertEquals(original.getWinePackage(), saved.getWinePackage());
        assertEquals(original.getComponentVersions(), saved.getComponentVersions());
        File gameRoot = temporary.newFolder();
        assertTrue(new File(gameRoot, "game.exe").createNewFile());
        Game game = new Game(saved.getId(), "Test", gameRoot.toURI().toString(), "game.exe", ".",
            java.util.Collections.emptyList(), "", 0);
        LaunchSpec nextLaunch = new LaunchSpecFactory(new GameStoragePaths(temporary.newFolder()))
            .create("launch-next", game, saved, gameRoot.getAbsolutePath(), new GlibcTermuxBoxBackend(), "");
        assertEquals("dinput", nextLaunch.getInputProfileId());
        assertTrue(nextLaunch.isXinputEnabled());
        assertFalse(nextLaunch.isDinputEnabled());
    }

    @Test public void missingOrInvalidProfileIsNeverReplaced() throws Exception {
        File directory = temporary.newFolder();
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        GameControllerSettings settings = new GameControllerSettings(repository, "wine-test");
        assertThrows(IOException.class, () -> settings.setInputApi(false, true));
        assertEquals(0, directory.list().length);
        RuntimeProfile valid = RuntimeProfilePresets.create("wine-test", RuntimeProfilePreset.RECOMMENDED);
        repository.save(valid);
        File source = new File(directory, "wine-test.properties");
        String invalid = new String(Files.readAllBytes(source.toPath()), java.nio.charset.StandardCharsets.UTF_8)
            .replaceAll("(?m)^xinputEnabled=.*\\R?", "");
        Files.write(source.toPath(), invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(source.toPath());
        assertThrows(Exception.class, () -> settings.setMapper("dinput"));
        assertArrayEquals(before, Files.readAllBytes(source.toPath()));
    }

    @Test public void failedWriteLeavesPreviousSelectionIntact() throws Exception {
        File directory = temporary.newFolder();
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        RuntimeProfile original = RuntimeProfilePresets.create("wine-test", RuntimeProfilePreset.RECOMMENDED);
        repository.save(original);
        File blocked = new File(directory, "wine-test.properties.tmp");
        assertTrue(blocked.mkdir());
        assertTrue(new File(blocked, "keep").createNewFile());
        GameControllerSettings settings = new GameControllerSettings(repository, original.getId());
        assertThrows(IOException.class, () -> settings.setInputApi(true, false));
        assertThrows(IOException.class, () -> settings.setMapper("dinput"));
        assertEquals(original.getInputProfileId(), settings.read().getInputProfileId());
        assertEquals(original.isXinputEnabled(), settings.read().isXinputEnabled());
    }
}
