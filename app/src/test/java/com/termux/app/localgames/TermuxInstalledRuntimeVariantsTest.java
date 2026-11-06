package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class TermuxInstalledRuntimeVariantsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void listsOnlyPresentNonemptyWhitelistedArchives() throws Exception {
        File files = temporary.newFolder();
        archive(files, "d3d/dxvk-gplasync-2.3.7z");
        archive(files, "d3d/dxvk-unknown.7z");
        File empty = new File(files, "usr/glibc/opt/libs/d3d/dxvk-dev.7z");
        assertTrue(empty.createNewFile());
        File directory = new File(files, "usr/glibc/opt/libs/d3d/dxvk-0.96.7z");
        assertTrue(directory.mkdir());
        List<RuntimeComponentVariant> variants = new TermuxInstalledRuntimeVariants(files)
            .list(CustomRuntimeComponent.Kind.DXVK, GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        assertEquals(1, variants.size());
        assertEquals("native-dxvk-gplasync-2.3", variants.get(0).getId());
        assertEquals(CustomRuntimeComponent.Kind.DXVK, variants.get(0).getKind());
    }

    @Test public void keepsBackendAndKindScopesSeparate() throws Exception {
        File files = temporary.newFolder();
        archive(files, "d3d/vkd3d.7z");
        archive(files, "mesa/turnip-v6.5.7z");
        TermuxInstalledRuntimeVariants inventory = new TermuxInstalledRuntimeVariants(files);
        assertTrue(inventory.list(CustomRuntimeComponent.Kind.TURNIP,
            GameRuntimeBackendType.ROOTFS_PROOT).isEmpty());
        assertTrue(inventory.list(CustomRuntimeComponent.Kind.DXVK,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).isEmpty());
        assertEquals("native-vkd3d", inventory.list(CustomRuntimeComponent.Kind.VKD3D,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).get(0).getId());
        assertEquals("native-turnip-v6.5", inventory.list(CustomRuntimeComponent.Kind.TURNIP,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).get(0).getId());
    }

    @Test public void doesNotGuessAlternateExtensionsOrExpandedDirectory() throws Exception {
        File files = temporary.newFolder();
        archive(files, "mesa/turnip-v6.5.tar.xz");
        assertTrue(new File(files, "usr/glibc/opt/libs/mesa/turnip-v5").mkdir());
        assertTrue(new TermuxInstalledRuntimeVariants(files).list(CustomRuntimeComponent.Kind.TURNIP,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).isEmpty());
    }

    private static void archive(File files, String relative) throws Exception {
        File archive = new File(files, "usr/glibc/opt/libs/" + relative);
        if (!archive.getParentFile().isDirectory()) assertTrue(archive.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(archive)) { output.write(1); }
    }
}
