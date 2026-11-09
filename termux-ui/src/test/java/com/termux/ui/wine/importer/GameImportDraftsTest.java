package com.termux.ui.wine.importer;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GameImportDraftsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File files;
    private GameStoragePaths paths;
    private GameImportDrafts drafts;
    @Before public void setup() throws Exception {
        files = temporary.newFolder("files");
        paths = new GameStoragePaths(files);
        drafts = new GameImportDrafts(files);
    }
    private String stage(GameRuntimeBackendType backend) throws Exception {
        return QuickGameImport.createConfiguration(drafts.games(), drafts.profiles(), drafts.containers(),
            "file:///external/games", "game.exe", backend);
    }
    @Test public void importsStayOutOfLibraryAndRuntimeInventoryOnBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stage(backend);
            assertTrue(drafts.isDraft(id));
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).list().isEmpty());
            assertTrue(new FileRuntimeProfileRepository(paths.getProfilesDirectory()).list().isEmpty());
            assertTrue(new FileGameContainerRepository(paths.getContainersDirectory()).list().isEmpty());
            // Reopening the editor/process still finds a draft, never an installed game.
            GameImportDrafts reopened = new GameImportDrafts(files);
            assertTrue(reopened.editingGames(id).find(id).isPresent());
            assertEquals(backend, reopened.editingProfiles(id).find(id).get().getRuntimeBackendType());
        }
    }
    @Test public void discardRemovesOnlyDraftOwnedDataOnBothBackends() throws Exception {
        File shared = new File(paths.getNativePrefixDirectory("default", "glibc_wine_9.3-vanilla"), "system.reg");
        assertTrue(shared.getParentFile().mkdirs());
        Files.write(shared.toPath(), new byte[] {1});
        File source = temporary.newFile("game.exe");
        File cached = new File(paths.getCacheDirectory(), "runtime.tar");
        assertTrue(cached.getParentFile().mkdirs());
        Files.write(cached.toPath(), new byte[] {2});
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stage(backend);
            drafts.discard(id);
            assertFalse(drafts.isDraft(id));
            assertFalse(drafts.editingGames(id).find(id).isPresent());
            assertFalse(drafts.profiles().find(id).isPresent());
            assertTrue(drafts.containers().list().isEmpty());
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).list().isEmpty());
            assertTrue(shared.isFile()); assertTrue(cached.isFile()); assertTrue(source.isFile());
        }
    }
    @Test public void unchangedFirstSavePublishesExactlyOneGameAndSelectedProfile() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stage(backend);
            Game game = drafts.games().find(id).get();
            RuntimeProfile profile = drafts.profiles().find(id).get();
            drafts.commit(game, profile);
            assertFalse(drafts.isDraft(id));
            assertEquals(game.getExecutable(), new FileGameRepository(paths.getLibraryDirectory()).find(id).get().getExecutable());
            assertEquals(profile.getWinePackage(), new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).get().getWinePackage());
            assertFalse(drafts.games().find(id).isPresent());
            assertFalse(drafts.profiles().find(id).isPresent());
            if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
                assertTrue(new FileGameContainerRepository(paths.getContainersDirectory()).find(profile.getContainerId()).isPresent());
            }
            drafts.commit(game, profile);
            drafts.discard(id); // Discard after publication must not uninstall an existing game.
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
        }
        assertEquals(2, new FileGameRepository(paths.getLibraryDirectory()).list().size());
    }
    @Test public void failedPublicationLeavesNoCardOrRuntimeRecordsAndAllowsRetry() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stage(backend);
            Game game = drafts.games().find(id).get();
            RuntimeProfile profile = drafts.profiles().find(id).get();
            File obstruction = new File(paths.getLibraryDirectory(), id + ".tmp");
            assertTrue(obstruction.mkdirs());
            assertThrows(java.io.IOException.class, () -> drafts.commit(game, profile));
            assertTrue(drafts.isDraft(id));
            assertFalse(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
            assertFalse(new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).isPresent());
            if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
                assertFalse(new FileGameContainerRepository(paths.getContainersDirectory()).find(profile.getContainerId()).isPresent());
            }
            if (obstruction.exists()) assertTrue(obstruction.delete());
            drafts.commit(game, profile);
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
        }
    }
    @Test public void discardedDraftCannotBeResurrectedByLateSave() throws Exception {
        String id = stage(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        Game game = drafts.games().find(id).get();
        RuntimeProfile profile = drafts.profiles().find(id).get();
        drafts.discard(id);
        assertThrows(java.io.IOException.class, () -> drafts.commit(game, profile));
        assertTrue(new FileGameRepository(paths.getLibraryDirectory()).list().isEmpty());
    }
    @Test public void repeatedSameExeImportsRemainIndependentDrafts() throws Exception {
        String first = stage(GameRuntimeBackendType.ROOTFS_PROOT);
        String second = stage(GameRuntimeBackendType.ROOTFS_PROOT);
        assertNotEquals(first, second);
        drafts.discard(first);
        assertTrue(drafts.isDraft(second));
        assertTrue(drafts.editingProfiles(second).find(second).isPresent());
    }
}
