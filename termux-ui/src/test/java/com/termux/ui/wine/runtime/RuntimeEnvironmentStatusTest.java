package com.termux.ui.wine.runtime;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FileGameContainerRepository;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeReadinessState;
import com.termux.ui.wine.domain.RuntimeTranslator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeEnvironmentStatusTest {
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

        File python = new File(bin, "python");
        assertTrue(python.createNewFile());
        assertTrue(python.setExecutable(true));
        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).glibcState());
        File sevenzip = new File(bin, "7z");
        assertTrue(sevenzip.createNewFile());
        assertTrue(sevenzip.setExecutable(false));
        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).glibcState());
        assertTrue(sevenzip.setExecutable(true));
        assertEquals(RuntimeReadinessState.READY,
            new RuntimeEnvironmentStatus(paths).glibcState());
        assertTrue(python.setExecutable(false));
        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).glibcState());
    }

    @Test
    public void sharedToolsAloneDoNotMakeGlibcInstalled() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-tools-only"));
        File bin = new File(paths.getTermuxPrefixDirectory(), "bin");
        assertTrue(bin.mkdirs());
        for (String name : new String[] {"python", "7z"}) {
            File tool = new File(bin, name);
            assertTrue(tool.createNewFile());
            assertTrue(tool.setExecutable(true));
        }
        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).glibcState());
    }

    @Test
    public void rootfsNotReadyWithoutAnyContainer() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-rootfs-empty"));
        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsNotReadyWhenNoArchiveExistsEvenIfAnUnrelatedContainerExists()
        throws Exception {
        // rootfsState() is about the one shared RootFS directory only -- a container record lying
        // around must not make it read as anything other than NOT_READY. Every container mounts
        // the one shared, always-current image now (see GameStoragePaths.getSharedRootfsDirectory()),
        // so there is no per-container copy for this to be confused with.
        File files = temporary.newFolder("files-rootfs-partial");
        GameStoragePaths paths = new GameStoragePaths(files);
        GameContainer container = new GameContainer("container-game-b", "Game B",
            GameRuntimeBackendType.ROOTFS_PROOT, GameContainer.ROOTFS_RUNTIME_PACKAGE,
            RuntimeTranslator.HANGOVER, "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap());
        new FileGameContainerRepository(paths.getContainersDirectory()).save(container);

        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsReadyWhenSharedRootfsIsComplete() throws Exception {
        File files = temporary.newFolder("files-rootfs-ready");
        GameStoragePaths paths = new GameStoragePaths(files);
        createCompleteSharedRootfs(paths);

        assertEquals(RuntimeReadinessState.READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void externalRootfsIsReadyWithoutStandaloneBox64() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-external"));
        File root = createCompleteSharedRootfs(paths);
        assertTrue(new File(root, "opt/box64/rootfs_box64_0.4.5/bin/box64").delete());
        File manifest = new File(root, "etc/games-runtime.properties");
        java.nio.file.Files.write(manifest.toPath(),
            "schemaVersion=3\nlayout=external-components-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(RuntimeReadinessState.READY, new RuntimeEnvironmentStatus(paths).rootfsState());
        java.nio.file.Files.write(manifest.toPath(),
            "schemaVersion=3\nlayout=unknown\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(RuntimeReadinessState.INCOMPLETE, new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsIncompleteWhenSharedRootfsIsMissingOneMarker() throws Exception {
        File files = temporary.newFolder("files-rootfs-incomplete");
        GameStoragePaths paths = new GameStoragePaths(files);
        File root = createCompleteSharedRootfs(paths);
        assertTrue(new File(root, "usr/bin/wineboot").delete());

        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsIncompleteAfterImageWasRemoved() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-removed"));
        File receipt = new File(paths.getTemplateArchiveCacheDirectory(), "games-rootfs-base.recipe");
        assertTrue(receipt.getParentFile().mkdirs());
        assertTrue(receipt.createNewFile());
        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    @Test
    public void rootfsIncompleteAfterInterruptedBuild() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files-interrupted"));
        assertTrue(paths.getRootfsBuildContainerDirectory().mkdirs());
        assertEquals(RuntimeReadinessState.INCOMPLETE,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    private static File createCompleteSharedRootfs(GameStoragePaths paths) throws Exception {
        File root = paths.getSharedRootfsDirectory();
        for (String executable : new String[] {"usr/bin/env", "opt/box64/rootfs_box64_0.4.5/bin/box64", "usr/bin/wine", "usr/bin/wineboot"}) {
            File file = new File(root, executable);
            assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
            assertTrue(file.createNewFile());
            assertTrue(file.setExecutable(true));
        }
        File font = new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
        assertTrue(font.getParentFile().mkdirs());
        assertTrue(font.createNewFile());
        File manifest = new File(root, "etc/games-runtime.properties");
        assertTrue(manifest.getParentFile().mkdirs());
        assertTrue(manifest.createNewFile());
        assertTrue(new File(root, "mnt/games/game").mkdirs());
        assertTrue(new File(root, "mnt/games/prefix").mkdirs());
        return root;
    }
}
