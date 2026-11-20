package com.termux.ui.wine.importer;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ProotContainerEntryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void createWithoutExeIsDraftUntilSavedAndCancellationRemovesIt() throws Exception {
        File files = temporary.newFolder();
        String id = QuickGameImport.createContainer(files, "My container");
        GameImportDrafts drafts = new GameImportDrafts(files);
        Game game = drafts.games().find(id).get();
        assertEquals("My container", game.getName());
        assertTrue(game.isWineFileBrowser());
        assertTrue(new File(java.net.URI.create(game.getRootUri())).isDirectory());
        assertFalse(new File(new File(java.net.URI.create(game.getRootUri())), game.getExecutable()).exists());
        assertTrue(drafts.isDraft(id));
        assertEquals(GameRuntimeBackendType.ROOTFS_PROOT, drafts.profiles().find(id).get().getRuntimeBackendType());
        assertFalse(new FileGameRepository(new GameStoragePaths(files).getLibraryDirectory()).find(id).isPresent());
        drafts.discard(id);
        assertFalse(drafts.games().find(id).isPresent());
        assertTrue(drafts.containers().list().isEmpty());
    }

    @Test public void browserLaunchDoesNotRequireHostExeButImportedLaunchDoes() throws Exception {
        File files = temporary.newFolder();
        String id = QuickGameImport.createContainer(files, "Browser");
        GameImportDrafts drafts = new GameImportDrafts(files);
        Game game = drafts.games().find(id).get();
        RuntimeProfile profile = drafts.profiles().find(id).get();
        com.termux.ui.wine.runtime.LaunchSpecFactory factory = new com.termux.ui.wine.runtime.LaunchSpecFactory(new GameStoragePaths(files));
        String root = new File(java.net.URI.create(game.getRootUri())).getPath();
        com.termux.ui.wine.runtime.RootfsProotBackend backend = new com.termux.ui.wine.runtime.RootfsProotBackend();
        assertEquals(Game.WINE_FILE_BROWSER, factory.create("task", game, profile, root, backend, files.getPath()).getExecutable());
        Game missing = new Game(id, "Missing", game.getRootUri(), "missing.exe", ".", java.util.Collections.emptyList(), "", 0);
        assertThrows(java.io.IOException.class, () -> factory.create("task", missing, profile, root, backend, files.getPath()));
        Game escaping = new Game(id, "Escape", game.getRootUri(), "../game.exe", ".", java.util.Collections.emptyList(), "", 0);
        assertThrows(java.io.IOException.class, () -> factory.create("task", escaping, profile, root, backend, files.getPath()));
    }

    @Test public void replacingEntryKeepsContainerProfileCreationTimeAndPrefix() throws Exception {
        File files = temporary.newFolder();
        String id = QuickGameImport.createContainer(files, "My container");
        GameImportDrafts drafts = new GameImportDrafts(files);
        Game before = drafts.games().find(id).get();
        RuntimeProfile profile = drafts.profiles().find(id).get();
        drafts.commit(before, profile);
        GameStoragePaths paths = new GameStoragePaths(files);
        File prefix = paths.getContainerPrefixDirectory(profile.getContainerId(), profile.getRuntimeBackendType(), profile.getWinePackage());
        assertTrue(prefix.mkdirs());
        File marker = new File(prefix, "user-save.dat");
        Files.write(marker.toPath(), new byte[]{1,2,3});
        File executable = temporary.newFile("game.exe");
        QuickGameImport.setEntry(files, id, executable);
        Game after = new FileGameRepository(paths.getLibraryDirectory()).find(id).get();
        RuntimeProfile afterProfile = new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).get();
        assertFalse(after.isWineFileBrowser());
        assertEquals("game.exe", after.getExecutable());
        assertEquals(executable.getParentFile().toURI(), java.net.URI.create(after.getRootUri()));
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getCreatedAt(), after.getCreatedAt());
        assertEquals(profile.getContainerId(), afterProfile.getContainerId());
        assertEquals(profile.getWinePackage(), afterProfile.getWinePackage());
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(marker.toPath()));
        assertThrows(java.io.IOException.class, () -> QuickGameImport.setEntry(files, id, new File(files,"missing.exe")));
        assertEquals("game.exe", new FileGameRepository(paths.getLibraryDirectory()).find(id).get().getExecutable());
    }
}
