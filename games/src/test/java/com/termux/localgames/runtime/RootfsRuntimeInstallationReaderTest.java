package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.data.GameStoragePaths;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class RootfsRuntimeInstallationReaderTest {
    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void resolvesVersionedContainerInsideTermuxPrefix() throws Exception {
        File files = temporary.newFolder("files");
        GameStoragePaths paths = new GameStoragePaths(files);
        String container = "games-runtime-v1-aaaaaaaaaaaa";
        File rootfs = new File(paths.getProotDistroContainersDirectory(), container + "/rootfs");
        assertTrue(rootfs.mkdirs());
        writeMetadata(paths, container);

        RootfsRuntimeInstallation active = new RootfsRuntimeInstallationReader(paths)
            .readActive("debian-13-games-rootfs").get();

        assertEquals(rootfs.getCanonicalPath(), active.getRootfsDirectory().getCanonicalPath());
        assertEquals(SHA, active.getRecipeSha256());
    }

    @Test
    public void rejectsContainerTraversalInReceipt() throws Exception {
        File files = temporary.newFolder("traversal-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        File packageRoot = new File(paths.getRootfsRuntimeDirectory(), "debian-13-games-rootfs");
        write(new File(packageRoot, "active.properties"), props(
            "schemaVersion", "1", "active", "v1-aaaaaaaaaaaa"));
        write(new File(packageRoot, "versions/v1-aaaaaaaaaaaa.properties"), props(
            "schemaVersion", "1", "packageName", "debian-13-games-rootfs",
            "version", "1", "recipeSha256", SHA, "containerName", "../escape"));

        try {
            new RootfsRuntimeInstallationReader(paths)
                .readActive("debian-13-games-rootfs");
            fail("container traversal must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("rootfs_metadata_invalid"));
        }
    }

    @Test
    public void rollbackAtomicallySwapsValidatedRuntimeVersions() throws Exception {
        File files = temporary.newFolder("rollback-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        File packageRoot = new File(paths.getRootfsRuntimeDirectory(), "debian-13-games-rootfs");
        String firstContainer = "games-runtime-v1-aaaaaaaaaaaa";
        String secondContainer = "games-runtime-v2-bbbbbbbbbbbb";
        assertTrue(new File(paths.getProotDistroContainersDirectory(),
            firstContainer + "/rootfs").mkdirs());
        assertTrue(new File(paths.getProotDistroContainersDirectory(),
            secondContainer + "/rootfs").mkdirs());
        write(new File(packageRoot, "active.properties"), props(
            "schemaVersion", "1", "active", "v2-bbbbbbbbbbbb",
            "previous", "v1-aaaaaaaaaaaa"));
        write(new File(packageRoot, "versions/v1-aaaaaaaaaaaa.properties"), props(
            "schemaVersion", "1", "packageName", "debian-13-games-rootfs",
            "version", "1", "recipeSha256", SHA, "containerName", firstContainer));
        write(new File(packageRoot, "versions/v2-bbbbbbbbbbbb.properties"), props(
            "schemaVersion", "1", "packageName", "debian-13-games-rootfs",
            "version", "2", "recipeSha256",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "containerName", secondContainer));

        RootfsRuntimeInstallation rolledBack = new RootfsRuntimeActivationStore(paths)
            .rollback("debian-13-games-rootfs");

        assertEquals(1, rolledBack.getVersion());
        assertEquals(2, new RootfsRuntimeInstallationReader(paths)
            .readPrevious("debian-13-games-rootfs").get().getVersion());
    }

    private static void writeMetadata(GameStoragePaths paths, String container) throws Exception {
        File packageRoot = new File(paths.getRootfsRuntimeDirectory(), "debian-13-games-rootfs");
        write(new File(packageRoot, "active.properties"), props(
            "schemaVersion", "1", "active", "v1-aaaaaaaaaaaa"));
        write(new File(packageRoot, "versions/v1-aaaaaaaaaaaa.properties"), props(
            "schemaVersion", "1", "packageName", "debian-13-games-rootfs",
            "version", "1", "recipeSha256", SHA, "containerName", container));
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
