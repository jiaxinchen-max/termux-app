package com.termux.ui.data;

import com.termux.ui.wine.data.FileGameContainerRepository;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GameStoragePathsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void wineWorkspacesAndTemplatesUseFlatSeparatePaths() throws Exception {
        File files = temporary.newFolder("files");
        GameStoragePaths paths = new GameStoragePaths(files);
        assertEquals(new File(files, "front/wine"), paths.getContainersDirectory());
        assertEquals(new File(files, "front/wine/container-1/native-prefixes/glibc_wine_9.3-vanilla"),
            paths.getNativePrefixDirectory("container-1", "glibc_wine_9.3-vanilla"));
        assertEquals(new File(files, "front/wine/container-1/prefix-rootfs_proot"),
            paths.getContainerPrefixDirectory("container-1", GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals(new File(files, "front/wine/templates"), paths.getTemplateArchiveCacheDirectory());
        assertEquals(new File(files, "usr/var/lib/proot-distro/games-shared-rootfs/rootfs"),
            paths.getSharedRootfsDirectory());
    }

    @Test public void templateAssetsAreNotEnumeratedAsContainers() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files"));
        File cache = new File(paths.getTemplateArchiveCacheDirectory(), "v11/package");
        assertTrue(cache.mkdirs());
        assertTrue(new File(cache, "prefix.tar.gz").createNewFile());
        assertTrue(new File(cache, "state.properties").createNewFile());
        assertTrue(new FileGameContainerRepository(paths.getContainersDirectory()).list().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void templateDirectoryCannotBeUsedAsAContainer() {
        new GameStoragePaths(temporary.getRoot()).getContainerDirectory("templates");
    }
}
