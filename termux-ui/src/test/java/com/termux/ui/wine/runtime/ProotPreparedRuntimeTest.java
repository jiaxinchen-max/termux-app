package com.termux.ui.wine.runtime;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.domain.ProotBindingSnapshot;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProotPreparedRuntimeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));
    private RuntimeProfile profile(String dx) {
        return new RuntimeProfile("game", "rootfs_hangover_11.16", "rootfs-llvmpipe", dx,
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT,
            RootfsSetupRecipe.DEFAULT_PACKAGE, "container");
    }
    private GameStoragePaths paths() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temp.newFolder());
        File root = paths.getSharedRootfsDirectory();
        new File(root, "etc").mkdirs();
        new File(root, "mnt/games/game").mkdirs();
        new File(root, "mnt/games/prefix").mkdirs();
        Files.write(new File(root, "etc/games-runtime.properties").toPath(),
            ("schemaVersion=3\nlayout=external-components-v1\nruntimeBackend=rootfs_proot\n" +
             "architecture=aarch64\nruntimePackages=rootfs_hangover_11.16\ndxWrappers=rootfs-wined3d\n" +
             "graphicsDrivers=rootfs-llvmpipe\naudioDrivers=pulseaudio\nrecipeSha256="+SHA+"\n")
                .getBytes(StandardCharsets.US_ASCII));
        return paths;
    }
    private void prepare(GameStoragePaths paths, String recipe) throws Exception {
        ProotPreparedRuntime.configurations(paths).write("container", new ProotBindingSnapshot(
            "rootfs_hangover_11.16", "rootfs-wined3d", RuntimeTranslator.HANGOVER, "", recipe,
            Collections.emptyList()));
    }
    @Test public void preparedHangoverBaseDoesNotConsultComponentActivePointers() throws Exception {
        GameStoragePaths paths = paths();
        prepare(paths, SHA);
        File install = new ComponentStoragePaths(paths.getTermuxPrefixDirectory().getParentFile())
            .getInstallDirectory();
        File bogusPointer = new File(install, "rootfs_hangover_11.16/active.properties");
        bogusPointer.getParentFile().mkdirs();
        Files.write(bogusPointer.toPath(), "not an active pointer".getBytes(StandardCharsets.US_ASCII));
        RootfsProotBackend backend = new RootfsProotBackend();
        assertTrue(backend.requiredComponentIds(profile("rootfs-wined3d"), paths).isEmpty());
        assertEquals(paths.getSharedRootfsDirectory().getCanonicalPath(), backend.resolveRuntimeRoot(paths,
            new ComponentStoragePaths(paths.getTermuxPrefixDirectory().getParentFile()), profile("rootfs-wined3d")));
    }
    @Test(expected = IOException.class) public void missingSnapshotCannotBeReportedReady() throws Exception {
        ProotPreparedRuntime.require(paths(), profile("rootfs-wined3d"));
    }
    @Test(expected = IOException.class) public void changedSelectionRequiresExplicitPreparation() throws Exception {
        GameStoragePaths paths = paths(); prepare(paths, SHA);
        ProotPreparedRuntime.require(paths, profile("rootfs_dxvk_3.1"));
    }
    @Test public void recipeDigestDifferenceDoesNotBlockBindings() throws Exception {
        GameStoragePaths paths = paths(); prepare(paths, SHA.replace('a', 'b'));
        ProotPreparedRuntime.require(paths, profile("rootfs-wined3d"));
    }
    private File preparationRecord(GameStoragePaths paths, String value) throws Exception {
        File file = new File(paths.getContainerPrefixDirectory("container", GameRuntimeBackendType.ROOTFS_PROOT),
            ".games-runtime/preparation-recipe");
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), value.getBytes(StandardCharsets.US_ASCII));
        return file;
    }
    @Test public void newPrefixRecipeUsesExistingPrebuiltImage() throws Exception {
        GameStoragePaths paths = paths();
        String updated = SHA.replace('a', 'b');
        prepare(paths, updated); preparationRecord(paths, updated + "\n");
        assertEquals(updated, ProotPreparedRuntime.require(paths, profile("rootfs-wined3d")).getRecipeSha256());
        assertTrue(new String(Files.readAllBytes(new File(paths.getSharedRootfsDirectory(),
            "etc/games-runtime.properties").toPath()), StandardCharsets.US_ASCII).contains("recipeSha256=" + SHA));
    }
    @Test public void preparationDigestDifferenceDoesNotBlockBindings() throws Exception {
        GameStoragePaths paths = paths(); prepare(paths, SHA);
        preparationRecord(paths, SHA.replace('a', 'b') + "\n");
        ProotPreparedRuntime.require(paths, profile("rootfs-wined3d"));
    }
    @Test(expected = IOException.class) public void emptyPreparationReceiptIsInvalid() throws Exception {
        GameStoragePaths paths = paths(); prepare(paths, SHA); preparationRecord(paths, "");
        ProotPreparedRuntime.require(paths, profile("rootfs-wined3d"));
    }
    @Test(expected = IOException.class) public void linkedPreparationReceiptRejected() throws Exception {
        GameStoragePaths paths = paths(); prepare(paths, SHA);
        File record = preparationRecord(paths, SHA + "\n");
        File other = temp.newFile(); Files.write(other.toPath(), (SHA + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.delete(record.toPath()); Files.createSymbolicLink(record.toPath(), other.toPath());
        ProotPreparedRuntime.require(paths, profile("rootfs-wined3d"));
    }
}
