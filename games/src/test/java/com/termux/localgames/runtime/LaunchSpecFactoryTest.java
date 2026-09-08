package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.LaunchSpec;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

public class LaunchSpecFactoryTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void freezesCanonicalContainedPaths() throws Exception {
        File files = temporary.newFolder("private files");
        File root = temporary.newFolder("Game $ Root");
        File work = new File(root, "bin folder");
        if (!work.mkdir()) throw new IOException("fixture mkdir failed");
        File executable = new File(work, "it's game.exe");
        if (!executable.createNewFile()) throw new IOException("fixture executable failed");
        Game game = new Game("game-1", "Game", "content://tree", "bin folder/it's game.exe",
            "bin folder", Arrays.asList("--hello world"), "", 0);
        RuntimeProfile profile = RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED);

        LaunchSpec spec = new LaunchSpecFactory(new GameStoragePaths(files))
            .create("task-1", game, profile, root.getPath());

        assertEquals(root.getCanonicalPath(), spec.getGameRootPath());
        assertEquals("bin folder/it's game.exe", spec.getExecutable());
        assertEquals("game-1", new File(spec.getPrefixPath()).getName());
    }
}
