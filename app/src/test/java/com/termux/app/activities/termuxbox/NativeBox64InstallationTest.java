package com.termux.app.activities.termuxbox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NativeBox64InstallationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void installingAndReinstallingVersionsNeverChangesAnotherBuildOrSharedBinary() throws Exception {
        File files = temporary.newFolder();
        TermuxBoxRepository repository = repository(files);
        write(new File(files, "usr/glibc/bin/box64"), "shared-sentinel");
        install(repository, "glibc_box64_0.2.7", "old-build");
        install(repository, "glibc_box64_0.4.4", "new-build");
        assertEquals("old-build", read(binary(files, "glibc_box64_0.2.7")));
        assertEquals("new-build", read(binary(files, "glibc_box64_0.4.4")));
        install(repository, "glibc_box64_0.4.4", "new-build-reinstalled");
        assertEquals("old-build", read(binary(files, "glibc_box64_0.2.7")));
        assertEquals("new-build-reinstalled", read(binary(files, "glibc_box64_0.4.4")));
        assertEquals("shared-sentinel", read(new File(files, "usr/glibc/bin/box64")));
        for (String id : new String[] {"glibc_box64_0.2.7", "glibc_box64_0.4.4"}) {
            assertTrue(binary(files, id).canExecute());
            File metadata = new File(files, "usr/glibc/termux-box/package-manager/installed");
            assertEquals("1\n", read(new File(metadata, id)));
            assertEquals("glibc/components/" + id + "/bin/box64\n", read(new File(metadata, id + "_lists")));
        }
    }

    @Test public void invalidPackageDoesNotDamageTheExistingBuildOrItsReceipt() throws Exception {
        File files = temporary.newFolder();
        TermuxBoxRepository repository = repository(files);
        install(repository, "glibc_box64_0.4.4", "working-build");
        File broken = temporary.newFolder();
        assertTrue(new File(broken, "glibc").mkdir());
        try {
            repository.installPackageFromPreparedDirectory(spec("glibc_box64_0.4.4"), broken, null);
            fail("Expected invalid package failure");
        } catch (IOException expected) {
            assertEquals("box64_package_binary_missing:glibc_box64_0.4.4", expected.getMessage());
        }
        assertEquals("working-build", read(binary(files, "glibc_box64_0.4.4")));
        assertEquals("1\n", read(new File(files,
            "usr/glibc/termux-box/package-manager/installed/glibc_box64_0.4.4")));
    }

    @Test public void staleSharedFileListsCannotDeleteAnotherVersionsFiles() throws Exception {
        File files = temporary.newFolder();
        TermuxBoxRepository repository = repository(files);
        install(repository, "glibc_box64_0.2.7", "old-build");
        File metadata = new File(files, "usr/glibc/termux-box/package-manager/installed");
        write(new File(metadata, "glibc_box64_0.4.4_lists"),
            "glibc/components/glibc_box64_0.2.7/bin/box64\n");
        install(repository, "glibc_box64_0.4.4", "new-build");
        assertEquals("old-build", read(binary(files, "glibc_box64_0.2.7")));
    }

    private TermuxBoxRepository repository(File files) {
        return new TermuxBoxRepository(RuntimeEnvironment.getApplication(), files);
    }

    private void install(TermuxBoxRepository repository, String id, String bytes) throws Exception {
        File source = temporary.newFolder();
        File binary = new File(source, "glibc/bin/box64");
        write(binary, bytes);
        assertTrue(binary.setExecutable(true, false));
        repository.installPackageFromPreparedDirectory(spec(id), source, null);
        assertEquals(bytes, read(binary)); // immutable source remains caller-owned
    }

    private static TermuxBoxPackageSpec spec(String id) {
        return new TermuxBoxPackageSpec(id, 1, "runtime", "https://example.invalid/box64", 1L, "");
    }

    private static File binary(File files, String id) {
        return new File(files, "usr/glibc/components/" + id + "/bin/box64");
    }

    private static void write(File file, String value) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
