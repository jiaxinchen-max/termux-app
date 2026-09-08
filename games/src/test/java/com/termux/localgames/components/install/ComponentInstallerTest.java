package com.termux.localgames.components.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.components.DownloadControl;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

public class ComponentInstallerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stagesTwoImmutableVersionsAndRollsBackPointer() throws Exception {
        Fixture fixture = fixture();
        File firstArchive = archive("first.tar.xz", "bin/runtime", "v1");
        ComponentTask first = fixture.verified("task-1", 1, firstArchive);

        InstalledComponent firstInstalled = fixture.installer.install(first, firstArchive,
            ComponentInstallListener.NONE);

        assertEquals("v1", text(new File(firstInstalled.getDirectory(), "bin/runtime")));
        assertEquals(ComponentTaskState.INSTALLED,
            fixture.repository.find("task-1").get().getState());

        File secondArchive = archive("second.tar.xz", "bin/runtime", "v2");
        ComponentTask second = fixture.verified("task-2", 2, secondArchive);
        InstalledComponent secondInstalled = fixture.installer.install(second, secondArchive,
            ComponentInstallListener.NONE);
        assertEquals(2, fixture.installer.findActive("runtime").get().getVersion());
        assertFalse(firstInstalled.getDirectory().equals(secondInstalled.getDirectory()));

        InstalledComponent rolledBack = fixture.installer.rollback("runtime");

        assertEquals(1, rolledBack.getVersion());
        assertEquals(1, fixture.installer.findActive("runtime").get().getVersion());
        assertTrue(secondInstalled.getDirectory().isDirectory());
    }

    @Test
    public void installsVerifiedPlainTarSourceComponent() throws Exception {
        Fixture fixture = fixture();
        File archive = plainTar("upstream.tar", "packages/runtime.deb", "deb");
        ComponentTask task = fixture.verified("task-source", 1, archive);

        InstalledComponent installed = fixture.installer.install(task, archive,
            ComponentInstallListener.NONE);

        assertEquals("deb", text(new File(installed.getDirectory(), "packages/runtime.deb")));
        assertEquals(ComponentTaskState.INSTALLED,
            fixture.repository.find("task-source").get().getState());
    }

    @Test
    public void traversalFailurePreservesPreviousActiveVersion() throws Exception {
        Fixture fixture = fixture();
        File firstArchive = archive("first.tar.xz", "bin/runtime", "v1");
        fixture.installer.install(fixture.verified("task-1", 1, firstArchive), firstArchive,
            ComponentInstallListener.NONE);
        File malicious = archive("malicious.tar.xz", "../escape", "owned");
        ComponentTask second = fixture.verified("task-2", 2, malicious);

        try {
            fixture.installer.install(second, malicious, ComponentInstallListener.NONE);
            fail("path traversal must fail");
        } catch (ComponentInstallException expected) {
            assertEquals("path_traversal", expected.getErrorCode());
        }

        assertEquals(1, fixture.installer.findActive("runtime").get().getVersion());
        assertEquals(ComponentTaskState.FAILED,
            fixture.repository.find("task-2").get().getState());
        assertFalse(new File(fixture.root, "escape").exists());
    }

    @Test
    public void changedArchiveFailsBeforePointerMutation() throws Exception {
        Fixture fixture = fixture();
        File archive = archive("runtime.tar.xz", "bin/runtime", "v1");
        ComponentTask task = fixture.verified("task-1", 1, archive);
        try (FileOutputStream output = new FileOutputStream(archive, true)) {
            output.write(1);
        }

        try {
            fixture.installer.install(task, archive, ComponentInstallListener.NONE);
            fail("changed verified archive must fail");
        } catch (ComponentInstallException expected) {
            assertEquals("archive_size_mismatch", expected.getErrorCode());
        }

        assertFalse(fixture.installer.findActive("runtime").isPresent());
        assertEquals(ComponentTaskState.FAILED,
            fixture.repository.find("task-1").get().getState());
    }

    @Test
    public void pauseDuringStagingCleansStagingAndPersistsPause() throws Exception {
        Fixture fixture = fixture();
        File archive = archive("runtime.tar.xz", "bin/runtime", "v1");
        ComponentTask task = fixture.verified("task-1", 1, archive);

        try {
            fixture.installer.install(task, archive, () -> DownloadControl.Decision.PAUSE,
                ComponentInstallListener.NONE);
            fail("pause must interrupt staging");
        } catch (InstallInterruptedException expected) {
            assertEquals(DownloadControl.Decision.PAUSE, expected.getDecision());
        }

        assertEquals(ComponentTaskState.PAUSED,
            fixture.repository.find("task-1").get().getState());
        assertFalse(new File(fixture.installRoot, ".staging/task-1").exists());
        assertFalse(fixture.installer.findActive("runtime").isPresent());
    }

    private Fixture fixture() throws Exception {
        File root = temporaryFolder.newFolder();
        FileComponentTaskRepository repository = new FileComponentTaskRepository(
            new File(root, "tasks"));
        File installRoot = new File(root, "install");
        ComponentInstaller installer = new ComponentInstaller(repository, installRoot,
            new JvmArchiveFileOperations(), 100, 1024 * 1024);
        return new Fixture(root, installRoot, repository, installer);
    }

    private File archive(String name, String path, String value) throws Exception {
        File file = new File(temporaryFolder.getRoot(), name);
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOutput = new FileOutputStream(file);
             XZOutputStream xz = new XZOutputStream(fileOutput, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setMode(0755);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return file;
    }

    private File plainTar(String name, String path, String value) throws Exception {
        File file = new File(temporaryFolder.getRoot(), name);
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOutput = new FileOutputStream(file);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(fileOutput)) {
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setMode(0644);
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return file;
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte item : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static final class Fixture {
        final File root;
        final File installRoot;
        final FileComponentTaskRepository repository;
        final ComponentInstaller installer;

        Fixture(File root, File installRoot, FileComponentTaskRepository repository,
                ComponentInstaller installer) {
            this.root = root;
            this.installRoot = installRoot;
            this.repository = repository;
            this.installer = installer;
        }

        ComponentTask verified(String taskId, int version, File archive) throws Exception {
            ComponentTask task = ComponentTask.queued(taskId, "runtime", version,
                "https://example.invalid/runtime.tar.xz", archive.length(), sha256(archive))
                .transition(ComponentTaskState.VERIFIED, archive.length(), "", "", "", "");
            repository.save(task);
            return task;
        }
    }

    private static final class JvmArchiveFileOperations implements ArchiveFileOperations {
        @Override
        public void createSymbolicLink(String target, File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        }

        @Override
        public void createHardLink(File target, File link) throws IOException {
            Files.createLink(link.toPath(), target.toPath());
        }

        @Override
        public void applyMode(File file, int mode) {
            // JVM tests validate content and paths; Android Os.chmod owns mode fidelity.
        }

        @Override
        public boolean isSymbolicLink(File file) {
            return Files.isSymbolicLink(file.toPath());
        }
    }
}
