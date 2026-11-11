package com.termux.ui.wine.service;

import com.termux.ui.components.ComponentDownloader;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.DownloadControl;
import com.termux.ui.components.delivery.ComponentDeliveryCoordinator;
import com.termux.ui.components.delivery.ComponentTaskListener;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.install.ArchiveFileOperations;
import com.termux.ui.components.install.ComponentInstallListener;
import com.termux.ui.components.install.ComponentInstaller;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.service.RuntimeInstallationGate;
import com.termux.ui.wine.runtime.RootfsSetupRecipe;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Collections;

import static org.junit.Assert.*;

/** Exercises the real preparation/download/install chain with same-version image replacements. */
public class RootfsRuntimeComponentPreparerTest {
    private static final String ID = RootfsSetupRecipe.IMAGE_COMPONENT;
    private static final byte[] OLD = "old larger installed rootfs".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CURRENT = "trimmed rootfs".getBytes(StandardCharsets.UTF_8);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void explicitBuildReplacesSameVersionAndNeverResumesOldFailedTask() throws Exception {
        Fixture f = new Fixture(true);
        ComponentDescriptor oldDescriptor = index(OLD).find(ID).get();
        f.tasks.save(oldDescriptor.createTask("stale-failed").transition(ComponentTaskState.FAILED,
            0, "", "", "old_download_failed", "old failure"));

        f.preparer.ensure(Collections.singleton(ID), true);

        assertEquals(1, f.enqueued);
        assertEquals(1, f.requests);
        assertEquals(2, f.active().getVersion());
        assertArrayEquals(CURRENT, Files.readAllBytes(f.payload().toPath()));
        assertArrayEquals(OLD, Files.readAllBytes(f.originalPayload.toPath()));
        assertEquals(ComponentTaskState.FAILED, f.tasks.find("stale-failed").get().getState());
        assertEquals(ComponentTaskState.INSTALLED, f.tasks.find("current-1").get().getState());
        assertTrue(new File(f.files, "front/runtime/setup/dependencies/parent/current-1").isFile());
    }

    @Test public void ordinaryPreparationNeverUpgradesExistingSameVersionImage() throws Exception {
        Fixture f = new Fixture(true);
        f.preparer.ensure(Collections.singleton(ID));
        assertEquals(0, f.enqueued);
        assertEquals(0, f.requests);
        assertEquals(f.originalPayload, f.payload());
        assertArrayEquals(OLD, Files.readAllBytes(f.payload().toPath()));
    }

    @Test public void explicitBuildReusesCurrentDownloadCache() throws Exception {
        Fixture f = new Fixture(true);
        Files.write(f.cache.toPath(), CURRENT);
        f.preparer.ensure(Collections.singleton(ID), true);
        assertEquals(1, f.enqueued);
        assertEquals(0, f.requests);
        assertArrayEquals(CURRENT, Files.readAllBytes(f.payload().toPath()));
    }

    @Test public void failedDownloadStopsWithoutReturningOldInstallationOrRetrying() throws Exception {
        Fixture f = new Fixture(true);
        f.failDownload = true;
        try {
            f.preparer.ensure(Collections.singleton(ID), true);
            fail("Old active image cannot satisfy a failed explicit build");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("component_prepare_failed:" + ID));
        }
        assertEquals(1, f.enqueued);
        assertEquals(1, f.requests);
        assertEquals(f.originalPayload, f.payload());
        assertEquals(ComponentTaskState.FAILED, f.tasks.find("current-1").get().getState());
    }

    @Test public void freshInstallDownloadsAndPublishesImage() throws Exception {
        Fixture f = new Fixture(false);
        f.preparer.ensure(Collections.singleton(ID), true);
        assertEquals(1, f.requests);
        assertArrayEquals(CURRENT, Files.readAllBytes(f.payload().toPath()));
    }

    @Test public void recoveringSameBuildKeepsItsOwnCompletedChild() throws Exception {
        Fixture f = new Fixture(true);
        f.preparer.ensure(Collections.singleton(ID), true);
        new RootfsRuntimeComponentPreparer(f.files, "parent", f.current, f)
            .ensure(Collections.singleton(ID), true);
        assertEquals(1, f.enqueued);
        assertEquals(1, f.requests);
        assertArrayEquals(CURRENT, Files.readAllBytes(f.payload().toPath()));
    }

    @Test public void recoveringFailedBuildDoesNotRetryItsOwnChild() throws Exception {
        Fixture f = new Fixture(true);
        f.failDownload = true;
        for (int recovery = 0; recovery < 2; recovery++) {
            try {
                new RootfsRuntimeComponentPreparer(f.files, "parent", f.current, f)
                    .ensure(Collections.singleton(ID), true);
                fail("A failed build must stop");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().startsWith("component_prepare_failed:" + ID));
            }
        }
        assertEquals(1, f.enqueued);
        assertEquals(1, f.requests);
    }

    private static ComponentIndex index(byte[] payload) throws Exception {
        JSONObject entry = new JSONObject(new String(Files.readAllBytes(Paths.get(
            "../distribution/termux-app-packages/prebuilt-rootfs-index-entry.json")), StandardCharsets.UTF_8));
        entry.put("size", payload.length);
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(payload)) sha.append(String.format("%02x", b & 255));
        entry.put("sha256", sha.toString());
        JSONObject root = new JSONObject().put("schemaVersion", 2).put("generatedAt", "test")
            .put("packages", new JSONArray().put(entry));
        return new ComponentIndexParser().parse(new ByteArrayInputStream(root.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private final class Fixture implements RootfsRuntimeComponentPreparer.Commands {
        final File files = temporary.newFolder();
        final File downloads = temporary.newFolder();
        final File cache = new File(downloads, ID + "-2.archive");
        final FileComponentTaskRepository tasks;
        final ComponentInstaller installer;
        final ComponentDeliveryCoordinator delivery;
        final ComponentIndex current = index(CURRENT);
        final RootfsRuntimeComponentPreparer preparer;
        File originalPayload;
        int requests, enqueued;
        boolean failDownload;

        Fixture(boolean oldInstalled) throws Exception {
            ComponentStoragePaths paths = new ComponentStoragePaths(files, downloads);
            tasks = new FileComponentTaskRepository(paths.getTasksDirectory());
            installer = new ComponentInstaller(tasks, paths.getInstallDirectory(), new FileOperations());
            delivery = new ComponentDeliveryCoordinator(tasks, new ComponentDownloader(tasks, downloads, url -> {
                requests++;
                if (failDownload) throw new IOException("network_failed");
                return new HttpURLConnection(url) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public String getHeaderField(String name) {
                        return "Content-Length".equalsIgnoreCase(name) ? String.valueOf(CURRENT.length) : null;
                    }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(CURRENT); }
                    @Override public void connect() { }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                };
            }), installer, downloads);
            if (oldInstalled) {
                Files.write(cache.toPath(), OLD);
                ComponentTask old = index(OLD).find(ID).get().createTask("old-installed")
                    .transition(ComponentTaskState.VERIFIED, OLD.length, "", "", "", "");
                tasks.save(old);
                installer.install(old, cache, DownloadControl.CONTINUE, ComponentInstallListener.NONE);
                originalPayload = payload();
            }
            preparer = new RootfsRuntimeComponentPreparer(files, "parent", current, this);
        }

        InstalledComponent active() throws Exception { return installer.findActive(ID).get(); }
        File payload() throws Exception {
            return new File(active().getDirectory(), current.find(ID).get().getUrl().substring(
                current.find(ID).get().getUrl().lastIndexOf('/') + 1));
        }
        @Override public String enqueue(String id) throws IOException {
            String taskId = "current-" + ++enqueued;
            RuntimeInstallationGate.registerDependency(files, "parent", taskId);
            delivery.enqueue(current.find(id).get(), taskId);
            try { delivery.execute(taskId, ComponentTaskListener.NONE); }
            catch (IOException failed) { /* The preparer must observe and reject the durable FAILED task. */ }
            return taskId;
        }
        @Override public void resume(String id, boolean retry) { fail("Must not reuse old tasks for explicit builds"); }
        @Override public void reconcile() { fail("No incomplete current task in fixture"); }
    }

    private static final class FileOperations implements ArchiveFileOperations {
        @Override public void createSymbolicLink(String target, File link) throws IOException {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        }
        @Override public void createHardLink(File target, File link) throws IOException {
            Files.createLink(link.toPath(), target.toPath());
        }
        @Override public void applyMode(File file, int mode) { }
        @Override public boolean isSymbolicLink(File file) { return Files.isSymbolicLink(file.toPath()); }
    }
}
