package com.termux.ui.wine.runtime;

import com.termux.ui.data.CustomComponentInstallation;
import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.*;

public final class CustomComponentSelectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void customSelectionsNeverRequestDefaultCatalogVersions() {
        RuntimeProfile profile = profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            "custom-turnip-test", "custom-dxvk-test", "custom-box64-test");
        java.util.Set<String> ids = new GlibcTermuxBoxBackend().requiredComponentIds(profile);
        assertFalse(ids.contains("turnip"));
        assertFalse(ids.contains("dxvk"));
        assertFalse(ids.contains("glibc_box64_0.2.8"));
        assertTrue(ids.contains("glibc_scripts_29"));
    }

    @Test public void missingRegistryEntryIsAVisibleError() throws Exception {
        GameStoragePaths paths = paths();
        expect("custom_component_not_installed:custom-dxvk-test", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                "native-turnip-v6.5", "custom-dxvk-test", ""), paths));
    }

    @Test public void registryEntryWithoutPublishedPayloadIsRejected() throws Exception {
        GameStoragePaths paths = paths();
        new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory())
            .save(component(CustomRuntimeComponent.Kind.DXVK, GameRuntimeBackendType.GLIBC_TERMUX_BOX));
        expect("custom_component_payload_missing:custom-dxvk-test", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                "native-turnip-v6.5", "custom-dxvk-test", ""), paths));
    }

    @Test public void sameIdFromAnotherBackendCannotRun() throws Exception {
        GameStoragePaths paths = paths();
        new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory())
            .save(component(CustomRuntimeComponent.Kind.DXVK, GameRuntimeBackendType.ROOTFS_PROOT));
        expect("custom_component_backend_mismatch:custom-dxvk-test", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                "native-turnip-v6.5", "custom-dxvk-test", ""), paths));
    }

    @Test public void publishedCustomDxvkIsNotRequiredInTheBaseManifest() throws Exception {
        GameStoragePaths paths = paths();
        CustomRuntimeComponent component = component(CustomRuntimeComponent.Kind.DXVK,
            GameRuntimeBackendType.ROOTFS_PROOT);
        new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory()).save(component);
        File dir = CustomComponentInstallation.directory(paths, component);
        write(new File(dir, ".custom-component.properties"), "schemaVersion=1\nruntimeBackend=rootfs_proot\nruntimeTranslator=none\nkind=dxvk\ncomponentId=custom-dxvk-test\nsha256=" + SHA + "\n");
        for (String arch : new String[] {"x32", "x64"}) {
            write(new File(dir, arch + "/d3d11.dll"), "dll");
            write(new File(dir, arch + "/dxgi.dll"), "dll");
        }
        File rootfs = paths.getSharedRootfsDirectory();
        assertTrue(new File(rootfs, "mnt/games/game").mkdirs());
        assertTrue(new File(rootfs, "mnt/games/prefix").mkdirs());
        write(new File(rootfs, "etc/wine-runtime.properties"), "schemaVersion=2\nruntimeBackend=rootfs_proot\narchitecture=aarch64\nruntimePackages=rootfs_wine_10.0\ngraphicsDrivers=rootfs-llvmpipe\ndxWrappers=rootfs-wined3d\naudioDrivers=pulseaudio\n");
        assertEquals(rootfs.getCanonicalPath(), new RootfsProotBackend().resolveRuntimeRoot(paths,
            new com.termux.ui.components.ComponentStoragePaths(rootfs),
            profile(GameRuntimeBackendType.ROOTFS_PROOT, "rootfs-llvmpipe", "custom-dxvk-test", "")));
    }

    @Test public void rootfsRejectsNativeOnlyCustomKindsAndVirglDxvk() {
        expect("custom_component_backend_unsupported:custom-turnip-test", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.ROOTFS_PROOT,
                "custom-turnip-test", "rootfs-wined3d", ""), null));
        expect("runtime_combination_unsupported:virgl_dxvk", () ->
            new RootfsProotBackend().requiredComponentIds(profile(GameRuntimeBackendType.ROOTFS_PROOT,
                "rootfs-virgl-mesa", "custom-dxvk-test", "")));
    }

    @Test public void unversionedAndInventedNativeIdsAreNotAliases() {
        expect("renderer_unsupported:turnip", () -> new GlibcTermuxBoxBackend()
            .requiredComponentIds(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "turnip", "wined3d", "")));
        expect("dxWrapper:native-dxvk-invented", () -> new GlibcTermuxBoxBackend()
            .requiredComponentIds(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                "native-turnip-v6.5", "native-dxvk-invented", "")));
        expect("box64_selection_unsupported:glibc_box64_99.0", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                "native-turnip-v6.5", "wined3d", "glibc_box64_99.0"), null));
    }

    @Test public void explicitBox64CannotOverrideHangover() {
        RuntimeProfile base = profile(GameRuntimeBackendType.ROOTFS_PROOT,
            "rootfs-llvmpipe", "rootfs-wined3d", "custom-box64-test");
        RuntimeProfile hangover = new RuntimeProfile(base.getId(), "rootfs_hangover_11.16",
            base.getGraphicsDriver(), base.getDxWrapper(), base.getAudioDriver(), base.getResolution(),
            base.getBox64Preset(), base.getEnvironment(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), base.getRuntimeBackendType(), base.getRootfsPackage(), base.getContainerId());
        expect("box64_translator_mismatch:custom-box64-test", () -> CustomComponentSelection.validate(hangover, null));
    }

    @Test public void builtInVersionsPrepareOnlyTheSelectedNativeBuild() {
        for (String id : new String[] {"glibc_box64_0.2.8", "glibc_box64_0.3.8", "glibc_box64_0.4.4"}) {
            java.util.Set<String> ids = new GlibcTermuxBoxBackend().requiredComponentIds(
                profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "native-turnip-v6.5", "wined3d", id));
            assertTrue(ids.contains(id));
            assertEquals(1, ids.stream().filter(NativeBox64Versions::isBuiltIn).count());
            expect("box64_selection_unsupported:" + id, () -> CustomComponentSelection.validate(
                profile(GameRuntimeBackendType.ROOTFS_PROOT, "rootfs-llvmpipe", "rootfs-wined3d", id), null));
        }
        java.util.Set<String> defaults = new GlibcTermuxBoxBackend().requiredComponentIds(
            profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "native-turnip-v6.5", "wined3d", ""));
        assertTrue(defaults.contains("glibc_box64_0.2.8"));
        assertFalse(defaults.contains("glibc_box64_0.4.4"));
    }

    @Test public void prootBuiltInsUseOnlySelectedDependencyAndRejectNativeScope() {
        for (String boxId : new String[]{"rootfs_box64_0.2.8", "rootfs_box64_0.3.8"}) {
            RuntimeProfile selected = profile(GameRuntimeBackendType.ROOTFS_PROOT,
                "rootfs-llvmpipe", "rootfs-wined3d", boxId);
            java.util.Set<String> ids = new ProotDependencyResolver().requiredComponentIds(selected, null);
            assertTrue(ids.contains(boxId));
            assertFalse(ids.contains("rootfs_box64_0.4.4"));
            expect("box64_selection_unsupported:" + boxId, () ->
                CustomComponentSelection.validate(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                    "native-turnip-v6.5", "wined3d", boxId), null));
        }
        expect("box64_selection_unsupported:rootfs_box64_99.0", () ->
            CustomComponentSelection.validate(profile(GameRuntimeBackendType.ROOTFS_PROOT,
                "rootfs-llvmpipe", "rootfs-wined3d", "rootfs_box64_99.0"), null));
    }

    private GameStoragePaths paths() throws Exception { return new GameStoragePaths(temporary.newFolder()); }
    private static CustomRuntimeComponent component(CustomRuntimeComponent.Kind kind, GameRuntimeBackendType backend) {
        return new CustomRuntimeComponent("custom-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-test",
            kind, null, "Test", 1700000000, SHA, backend);
    }
    private static RuntimeProfile profile(GameRuntimeBackendType backend, String graphics, String dx, String box64) {
        return new RuntimeProfile("game-1", backend == GameRuntimeBackendType.ROOTFS_PROOT ?
            "rootfs_wine_10.0" : "glibc_wine_9.3-vanilla", graphics, dx, "pulseaudio", "1280x720", "INTERMEDIATE",
            box64.isEmpty() ? Collections.emptyMap() : Collections.singletonMap("GAMES_CUSTOM_BOX64_ID", box64),
            "xinput", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(), backend,
            backend == GameRuntimeBackendType.ROOTFS_PROOT ? "debian-13-games-rootfs" : "", "game-container");
    }
    static void write(File file, String content) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    private static void expect(String expected, Runnable action) {
        try { action.run(); fail("Expected " + expected); }
        catch (IllegalArgumentException error) { assertEquals(expected, error.getMessage()); }
    }
}
