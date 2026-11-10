package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.ProotComponentBinding;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.domain.LaunchExecutionMode;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProotBindingResolverTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));
    private static final String ID = "rootfs_dxvk_3.1";
    private final RuntimeProfile profile = new RuntimeProfile("game", "rootfs_hangover_11.16",
        "rootfs-llvmpipe", ID, "pulseaudio", "1280x720", "INTERMEDIATE",
        Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
        Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT,
        "debian-13-games-rootfs", "container");

    @Test public void activeUpdateDoesNotRedirectFrozenVersion() throws Exception {
        File install = temp.newFolder("install $' =");
        File old = version(install, "v1-aaaa", 1, SHA);
        pointer(install, "v1-aaaa");
        ProotBindingResolver resolver = new ProotBindingResolver(install);
        List<ProotComponentBinding> bindings = resolver.freeze(profile, null);
        version(install, "v2-bbbb", 2, SHA.replace('a', 'b'));
        pointer(install, "v2-bbbb");
        resolver.validate(bindings);
        assertEquals(2, bindings.size());
        assertEquals(old.getCanonicalPath(), bindings.get(0).getVersionDirectory());
        assertEquals("dxvk/x64", bindings.get(0).getContentRelativePath());
        assertEquals("dxvk/x32", bindings.get(1).getContentRelativePath());
        assertEquals(1, bindings.get(0).getVersion());
    }
    @Test public void explicitReplacementRefreshesPreparedBindingsWithoutTouchingPrefix() throws Exception {
        File files = temp.newFolder();
        com.termux.ui.data.GameStoragePaths paths = new com.termux.ui.data.GameStoragePaths(files);
        File install = new com.termux.ui.components.ComponentStoragePaths(files).getInstallDirectory();
        version(install, "v1-aaaa", 1, SHA); pointer(install, "v1-aaaa");
        ProotBindingResolver resolver = new ProotBindingResolver(install);
        ProotPreparedRuntime.configurations(paths).write("container", new com.termux.ui.wine.domain.ProotBindingSnapshot(
            profile.getWinePackage(), ID, com.termux.ui.wine.domain.RuntimeTranslator.HANGOVER, "", SHA,
            resolver.freeze(profile, null)));
        File prefix = new File(files, "front/wine/preserved.save"); prefix.getParentFile().mkdirs();
        Files.write(prefix.toPath(), new byte[]{7});
        File replacement = version(install, "same-version-fresh", 1, SHA); pointer(install, "same-version-fresh");
        new com.termux.ui.components.install.ComponentMaintenance(files).refreshProotBindings(ID);
        List<ProotComponentBinding> bindings = ProotPreparedRuntime.configurations(paths).read("container").getBindings();
        assertEquals(2, bindings.size());
        assertEquals(replacement.getCanonicalPath(), bindings.get(0).getVersionDirectory());
        resolver.validate(bindings);
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(prefix.toPath()));
    }
    @Test(expected = IOException.class) public void missingFrozenVersionFailsRatherThanFollowingActive() throws Exception {
        File install = temp.newFolder();
        File old = version(install, "v1-aaaa", 1, SHA);
        pointer(install, "v1-aaaa");
        ProotBindingResolver resolver = new ProotBindingResolver(install);
        List<ProotComponentBinding> bindings = resolver.freeze(profile, null);
        version(install, "v2-bbbb", 2, SHA.replace('a', 'b'));
        pointer(install, "v2-bbbb");
        Files.move(old.toPath(), old.toPath().resolveSibling("removed"));
        resolver.validate(bindings);
    }
    @Test(expected = IOException.class) public void rejectsPayloadSymlinkEscape() throws Exception {
        File install = temp.newFolder();
        File old = version(install, "v1-aaaa", 1, SHA);
        pointer(install, "v1-aaaa");
        Files.createSymbolicLink(new File(old, "dxvk/x64/external.dll").toPath(), temp.newFile().toPath());
        new ProotBindingResolver(install).freeze(profile, null);
    }
    @Test(expected = IOException.class) public void rejectsAmbiguousWrapperRoots() throws Exception {
        File install = temp.newFolder();
        File old = version(install, "v1-aaaa", 1, SHA);
        pointer(install, "v1-aaaa");
        File duplicate = new File(old, "other/x64/d3d11.dll");
        assertTrue(duplicate.getParentFile().mkdirs());
        assertTrue(duplicate.createNewFile());
        new ProotBindingResolver(install).freeze(profile, null);
    }
    @Test public void builtInBox64BindingPreservesVersionThroughStorageAndReinstall() throws Exception {
        for (String boxId : new String[]{"rootfs_box64_0.2.8", "rootfs_box64_0.3.8"}) {
            File files = temp.newFolder();
            com.termux.ui.data.GameStoragePaths paths = new com.termux.ui.data.GameStoragePaths(files);
            File install = new com.termux.ui.components.ComponentStoragePaths(files).getInstallDirectory();
            File wine = installPayload(install, "rootfs_wine_10.0", "one", "bin/wine64");
            File box = installPayload(install, boxId, "one", "bin/box64");
            ProotBindingResolver resolver = new ProotBindingResolver(install);
            com.termux.ui.components.install.ComponentInstallationReader reader =
                new com.termux.ui.components.install.ComponentInstallationReader(install);
            java.util.List<ProotComponentBinding> bindings = new java.util.ArrayList<>();
            bindings.addAll(resolver.bind(reader.read("rootfs_wine_10.0").getActive().get()));
            bindings.addAll(resolver.bind(reader.read(boxId).getActive().get()));
            com.termux.ui.wine.data.ProotBindingStore store = ProotPreparedRuntime.configurations(paths);
            store.write("box038", new com.termux.ui.wine.domain.ProotBindingSnapshot("rootfs_wine_10.0",
                "rootfs-wined3d", com.termux.ui.wine.domain.RuntimeTranslator.BOX64, boxId, SHA, bindings));
            assertEquals(boxId, store.read("box038").getBox64ComponentId());
            assertEquals("/opt/box64/" + boxId, store.read("box038").getBindings().get(1).getGuestTarget());
            assertEquals(box.getCanonicalPath(), store.read("box038").getBindings().get(1).getVersionDirectory());
            resolver.validate(store.read("box038").getBindings());
            File replacement = installPayload(install, boxId, "reinstalled", "bin/box64");
            new com.termux.ui.components.install.ComponentMaintenance(files).refreshProotBindings(boxId);
            assertEquals(replacement.getCanonicalPath(), store.read("box038").getBindings().get(1).getVersionDirectory());
            assertEquals(wine.getCanonicalPath(), store.read("box038").getBindings().get(0).getVersionDirectory());
            resolver.validate(store.read("box038").getBindings());
        }
    }
    private File installPayload(File install, String id, String name, String binary) throws Exception {
        File dir = new File(install, id + "/versions/" + name);
        File payload = new File(dir, binary); assertTrue(payload.getParentFile().mkdirs());
        Files.write(payload.toPath(), new byte[]{1}); assertTrue(payload.setExecutable(true));
        Properties receipt = new Properties(); receipt.setProperty("schemaVersion", "1");
        receipt.setProperty("packageName", id); receipt.setProperty("version", "1"); receipt.setProperty("sha256", SHA);
        store(new File(dir, ".wine-component.properties"), receipt);
        Properties pointer = new Properties(); pointer.setProperty("schemaVersion", "1");
        pointer.setProperty("active", name); pointer.setProperty("previous", "");
        store(new File(install, id + "/active.properties"), pointer);
        return dir;
    }
    private File version(File root, String name, int version, String sha) throws Exception {
        File dir = new File(root, ID + "/versions/" + name);
        for (String relative : new String[]{"dxvk/x64/d3d11.dll", "dxvk/x32/d3d11.dll"}) {
            File f = new File(dir, relative);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new byte[]{1});
        }
        Properties receipt = new Properties();
        receipt.setProperty("schemaVersion", "1"); receipt.setProperty("packageName", ID);
        receipt.setProperty("version", Integer.toString(version)); receipt.setProperty("sha256", sha);
        store(new File(dir, ".wine-component.properties"), receipt);
        return dir;
    }
    private void pointer(File root, String name) throws Exception {
        Properties p = new Properties(); p.setProperty("schemaVersion", "1");
        p.setProperty("active", name); p.setProperty("previous", "");
        store(new File(root, ID + "/active.properties"), p);
    }
    private void store(File file, Properties p) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { p.store(out, "test"); }
    }
}
