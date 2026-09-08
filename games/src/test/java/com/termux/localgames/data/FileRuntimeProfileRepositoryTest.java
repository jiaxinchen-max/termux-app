package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.GameRuntimeBackendType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class FileRuntimeProfileRepositoryTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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

        assertEquals("wine-9.3-vanilla-wow64",
            repository.find("game-1").get().getWinePackage());
        assertEquals(LaunchExecutionMode.TERMINAL_SESSION,
            repository.find("game-1").get().getLaunchExecutionMode());
        assertEquals("wine-8.18-staging-wow64",
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
    public void readsSchemaOneWithAppShellDefault() throws Exception {
        File directory = temporaryFolder.newFolder("legacy-profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        repository.save(RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED)
            .withLaunchExecutionMode(LaunchExecutionMode.TERMINAL_SESSION));
        File file = new File(directory, "game-1.properties");
        Properties properties = new Properties();
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            properties.load(input);
        }
        properties.setProperty("schemaVersion", "1");
        properties.remove("launchExecutionMode");
        properties.remove("runtimeBackendType");
        properties.remove("rootfsPackage");
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }

        assertEquals(LaunchExecutionMode.APP_SHELL,
            repository.find("game-1").get().getLaunchExecutionMode());
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            repository.find("game-1").get().getRuntimeBackendType());
    }

    @Test
    public void persistsRootfsBackendWithoutSharingGlibcSemantics() throws Exception {
        FileRuntimeProfileRepository repository = repository();
        RuntimeProfile base = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile rootfs = new RuntimeProfile(base.getId(), "hangover-11.9",
            "rootfs-virgl-mesa", "rootfs-wined3d", base.getAudioDriver(), base.getResolution(),
            base.getBox64Preset(), base.getEnvironment(), base.getInputProfileId(),
            base.getLaunchExecutionMode(), base.getComponentVersions(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        repository.save(rootfs);

        RuntimeProfile restored = repository.find("game-1").get();
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, restored.getRuntimeBackendType());
        assertEquals("debian-13-games-rootfs", restored.getRootfsPackage());
    }

    @Test
    public void readsSchemaTwoWithGlibcBackendDefault() throws Exception {
        File directory = temporaryFolder.newFolder("schema-two-profiles");
        FileRuntimeProfileRepository repository = new FileRuntimeProfileRepository(directory);
        repository.save(RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED));
        File file = new File(directory, "game-1.properties");
        Properties properties = new Properties();
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            properties.load(input);
        }
        properties.setProperty("schemaVersion", "2");
        properties.remove("runtimeBackendType");
        properties.remove("rootfsPackage");
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }

        RuntimeProfile restored = repository.find("game-1").get();
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            restored.getRuntimeBackendType());
        assertEquals("", restored.getRootfsPackage());
    }

    private FileRuntimeProfileRepository repository() throws IOException {
        return new FileRuntimeProfileRepository(temporaryFolder.newFolder("profiles"));
    }
}
