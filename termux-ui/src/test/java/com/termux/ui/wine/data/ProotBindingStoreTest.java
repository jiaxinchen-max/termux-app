package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.ProotBindingSnapshot;
import com.termux.ui.wine.domain.ProotComponentBinding;
import com.termux.ui.wine.domain.RuntimeTranslator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProotBindingStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SHA = String.join("", Collections.nCopies(64, "a"));
    private ProotBindingSnapshot snapshot() {
        return new ProotBindingSnapshot("rootfs_wine_10.0", "rootfs-wined3d", RuntimeTranslator.BOX64,
            "", SHA, Arrays.asList(
                new ProotComponentBinding("rootfs_wine_10.0", 1, SHA,
                    "/private/install/rootfs_wine_10.0/versions/v1-aaaa", "wine $' = 目录",
                    "/opt/box64-wine", ProotComponentBinding.Role.WINE),
                new ProotComponentBinding("rootfs_box64_0.4.5", 2, SHA,
                    "/private/install/rootfs_box64_0.4.5/versions/v2-aaaa", "",
                    "/opt/box64/rootfs_box64_0.4.5", ProotComponentBinding.Role.BOX64)));
    }
    @Test public void exactIdentityRoundTripsWithoutAnActivePointer() throws Exception {
        File root = temp.newFolder();
        ProotBindingStore store = new ProotBindingStore(root);
        store.write("c", snapshot());
        ProotBindingSnapshot actual = store.read("c");
        assertEquals("rootfs_wine_10.0", actual.getWinePackage());
        assertEquals(2, actual.getBindings().get(1).getVersion());
        assertEquals("wine $' = 目录", actual.getBindings().get(0).getContentRelativePath());
        // The temporary file from an interrupted replacement is never consumed.
        Files.write(new File(root, "c.interrupted.tmp").toPath(), new byte[]{0});
        assertEquals(SHA, store.read("c").getBindings().get(0).getSha256());
    }
    @Test(expected = IOException.class) public void missingSnapshotRequiresPreparation() throws Exception {
        new ProotBindingStore(temp.newFolder()).read("container");
    }
    @Test(expected = IOException.class) public void extraFieldsCannotOverrideFrozenIdentity() throws Exception {
        File root = temp.newFolder();
        ProotBindingStore store = new ProotBindingStore(root);
        store.write("container", snapshot());
        Files.write(new File(root, "container.bindings").toPath(), "active=new\n".getBytes(StandardCharsets.US_ASCII),
            java.nio.file.StandardOpenOption.APPEND);
        store.read("container");
    }
    @Test(expected = IllegalArgumentException.class) public void portableWineWithoutTranslatorIsNotReady() {
        new ProotBindingSnapshot("rootfs_wine_10.0", "rootfs-wined3d", RuntimeTranslator.BOX64,
            "", SHA, Collections.singletonList(snapshot().getBindings().get(0)));
    }
    @Test(expected = IllegalArgumentException.class) public void defaultHangoverCannotMasqueradeAsBox64() {
        new ProotBindingSnapshot("rootfs_hangover_11.16", "rootfs-wined3d", RuntimeTranslator.BOX64,
            "", SHA, Collections.emptyList());
    }
    @Test(expected = IllegalArgumentException.class) public void wined3dCannotSilentlyCarryUnselectedDxvk() {
        new ProotBindingSnapshot("rootfs_hangover_11.16", "rootfs_dxvk_3.1", RuntimeTranslator.HANGOVER,
            "", SHA, Collections.emptyList());
    }
}
