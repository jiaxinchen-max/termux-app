package com.termux.ui.wine.importer;

import com.termux.ui.wine.data.FileGameContainerRepository;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.runtime.GameContainerProfileResolver;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static org.junit.Assert.*;

public class QuickGameImportTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private FileGameRepository games;
    private FileRuntimeProfileRepository profiles;
    private FileGameContainerRepository containers;

    @Before public void setUp() throws Exception {
        games = new FileGameRepository(temporary.newFolder("library"));
        profiles = new FileRuntimeProfileRepository(temporary.newFolder("profiles"));
        containers = new FileGameContainerRepository(temporary.newFolder("containers"));
    }

    private String create(GameRuntimeBackendType backend) throws Exception {
        return QuickGameImport.createConfiguration(games, profiles, containers,
            "file:///storage/emulated/0/Game", "wfm.exe", backend);
    }

    @Test public void nativeThenProotPreservesTheEntireOriginalConfiguration() throws Exception {
        String nativeId = create(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        Game original = games.find(nativeId).get().withLaunchDetails("Native custom", ".",
            Collections.singletonList("--native-only")).withLastPlayedAt(123L);
        games.save(original);
        RuntimeProfile nativeProfile = profiles.find(nativeId).get().withInputProfileId("dinput:1");
        profiles.save(nativeProfile);

        String prootId = create(GameRuntimeBackendType.ROOTFS_PROOT);

        assertNotEquals(nativeId, prootId);
        assertEquals(2, games.list().size());
        assertEquals(original.getRootUri(), games.find(prootId).get().getRootUri());
        assertEquals(original.getExecutable(), games.find(prootId).get().getExecutable());
        assertEquals("Native custom", games.find(nativeId).get().getName());
        assertEquals(original.getArguments(), games.find(nativeId).get().getArguments());
        assertEquals(123L, games.find(nativeId).get().getLastPlayedAt());
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            profiles.find(nativeId).get().getRuntimeBackendType());
        assertEquals("dinput:1", profiles.find(nativeId).get().getInputProfileId());
        RuntimeProfile proot = profiles.find(prootId).get();
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, proot.getRuntimeBackendType());
        assertNotEquals(GameContainer.DEFAULT_ID, proot.getContainerId());
        GameContainer container = containers.find(proot.getContainerId()).get();
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT,
            new GameContainerProfileResolver().resolve(proot, container).getRuntimeBackendType());
    }

    @Test public void repeatedNativeImportsAreSeparateConfigurationsToo() throws Exception {
        String first = create(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        String second = create(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        assertNotEquals(first, second);
        assertEquals(2, games.list().size());
        assertEquals(first, profiles.find(first).get().getId());
        assertEquals(second, profiles.find(second).get().getId());
    }

    @Test public void repeatedProotImportsOwnSeparateContainers() throws Exception {
        String first = create(GameRuntimeBackendType.ROOTFS_PROOT);
        String second = create(GameRuntimeBackendType.ROOTFS_PROOT);
        assertNotEquals(first, second);
        assertNotEquals(profiles.find(first).get().getContainerId(),
            profiles.find(second).get().getContainerId());
        assertEquals(2, containers.list().size());
    }

    @Test public void modeMustBeExplicitBeforeAnyRecordIsWritten() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> create(null));
        assertTrue(games.list().isEmpty());
        assertTrue(profiles.list().isEmpty());
        assertTrue(containers.list().isEmpty());
    }
}
