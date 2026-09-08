package com.termux.localgames.components.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Properties;

public class ComponentInstallationReaderTest {

    private static final String SHA_A =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingPointerIsNotInstalled() throws Exception {
        ComponentInstallationSnapshot snapshot = new ComponentInstallationReader(
            temporaryFolder.newFolder("install")).read("runtime");

        assertFalse(snapshot.getActive().isPresent());
        assertFalse(snapshot.getPrevious().isPresent());
    }

    @Test
    public void readsValidatedActiveAndPreviousReceipts() throws Exception {
        File root = temporaryFolder.newFolder("install");
        writeReceipt(root, "runtime", "v2-bbbbbbbbbbbb", 2, SHA_B);
        writeReceipt(root, "runtime", "v1-aaaaaaaaaaaa", 1, SHA_A);
        writePointer(root, "runtime", "v2-bbbbbbbbbbbb", "v1-aaaaaaaaaaaa");

        ComponentInstallationSnapshot snapshot =
            new ComponentInstallationReader(root).read("runtime");

        assertEquals(2, snapshot.getActive().get().getVersion());
        assertEquals(1, snapshot.getPrevious().get().getVersion());
        assertTrue(snapshot.getActive().get().getDirectory().isDirectory());
    }

    @Test
    public void readsBackupPointerAfterInterruptedAtomicReplacement() throws Exception {
        File root = temporaryFolder.newFolder("install-backup");
        writeReceipt(root, "runtime", "v1-aaaaaaaaaaaa", 1, SHA_A);
        writePointer(root, "runtime", "v1-aaaaaaaaaaaa", "");
        File pointer = new File(new File(root, "runtime"), "active.properties");
        assertTrue(pointer.renameTo(new File(pointer.getPath() + ".bak")));

        ComponentInstallationSnapshot snapshot =
            new ComponentInstallationReader(root).read("runtime");

        assertEquals(1, snapshot.getActive().get().getVersion());
    }

    @Test
    public void rejectsPointerTraversalAndReceiptPackageMismatch() throws Exception {
        File root = temporaryFolder.newFolder("install");
        writePointer(root, "runtime", "../outside", "");
        expectInvalid(root);

        root = temporaryFolder.newFolder("install-mismatch");
        writeReceipt(root, "runtime", "v1-aaaaaaaaaaaa", 1, SHA_A, "other");
        writePointer(root, "runtime", "v1-aaaaaaaaaaaa", "");
        expectInvalid(root);
    }

    private static void expectInvalid(File root) throws Exception {
        try {
            new ComponentInstallationReader(root).read("runtime");
            fail("invalid installation metadata must fail");
        } catch (ComponentInstallException expected) {
            assertEquals("invalid_install_metadata", expected.getErrorCode());
        }
    }

    private static void writePointer(File root, String packageName, String active,
                                     String previous) throws Exception {
        Properties value = new Properties();
        value.setProperty("schemaVersion", "1");
        value.setProperty("active", active);
        value.setProperty("previous", previous);
        write(new File(new File(root, packageName), "active.properties"), value);
    }

    private static void writeReceipt(File root, String packageName, String directory,
                                     int version, String sha256) throws Exception {
        writeReceipt(root, packageName, directory, version, sha256, packageName);
    }

    private static void writeReceipt(File root, String packageName, String directory,
                                     int version, String sha256,
                                     String receiptPackage) throws Exception {
        Properties value = new Properties();
        value.setProperty("schemaVersion", "1");
        value.setProperty("packageName", receiptPackage);
        value.setProperty("version", String.valueOf(version));
        value.setProperty("sha256", sha256);
        write(new File(new File(new File(new File(root, packageName), "versions"), directory),
            ".games-component.properties"), value);
    }

    private static void write(File file, Properties value) throws Exception {
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            throw new IllegalStateException("unable to create test directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            value.store(output, "test");
        }
    }
}
