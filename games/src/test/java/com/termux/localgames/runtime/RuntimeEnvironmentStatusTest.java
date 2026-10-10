package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
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
import java.util.Collections;

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
            RuntimeTranslator.HANGOVER, "hangover-latest", "rootfs-llvmpipe", "rootfs-wined3d",
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
    public void rootfsNotReadyWhenSharedRootfsIsMissingOneMarker() throws Exception {
        File files = temporary.newFolder("files-rootfs-incomplete");
        GameStoragePaths paths = new GameStoragePaths(files);
        File root = createCompleteSharedRootfs(paths);
        assertTrue(new File(root, "usr/bin/wineboot").delete());

        assertEquals(RuntimeReadinessState.NOT_READY,
            new RuntimeEnvironmentStatus(paths).rootfsState());
    }

    private static File createCompleteSharedRootfs(GameStoragePaths paths) throws Exception {
        File root = paths.getSharedRootfsDirectory();
        for (String executable : new String[] {"usr/bin/env", "usr/local/bin/box64",
            "usr/bin/wine", "usr/bin/wineboot"}) {
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
