package com.termux.localgames.components.install;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime packages are built for a fixed install prefix and ship absolute symbolic links that
 * only resolve once installed there, so the installer must accept those targets without
 * weakening the guard for anything else.
 */
public class ComponentInstallerRuntimeLinkTest {

    private static final String RUNTIME_ROOT = "/data/data/com.termux/files/usr/";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsPackageWhoseLinksPointAtTheRuntimePrefix() throws Exception {
        File archive = archiveWithSymlink("glibc-prefix.tar.xz",
            "glibc/lib/libc.so.6", RUNTIME_ROOT + "glibc/lib/libc-2.38.so");
        Fixture fixture = fixture(Collections.singletonList(RUNTIME_ROOT));

        InstalledComponent installed = install(fixture, archive, "glibc-prefix");

        assertEquals("glibc-prefix", installed.getPackageName());
        assertEquals(ComponentTaskState.INSTALLED,
            fixture.repository.find(taskId()).get().getState());
    }

    @Test
    public void rejectsAbsoluteLinkOutsideTheAllowedPrefix() throws Exception {
        File archive = archiveWithSymlink("evil.tar.xz",
            "glibc/lib/passwd", "/etc/passwd");
        Fixture fixture = fixture(Collections.singletonList(RUNTIME_ROOT));

        expectFailure(fixture, archive, "glibc-prefix", "link_traversal");
    }

    @Test
    public void rejectsAbsoluteLinkWhenNoPrefixIsAllowed() throws Exception {
        File archive = archiveWithSymlink("strict.tar.xz",
            "glibc/lib/libc.so.6", RUNTIME_ROOT + "glibc/lib/libc-2.38.so");
        Fixture fixture = fixture(Collections.emptyList());

        expectFailure(fixture, archive, "glibc-prefix", "link_traversal");
    }

    @Test
    public void stillRejectsRelativeLinkEscapingTheStagingRoot() throws Exception {
        File archive = archiveWithSymlink("escape.tar.xz",
            "glibc/lib/escape", "../../../../etc/passwd");
        Fixture fixture = fixture(Collections.singletonList(RUNTIME_ROOT));

        expectFailure(fixture, archive, "glibc-prefix", "link_traversal");
    }

    @Test
    public void failsTheTaskWhenTheComponentCannotReachTheLauncherRuntime() throws Exception {
        File archive = archiveWithSymlink("glibc-prefix.tar.xz",
            "glibc/lib/libc.so.6", RUNTIME_ROOT + "glibc/lib/libc-2.38.so");
        Fixture fixture = fixture(Collections.singletonList(RUNTIME_ROOT));
        fixture.installer.setRuntimeComponentActivator((task, directory) -> {
            throw new ComponentInstallException("runtime_activation_failed", "no runtime");
        });

        expectFailure(fixture, archive, "glibc-prefix", "runtime_activation_failed");
    }

    @Test
    public void handsTheVerifiedArchiveToTheActivator() throws Exception {
        File archive = archiveWithSymlink("glibc-prefix.tar.xz",
            "glibc/lib/libc.so.6", RUNTIME_ROOT + "glibc/lib/libc-2.38.so");
        Fixture fixture = fixture(Collections.singletonList(RUNTIME_ROOT));
        List<String> activated = new ArrayList<>();
        fixture.installer.setRuntimeComponentActivator((task, directory) -> {
            assertTrue("activator must receive the verified prepared directory",
                directory.isDirectory());
            assertTrue("prepared payload must be available",
                new File(directory, "glibc/bin/box64").isFile());
            activated.add(task.getPackageName());
        });

        install(fixture, archive, "glibc-prefix");

        assertEquals(Collections.singletonList("glibc-prefix"), activated);
    }

    private InstalledComponent install(Fixture fixture, File archive, String packageName)
        throws Exception {
        ComponentTask task = queued(archive, packageName);
        fixture.repository.save(task);
        return fixture.installer.install(task, archive, updated -> { });
    }

    private void expectFailure(Fixture fixture, File archive, String packageName,
                               String expectedCode) throws Exception {
        try {
            install(fixture, archive, packageName);
            fail("install must not report success for " + expectedCode);
        } catch (ComponentInstallException expected) {
            assertEquals(expectedCode, expected.getErrorCode());
        }
        ComponentTask stored = fixture.repository.find(taskId()).get();
        assertEquals(ComponentTaskState.FAILED, stored.getState());
        assertEquals(expectedCode, stored.getErrorCode());
        assertFalse("failed installs must not publish a version",
            new File(new File(fixture.installRoot, packageName), "active.properties").isFile());
    }

    private static String taskId() {
        return "task-runtime-link";
    }

    private ComponentTask queued(File archive, String packageName) throws Exception {
        return ComponentTask.queued(taskId(), packageName, 1,
            "https://example.invalid/" + packageName + ".tar.xz",
            archive.length(), sha256(archive));
    }

    private Fixture fixture(List<String> allowedPrefixes) throws Exception {
        File root = temporaryFolder.newFolder();
        FileComponentTaskRepository repository = new FileComponentTaskRepository(
            new File(root, "tasks"));
        File installRoot = new File(root, "install");
        ComponentInstaller installer = new ComponentInstaller(repository, installRoot,
            new JvmArchiveFileOperations(), allowedPrefixes);
        return new Fixture(installRoot, repository, installer);
    }

    private File archiveWithSymlink(String name, String linkPath, String target)
        throws Exception {
        File file = new File(temporaryFolder.newFolder(), name);
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        try (OutputStream fileOutput = new FileOutputStream(file);
             XZOutputStream xz = new XZOutputStream(fileOutput, new LZMA2Options());
             TarArchiveOutputStream tar = new TarArchiveOutputStream(xz)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry regular = new TarArchiveEntry("glibc/bin/box64");
            regular.setMode(0755);
            regular.setSize(content.length);
            tar.putArchiveEntry(regular);
            tar.write(content);
            tar.closeArchiveEntry();

            TarArchiveEntry link = new TarArchiveEntry(linkPath, TarArchiveEntry.LF_SYMLINK);
            link.setLinkName(target);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return file;
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
        final File installRoot;
        final FileComponentTaskRepository repository;
        final ComponentInstaller installer;

        Fixture(File installRoot, FileComponentTaskRepository repository,
                ComponentInstaller installer) {
            this.installRoot = installRoot;
            this.repository = repository;
            this.installer = installer;
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
