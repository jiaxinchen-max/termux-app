package com.termux.ui.data;

import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CustomComponentInstallationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void requiresReceiptAndBothArchitectures() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder());
        CustomRuntimeComponent entry = dxvk();
        File root = CustomComponentInstallation.directory(paths, entry);
        assertFalse(CustomComponentInstallation.isAvailable(paths, entry));
        publish(root, entry);
        assertTrue(CustomComponentInstallation.isAvailable(paths, entry));
        assertTrue(new File(root, "x32/dxgi.dll").delete());
        assertFalse(CustomComponentInstallation.isAvailable(paths, entry));
    }

    @Test public void rejectsAnotherBackendButDoesNotGateOnArchiveDigest() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder());
        CustomRuntimeComponent entry = dxvk();
        File root = CustomComponentInstallation.directory(paths, entry);
        publish(root, entry);
        File receipt = new File(root, ".custom-component.properties");
        String valid = new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8);
        write(receipt, valid.replace("rootfs_proot", "glibc_termux_box"));
        assertFalse(CustomComponentInstallation.isAvailable(paths, entry));
        write(receipt, valid.replace(entry.getSha256(), new String(new char[64]).replace('\0', 'b')));
        assertTrue(CustomComponentInstallation.isAvailable(paths, entry));
    }

    @Test public void payloadSymlinkCannotEscapePublishedVersion() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder());
        CustomRuntimeComponent entry = dxvk();
        File root = CustomComponentInstallation.directory(paths, entry);
        publish(root, entry);
        File dll = new File(root, "x64/dxgi.dll");
        assertTrue(dll.delete());
        File outside = temporary.newFile();
        write(outside, "x");
        Files.createSymbolicLink(dll.toPath(), outside.toPath());
        assertFalse(CustomComponentInstallation.isAvailable(paths, entry));
    }

    private CustomRuntimeComponent dxvk() {
        return new CustomRuntimeComponent("custom-dxvk-proot-test", CustomRuntimeComponent.Kind.DXVK,
            null, "test", 1, new String(new char[64]).replace('\0','a'), GameRuntimeBackendType.ROOTFS_PROOT);
    }
    private void publish(File root, CustomRuntimeComponent entry) throws Exception {
        for (String arch : new String[]{"x32", "x64"}) for (String name : new String[]{"dxgi.dll", "d3d11.dll"}) write(new File(root, arch+"/"+name), "x");
        write(new File(root, ".custom-component.properties"), "schemaVersion=1\nruntimeBackend=rootfs_proot\nkind=dxvk\nruntimeTranslator=none\ncomponentId="+entry.getId()+"\nsha256="+entry.getSha256()+"\n");
    }
    private void write(File file, String text) throws Exception {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
}
