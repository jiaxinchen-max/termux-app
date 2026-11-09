package com.termux.ui.wine.runtime;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.*;

public class GameEditorReadinessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));

    private RuntimeProfile profile(GameRuntimeBackendType backend) {
        return new RuntimeProfile("game", backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX
            ? "glibc_wine_9.3-vanilla" : "rootfs_hangover_11.16", "rootfs-llvmpipe",
            "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(),
            "xinput", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(), backend,
            backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX ? "" : RootfsSetupRecipe.DEFAULT_PACKAGE, backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX
                ? "default" : "container");
    }

    private GameStoragePaths paths() throws IOException {
        return new GameStoragePaths(temporary.newFolder());
    }

    private void task(GameStoragePaths paths, RuntimeProfile profile, String id,
                      RuntimeSetupTaskState state, long created) throws IOException {
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).save(
                new PrefixSetupTask(id, profile.getId(), profile.getWinePackage(), state, "", created, created));
        } else {
            new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).save(
                new RuntimeSetupTask(id, profile.getRootfsPackage(), 1, SHA, "rootfs_hangover_11.16",
                    profile.getContainerId(), "container", false, state, "", created, created));
        }
    }

    @Test public void importedConfigurationNeedsItsOwnSuccessfulSaveOnBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths();
            assertFalse(backend.name(), new GameEditorReadiness(paths, p -> true).isReady(profile(backend)));
        }
    }

    @Test public void everyIncompleteOrFailedStateDeniesBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            for (RuntimeSetupTaskState state : RuntimeSetupTaskState.values()) {
                GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
                task(paths, p, "task", state, 1);
                assertEquals(backend + ":" + state, state == RuntimeSetupTaskState.SUCCEEDED,
                    new GameEditorReadiness(paths, ignored -> true).isReady(p));
            }
        }
    }

    @Test public void newFailureOrCancellationOverridesOldSuccess() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            for (RuntimeSetupTaskState state : new RuntimeSetupTaskState[] {
                RuntimeSetupTaskState.FAILED, RuntimeSetupTaskState.CANCELLED, RuntimeSetupTaskState.QUEUED}) {
                GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
                task(paths, p, "old", RuntimeSetupTaskState.SUCCEEDED, 1);
                task(paths, p, "new", state, 2);
                assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
            }
        }
    }

    @Test public void activeTaskBlocksEvenIfAnotherTaskSucceededLater() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
            task(paths, p, "running", RuntimeSetupTaskState.BUILDING, 1);
            task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 2);
            assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
        }
    }

    @Test public void userSaveAfterFailureCanBecomeReady() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
            task(paths, p, "failed", RuntimeSetupTaskState.FAILED, 1);
            task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 2);
            assertTrue(new GameEditorReadiness(paths, ignored -> true).isReady(p));
        }
    }

    @Test public void ambiguousSameTimestampFailureNeverLosesToSuccess() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
            task(paths, p, "a-success", RuntimeSetupTaskState.SUCCEEDED, 1);
            task(paths, p, "b-failure", RuntimeSetupTaskState.FAILED, 1);
            assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
        }
    }

    @Test public void nativeCannotBorrowAnotherGamesPreparation() throws Exception {
        GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).save(
            new PrefixSetupTask("other", "other-game", p.getWinePackage(), RuntimeSetupTaskState.SUCCEEDED, "", 1, 1));
        assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
    }

    @Test public void nativeChangedWineCannotUseOldPreparation() throws Exception {
        GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).save(
            new PrefixSetupTask("old", p.getId(), "glibc_wine_10.0", RuntimeSetupTaskState.SUCCEEDED, "", 1, 1));
        assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
    }

    @Test public void prootBaseOnlyAndOtherContainerSuccessDoNotUnlockGame() throws Exception {
        for (boolean baseOnly : new boolean[] {true, false}) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.ROOTFS_PROOT);
            new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).save(
                new RuntimeSetupTask("base", p.getRootfsPackage(), 1, SHA, "rootfs_hangover_11.16",
                    baseOnly ? p.getContainerId() : "other-container", "container", baseOnly,
                    RuntimeSetupTaskState.SUCCEEDED, "", 1, 1));
            assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
        }
    }

    @Test public void prootDifferentPackageDoesNotUnlockGame() throws Exception {
        GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.ROOTFS_PROOT);
        new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).save(
            new RuntimeSetupTask("wrong", "other-rootfs", 1, SHA, "rootfs_hangover_11.16",
                p.getContainerId(), "container", false, RuntimeSetupTaskState.SUCCEEDED, "", 1, 1));
        assertFalse(new GameEditorReadiness(paths, ignored -> true).isReady(p));
    }

    @Test public void deletedOrIncompleteEnvironmentDoesNotUnlockAfterSuccess() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
            task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 1);
            assertFalse(new GameEditorReadiness(paths, ignored -> false).isReady(p));
            assertFalse(new GameEditorReadiness(paths).isReady(p));
        }
    }

    @Test public void unreadableReadinessCannotBecomeSuccess() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            GameStoragePaths paths = paths(); RuntimeProfile p = profile(backend);
            task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 1);
            try {
                new GameEditorReadiness(paths, ignored -> { throw new IOException("unreadable"); }).isReady(p);
                fail("read failures must reach the host's disabled error path");
            } catch (IOException expected) { assertEquals("unreadable", expected.getMessage()); }
        }
    }

    @Test public void realNativePreparationRequiresCurrentRuntimeAndRegistry() throws Exception {
        GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 1);
        File usr = paths.getTermuxPrefixDirectory();
        for (String name : new String[] {"ld-linux-aarch64.so.1", "libc.so.6", "libX11.so.6",
                "libXrender.so.1", "libXrandr.so.2", "libfreetype.so.6"}) write(new File(usr, "glibc/lib/" + name), "x");
        for (String name : new String[] {"grun", "python", "7z", "zip", "unzip", "tar", "gzip", "bzip2", "xz", "zstd"}) {
            File command = new File(usr, "bin/" + name); write(command, "x"); assertTrue(command.setExecutable(true));
        }
        for (String name : new String[] {"termux-box/run/games-zh-cn-locale-v1", "lib/locale/zh_CN.utf8/LC_CTYPE",
                "lib/locale/zh_CN.gbk/LC_CTYPE"}) write(new File(usr, "glibc/" + name), "x");
        File prefix = paths.getNativePrefixDirectory(p.getContainerId(), p.getWinePackage());
        prefix(prefix);
        for (String name : new String[] {".termux-box-bootstrap-done", ".update-timestamp", "termux-boxmeta/games-wine-fonts-v2"}) write(new File(prefix, name), "x");
        write(new File(prefix, ".games-runtime/prepared-identity"), SHA + "\n");
        write(new File(prefix, ".games-runtime/preparation-script-identity"), SHA + "\n");
        File runtime = new File(prefix, ".termux-box-wine-package"); write(runtime, p.getWinePackage() + "\n");
        GameEditorReadiness check = new GameEditorReadiness(paths);
        assertTrue(check.isReady(p));
        write(runtime, "glibc_wine_10.0\n"); assertFalse(check.isReady(p));
        write(runtime, p.getWinePackage() + "\n");
        assertTrue(new File(prefix, "system.reg").delete()); assertFalse(check.isReady(p));
    }

    @Test public void realProotPreparationRequiresMatchingBindingsAndPrefix() throws Exception {
        GameStoragePaths paths = paths(); RuntimeProfile p = profile(GameRuntimeBackendType.ROOTFS_PROOT);
        task(paths, p, "success", RuntimeSetupTaskState.SUCCEEDED, 1);
        File root = paths.getSharedRootfsDirectory();
        write(new File(root, "etc/games-runtime.properties"), "schemaVersion=3\nlayout=external-components-v1\n");
        for (String name : new String[] {"env", "wine", "wineboot"}) {
            File command = new File(root, "usr/bin/" + name); write(command, "x"); assertTrue(command.setExecutable(true));
        }
        write(new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"), "x");
        assertTrue(new File(root, "mnt/games/game").mkdirs());
        assertTrue(new File(root, "mnt/games/prefix").mkdirs());
        ProotPreparedRuntime.configurations(paths).write(p.getContainerId(), new ProotBindingSnapshot(
            p.getWinePackage(), p.getDxWrapper(), RuntimeTranslator.HANGOVER, "", SHA, Collections.emptyList()));
        File prefix = paths.getContainerPrefixDirectory(p.getContainerId(), GameRuntimeBackendType.ROOTFS_PROOT);
        prefix(prefix);
        write(new File(prefix, ".games-runtime/runtime"), root.getAbsolutePath() + "|" + p.getWinePackage() + "\n");
        File receipt = new File(prefix, ".games-runtime/preparation-recipe"); write(receipt, SHA + "\n");
        GameEditorReadiness check = new GameEditorReadiness(paths);
        assertTrue(check.isReady(p));
        File pending = new File(prefix, ".games-runtime/wine-dlls-pending"); write(pending, "x");
        assertFalse(check.isReady(p)); assertTrue(pending.delete());
        write(new File(prefix, ".games-runtime/runtime"), root.getAbsolutePath() + "|rootfs_wine_10.0\n");
        assertFalse(check.isReady(p));
        write(new File(prefix, ".games-runtime/runtime"), root.getAbsolutePath() + "|" + p.getWinePackage() + "\n");
        assertTrue(receipt.delete()); assertFalse(check.isReady(p));
    }

    private void prefix(File prefix) throws IOException {
        write(new File(prefix, "system.reg"), "registry"); write(new File(prefix, "user.reg"), "registry");
        assertTrue(new File(prefix, "drive_c").mkdirs());
    }

    private void write(File file, String value) throws IOException {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
