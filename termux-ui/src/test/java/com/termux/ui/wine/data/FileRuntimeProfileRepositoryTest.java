package com.termux.ui.wine.data;

import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileRuntimeProfileRepositoryTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void independentInputApisSurviveStorageAndProfileCopies() throws Exception {
        FileRuntimeProfileRepository repository = repository();
        for (boolean xinput : new boolean[] {false, true}) {
            for (boolean dinput : new boolean[] {false, true}) {
                RuntimeProfile selected = RuntimeProfilePresets.create("wine-input",
                    RuntimeProfilePreset.RECOMMENDED).withInputApis(xinput, dinput)
                    .withInputProfileId("dinput:3").withContainerId("default")
                    .withLaunchExecutionMode(LaunchExecutionMode.APP_SHELL)
                    .withRuntimeBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "");
                repository.save(selected);
                repository.saveLastSuccessful(selected);
                for (RuntimeProfile restored : new RuntimeProfile[] {
                        repository.find("wine-input").get(), repository.findLastSuccessful("wine-input").get()}) {
                    assertEquals(xinput, restored.isXinputEnabled());
                    assertEquals(dinput, restored.isDinputEnabled());
                    assertEquals("dinput:3", restored.getInputProfileId());
                }
            }
        }
    }

    @Test
    public void persistsCurrentAndIndependentLastSuccess() throws Exception {
        FileRuntimeProfileRepository repository = repository();
        RuntimeProfile current = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED)
            .withLaunchExecutionMode(LaunchExecutionMode.TERMINAL_SESSION);
        RuntimeProfile successful = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.COMPATIBILITY);

        repository.save(current);
        repository.saveLastSuccessful(successful);

        assertEquals("glibc_wine_9.3-vanilla",
            repository.find("game-1").get().getWinePackage());
        assertEquals(LaunchExecutionMode.TERMINAL_SESSION,
            repository.find("game-1").get().getLaunchExecutionMode());
        assertEquals("glibc_wine_8.18-staging",
            repository.findLastSuccessful("game-1").get().getWinePackage());
        assertEquals(1, repository.list().size());

        repository.delete("game-1");
        assertFalse(repository.find("game-1").isPresent());
        assertFalse(repository.findLastSuccessful("game-1").isPresent());
    }

    @Test(expected = IOException.class)
    public void rejectsUnknownFieldsWithoutOverwritingRecord() throws Exception {
        File directory = temporaryFolder.newFolder("profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        RuntimeProfile profile = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        repository.save(profile);
        File file = new File(directory, "game-1.properties");
        Properties properties = new Properties();
        properties.load(new java.io.FileInputStream(file));
        properties.setProperty("unexpected", "value");
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }

        repository.find("game-1");
    }

    @Test
    public void missingProfileDoesNotCreateDefaultFile() throws Exception {
        File directory = temporaryFolder.newFolder("empty-profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);

        assertFalse(repository.find("game-1").isPresent());
        assertEquals(0, directory.listFiles().length);
    }

    @Test
    public void persistsRootfsBackendWithoutSharingGlibcSemantics() throws Exception {
        FileRuntimeProfileRepository repository = repository();
        RuntimeProfile base = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile rootfs = new RuntimeProfile(base.getId(), "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs-wined3d", base.getAudioDriver(), base.getResolution(),
            base.getBox64Preset(), base.getEnvironment(), base.getInputProfileId(),
            base.getLaunchExecutionMode(), base.getComponentVersions(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        repository.save(rootfs);

        RuntimeProfile restored = repository.find("game-1").get();
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, restored.getRuntimeBackendType());
        assertEquals("debian-13-games-rootfs", restored.getRootfsPackage());
    }

    @Test public void eachBackendPersistsItsExactBox64038Selection() throws Exception {
        FileRuntimeProfileRepository repository = repository();
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            boolean proot = backend == GameRuntimeBackendType.ROOTFS_PROOT;
            String id = (proot ? "rootfs" : "glibc") + "_box64_0.3.8";
            RuntimeProfile base = RuntimeProfilePresets.create("game-" + backend.name(), RuntimeProfilePreset.RECOMMENDED);
            RuntimeProfile profile = new RuntimeProfile(base.getId(), proot ? "rootfs_wine_10.0" : base.getWinePackage(),
                proot ? "rootfs-llvmpipe" : base.getGraphicsDriver(), proot ? "rootfs-wined3d" : base.getDxWrapper(),
                base.getAudioDriver(), base.getResolution(), base.getBox64Preset(),
                java.util.Collections.singletonMap("GAMES_CUSTOM_BOX64_ID", id), base.getInputProfileId(),
                base.getLaunchExecutionMode(), base.getComponentVersions(), backend,
                proot ? "debian-13-games-rootfs" : "");
            repository.save(profile);
            assertEquals(id, repository.find(profile.getId()).get().getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
        }
    }

    private FileRuntimeProfileRepository repository() throws IOException {
        return new FileRuntimeProfileRepository(temporaryFolder.newFolder("profiles"));
    }
}
