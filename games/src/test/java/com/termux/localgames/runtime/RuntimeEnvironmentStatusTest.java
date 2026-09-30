package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeReadinessState;
import com.termux.localgames.domain.RuntimeTranslator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Properties;

public class RuntimeEnvironmentStatusTest {
    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void glibcNotReadyWhenMarkerFilesAreMissing() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-empty"));
        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).glibcState());
    }

    @Test
    public void glibcIncompleteWhenSomeButNotAllMarkersExist() throws Exception {
        File files = temporary.newFolder("files-glibc-partial");
        GameStoragePaths paths = new GameStoragePaths(files);
        File glibc = new File(paths.getTermuxPrefixDirectory(), "glibc/lib");
        assertTrue(glibc.mkdirs());
        assertTrue(new File(glibc, "ld-linux-aarch64.so.1").createNewFile());
        assertTrue(new File(glibc, "libc.so.6").createNewFile());

        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).glibcState());
    }

    @Test
    public void glibcReadyWhenAllInstallVerificationMarkersExist() throws Exception {
        File files = temporary.newFolder("files-glibc");
        GameStoragePaths paths = new GameStoragePaths(files);
        File glibc = new File(paths.getTermuxPrefixDirectory(), "glibc/lib");
        assertTrue(glibc.mkdirs());
        for (String name : new String[] {"ld-linux-aarch64.so.1", "libc.so.6", "libX11.so.6",
            "libXrender.so.1", "libfreetype.so.6"}) {
            assertTrue(new File(glibc, name).createNewFile());
        }
        File bin = new File(paths.getTermuxPrefixDirectory(), "bin");
        assertTrue(bin.mkdirs());
        File grun = new File(bin, "grun");
        assertTrue(grun.createNewFile());
        assertTrue(grun.setExecutable(true));

        assertEquals(RuntimeReadinessState.READY,
            new RuntimeEnvironmentStatus(paths).glibcState());
    }

    @Test
    public void rootfsNotReadyWithoutAnyContainer() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-rootfs-empty"));
        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsNotReadyWhenNoTemplateExistsEvenIfAnUnrelatedContainerExists()
        throws Exception {
        // rootfsState() is about the shared template only -- an old container lying around
        // (never even activated here) must not make it read as anything other than NOT_READY.
        File files = temporary.newFolder("files-rootfs-partial");
        GameStoragePaths paths = new GameStoragePaths(files);
        GameContainer container = new GameContainer("container-game-b", "Game B",
            GameRuntimeBackendType.ROOTFS_PROOT, GameContainer.ROOTFS_RUNTIME_PACKAGE,
            RuntimeTranslator.HANGOVER, "hangover-11.9", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap());
        new FileGameContainerRepository(paths.getContainersDirectory()).save(container);

        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsReadyWhenAnActiveContainerExistsAndTheSharedTemplateIsIntact()
        throws Exception {
        File files = temporary.newFolder("files-rootfs-ready");
        GameStoragePaths paths = new GameStoragePaths(files);
        String containerId = "container-game-a";
        activateContainer(paths, containerId);
        createCompleteRootfsBuild(new File(paths.getProotDistroContainersDirectory(),
            "tmpl-abcdef0123456789/rootfs"));

        assertEquals(RuntimeReadinessState.READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsReadyWhenTemplateIsIntactEvenWithoutAnyContainer() throws Exception {
        // Mirror of the previous test with the container removed entirely -- proves READY is
        // driven purely by the shared template, not by any container's existence.
        File files = temporary.newFolder("files-rootfs-ready-no-container");
        GameStoragePaths paths = new GameStoragePaths(files);
        createCompleteRootfsBuild(new File(paths.getProotDistroContainersDirectory(),
            "tmpl-abcdef0123456789/rootfs"));

        assertEquals(RuntimeReadinessState.READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsIncompleteWhenTemplateDirectoryExistsButBuildIsUnfinished()
        throws Exception {
        File files = temporary.newFolder("files-rootfs-template-unfinished");
        GameStoragePaths paths = new GameStoragePaths(files);
        assertTrue(new File(paths.getProotDistroContainersDirectory(),
            "tmpl-abcdef0123456789/rootfs").mkdirs());

        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsNotReadyWhenActiveContainerExistsButSharedTemplateWasReset()
        throws Exception {
        // Reproduces the "reset RootFS" report: an already-installed, playable game container
        // keeps working (still has an active build), but the shared template it was cloned
        // from has just been wiped. New containers can't be built until it is rebuilt, so this
        // reads as NOT_READY -- the old container's continued usability is a separate,
        // per-container fact (see isRootfsContainerStale*) that must not keep rootfsState()
        // artificially READY/INCOMPLETE.
        File files = temporary.newFolder("files-rootfs-template-reset");
        GameStoragePaths paths = new GameStoragePaths(files);
        activateContainer(paths, "container-game-a");
        // No tmpl-* directory at all -- as if it was just deleted by a reset.

        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void isRootfsContainerStaleWhenTemplateNoLongerExists() throws Exception {
        File files = temporary.newFolder("files-stale-template-gone");
        GameStoragePaths paths = new GameStoragePaths(files);
        activateContainer(paths, "container-game-a");
        // No tmpl-<SHA prefix> directory -- the template this container was built from is gone.

        assertTrue(new RuntimeEnvironmentStatus(paths)
            .isRootfsContainerStale("container-game-a", GameContainer.ROOTFS_RUNTIME_PACKAGE));
    }

    @Test
    public void isRootfsContainerStaleIsFalseWhenTemplateIsCurrent() throws Exception {
        File files = temporary.newFolder("files-stale-template-current");
        GameStoragePaths paths = new GameStoragePaths(files);
        activateContainer(paths, "container-game-a");
        createCompleteRootfsBuild(new File(paths.getProotDistroContainersDirectory(),
            "tmpl-" + SHA.substring(0, 16) + "/rootfs"));

        assertFalse(new RuntimeEnvironmentStatus(paths)
            .isRootfsContainerStale("container-game-a", GameContainer.ROOTFS_RUNTIME_PACKAGE));
    }

    @Test
    public void isRootfsContainerStaleIsFalseWhenContainerHasNoActiveBuild() throws Exception {
        File files = temporary.newFolder("files-stale-no-active-build");
        GameStoragePaths paths = new GameStoragePaths(files);

        assertFalse(new RuntimeEnvironmentStatus(paths)
            .isRootfsContainerStale("container-game-a", GameContainer.ROOTFS_RUNTIME_PACKAGE));
    }

    private static void activateContainer(GameStoragePaths paths, String containerId)
        throws Exception {
        GameContainer container = new GameContainer(containerId, "Game A",
            GameRuntimeBackendType.ROOTFS_PROOT, GameContainer.ROOTFS_RUNTIME_PACKAGE,
            RuntimeTranslator.HANGOVER, "hangover-11.9", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap());
        new FileGameContainerRepository(paths.getContainersDirectory()).save(container);

        assertTrue(new File(paths.getProotDistroContainersDirectory(), containerId + "/rootfs")
            .mkdirs());
        File packageRoot = new File(paths.getRootfsRuntimeDirectory(containerId),
            GameContainer.ROOTFS_RUNTIME_PACKAGE);
        write(new File(packageRoot, "active.properties"), props(
            "schemaVersion", "1", "active", "v1-aaaaaaaaaaaa"));
        write(new File(packageRoot, "versions/v1-aaaaaaaaaaaa.properties"), props(
            "schemaVersion", "1", "packageName", GameContainer.ROOTFS_RUNTIME_PACKAGE,
            "version", "1", "recipeSha256", SHA, "containerName", containerId));
    }

    private static void createCompleteRootfsBuild(File root) throws Exception {
        for (String directory : new String[] {"usr/bin", "usr/local/bin",
            "usr/share/fonts/opentype/noto", "etc", "mnt/games/game", "mnt/games/prefix"}) {
            assertTrue(new File(root, directory).mkdirs());
        }
        for (String executable : new String[] {"usr/bin/env", "usr/local/bin/box64",
            "usr/bin/wine", "usr/bin/wineboot"}) {
            File file = new File(root, executable);
            assertTrue(file.createNewFile());
            assertTrue(file.setExecutable(true));
        }
        assertTrue(new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
            .createNewFile());
        assertTrue(new File(root, "etc/games-runtime.properties").createNewFile());
    }

    private static Properties props(String... pairs) {
        Properties value = new Properties();
        for (int i = 0; i < pairs.length; i += 2) value.setProperty(pairs[i], pairs[i + 1]);
        return value;
    }

    private static void write(File file, Properties value) throws Exception {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            value.store(output, null);
        }
    }
}
