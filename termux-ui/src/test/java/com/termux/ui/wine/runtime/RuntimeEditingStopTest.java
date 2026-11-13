package com.termux.ui.wine.runtime;

import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeEditingStopTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));

    @Test public void bothBackendsProtectInstallationAndOnlyStopAfterItEnds() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            for (RuntimeSetupTaskState state : RuntimeSetupTaskState.values()) {
                File files = temporary.newFolder(); GameStoragePaths paths = new GameStoragePaths(files);
                RuntimeProfile profile = profile(backend, "game", "container");
                new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(profile);
                if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                    new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).save(
                        new PrefixSetupTask("task", "game", profile.getWinePackage(), state, "", 1, 1));
                } else {
                    new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).save(
                        new RuntimeSetupTask("task", profile.getRootfsPackage(), 1, SHA, profile.getWinePackage(),
                            "container", "container", false, state, "", 1, 1));
                }
                final boolean[] killed = {false};
                Host host = new Host() {
                    @Override public void forceStopRuntimeProcesses(List<String> scopes) {
                        killed[0] = true;
                        assertTrue(scopes.contains(paths.getContainerPrefixDirectory("container", backend,
                            profile.getWinePackage()).getAbsolutePath()));
                    }
                };
                if (!state.isTerminal()) {
                    assertEquals("runtime_installation_busy", assertThrows(IOException.class,
                        () -> RuntimeEditingStop.game(files, host, "game")).getMessage());
                    assertFalse(killed[0]);
                    RuntimeEditingStop.requireNotCancelled(files, "task");
                } else {
                    RuntimeEditingStop.game(files, host, "game");
                    assertTrue(killed[0]);
                    assertThrows(IOException.class, () -> RuntimeEditingStop.requireNotCancelled(files, "task"));
                }
                RuntimeSetupTaskState result = backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX
                    ? new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).find("task").get().getState()
                    : new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).find("task").get().getState();
                assertEquals(state, result);
            }
        }
    }

    @Test public void taskNotYetPersistedIsCancelledAndCannotBeStartedLater() throws Exception {
        File files = temporary.newFolder();
        RuntimeEditingStop.register("pending", "game:game");
        RuntimeEditingStop.game(files, new Host(), "game");
        assertThrows(IOException.class, () -> RuntimeEditingStop.requireNotCancelled(files, "pending"));
        assertTrue(RuntimeEditingStop.shellCancelled(files, "games-runtime-setup-pending"));
        assertTrue(RuntimeEditingStop.shellCancelled(files, "local-games-pending"));
    }

    @Test public void pendingDesktopCommandIsCancelledBeforeServiceStartsIt() throws Exception {
        File files = temporary.newFolder();
        RuntimeEditingStop.register("desktop-start-one", "desktop:one");
        RuntimeEditingStop.register("desktop-start-two", "desktop:two");
        RuntimeEditingStop.cancelOwner(files, "desktop:one");
        assertTrue(RuntimeEditingStop.shellCancelled(files, "local-games-desktop-start-one"));
        assertFalse(RuntimeEditingStop.shellCancelled(files, "local-games-desktop-start-two"));
    }

    @Test public void noTasksStillInvokesForcedProcessCleanup() throws Exception {
        File files = temporary.newFolder(); final boolean[] killed = {false};
        RuntimeEditingStop.game(files, new Host() {
            public void forceStopRuntimeProcesses(List<String> paths) { killed[0] = true; }
        }, "game");
        assertTrue(killed[0]);
    }

    @Test public void otherContainerPreparationRemainsUntouched() throws Exception {
        File files = temporary.newFolder(); GameStoragePaths paths = new GameStoragePaths(files);
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "game", "one"));
        FilePrefixSetupTaskRepository tasks = new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        tasks.save(PrefixSetupTask.queued("other", "other-game", "glibc_wine_9.3-vanilla", 1));
        RuntimeEditingStop.game(files, new Host(), "game");
        assertEquals(RuntimeSetupTaskState.QUEUED, tasks.find("other").get().getState());
    }

    @Test public void sharedNativePrefixStopsItsRelatedGames() throws Exception {
        File files = temporary.newFolder(); GameStoragePaths paths = new GameStoragePaths(files);
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        profiles.save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "game", "default"));
        profiles.save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "other-game", "default"));
        FileLaunchTaskRepository tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        tasks.save(LaunchTask.queued("other-launch", "other-game", 1));
        RuntimeEditingStop.game(files, new Host(), "game");
        assertEquals(LaunchTaskState.CANCELLED, tasks.find("other-launch").get().getState());
    }

    @Test public void sharedNativePrefixInstallationCannotBeKilledThroughAnotherGameOrDraft() throws Exception {
        File files = temporary.newFolder(); GameStoragePaths paths = new GameStoragePaths(files);
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        profiles.save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "owner", "default"));
        profiles.save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "peer", "default"));
        com.termux.ui.wine.importer.GameImportDrafts drafts = new com.termux.ui.wine.importer.GameImportDrafts(files);
        drafts.games().save(new Game("draft", "Draft", "file:///games", "game.exe", ".", Collections.emptyList(), "", 0));
        drafts.profiles().save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "draft", "default"));
        FilePrefixSetupTaskRepository tasks = new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        tasks.save(PrefixSetupTask.queued("owner-task", "owner", "glibc_wine_9.3-vanilla", 1));
        for (String id : Arrays.asList("owner", "peer", "draft")) {
            assertEquals("owner-task", com.termux.ui.wine.api.ActiveWineInstallation.find(files, id).taskId);
            assertThrows(IOException.class, () -> RuntimeEditingStop.game(files, new Host() {
                public void forceStopRuntimeProcesses(List<String> scopes) { fail("must not kill installer"); }
            }, id));
        }
        assertEquals(RuntimeSetupTaskState.QUEUED, tasks.find("owner-task").get().getState());
    }

    @Test public void killFailureIsReportedInsteadOfAllowingMutation() throws Exception {
        File files = temporary.newFolder();
        assertEquals("runtime_force_stop_failed", assertThrows(IOException.class, () -> RuntimeEditingStop.game(files,
            new Host() { public void forceStopRuntimeProcesses(List<String> paths) throws LaunchHostException {
                throw new LaunchHostException("runtime_force_stop_failed", true, null);
            } }, "game")).getMessage());
    }

    @Test public void draftEditorStillStopsGamesUsingTheSameNativePrefix() throws Exception {
        File files = temporary.newFolder(); GameStoragePaths paths = new GameStoragePaths(files);
        com.termux.ui.wine.importer.GameImportDrafts drafts = new com.termux.ui.wine.importer.GameImportDrafts(files);
        drafts.games().save(new Game("draft", "Draft", "file:///games", "game.exe", ".", Collections.emptyList(), "", 0));
        drafts.profiles().save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "draft", "default"));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory())
            .save(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "running", "default"));
        FileLaunchTaskRepository tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        tasks.save(LaunchTask.queued("running-launch", "running", 1));
        RuntimeEditingStop.game(files, new Host() {
            public void forceStopRuntimeProcesses(List<String> scopes) {
                assertTrue(scopes.contains(paths.getNativePrefixDirectory("default", "glibc_wine_9.3-vanilla").getAbsolutePath()));
            }
        }, "draft");
        assertEquals(LaunchTaskState.CANCELLED, tasks.find("running-launch").get().getState());
        assertTrue(drafts.isDraft("draft"));
        assertTrue(new FileGameRepository(paths.getLibraryDirectory()).list().isEmpty());
    }

    private RuntimeProfile profile(GameRuntimeBackendType backend, String id, String container) {
        return new RuntimeProfile(id, backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX
            ? "glibc_wine_9.3-vanilla" : "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), backend, backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX
                ? "" : RootfsSetupRecipe.DEFAULT_PACKAGE, container);
    }
    private static class Host implements LocalGamesHost {
        public boolean isRuntimeAvailable() { return true; }
        public void forceStopRuntimeProcesses(List<String> paths) throws LaunchHostException {}
    }
}
