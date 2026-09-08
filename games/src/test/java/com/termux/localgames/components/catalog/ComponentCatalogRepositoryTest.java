package com.termux.localgames.components.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public class ComponentCatalogRepositoryTest {

    private static final String SHA =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OLD_SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void emptyStorageMapsEveryDescriptorToNotInstalled() throws Exception {
        Fixture fixture = fixture();

        List<ComponentCatalogItem> items = fixture.catalog.load();

        assertEquals(2, items.size());
        assertEquals(ComponentCatalogState.NOT_INSTALLED, items.get(0).getState());
        assertEquals(ComponentCatalogState.NOT_INSTALLED, items.get(1).getState());
    }

    @Test
    public void activeTaskRestoresProgressAndStableTaskId() throws Exception {
        Fixture fixture = fixture();
        ComponentTask task = ComponentTask.queued("task-1", "runtime", 2,
            "https://example.invalid/runtime.tar.xz", 100, SHA)
            .transition(ComponentTaskState.DOWNLOADING, 37, "\"v2\"", "", "", "");
        fixture.tasks.save(task);

        ComponentCatalogItem item = fixture.catalog.load().get(0);

        assertEquals(ComponentCatalogState.DOWNLOADING, item.getState());
        assertEquals(37, item.getProgressPercent());
        assertEquals("task-1", item.getTask().get().getTaskId());
    }

    @Test
    public void failedTaskWinsOverOldInstallationAndKeepsErrorEvidence() throws Exception {
        Fixture fixture = fixture();
        fixture.install("runtime", "v1-aaaaaaaaaaaa", 1, OLD_SHA, "");
        ComponentTask failed = ComponentTask.queued("task-2", "runtime", 2,
            "https://example.invalid/runtime.tar.xz", 100, SHA)
            .transition(ComponentTaskState.FAILED, 40, "", "", "network", "offline");
        fixture.tasks.save(failed);

        ComponentCatalogItem item = fixture.catalog.load().get(0);

        assertEquals(ComponentCatalogState.FAILED, item.getState());
        assertEquals(1, item.getActive().get().getVersion());
        assertEquals("offline", item.getTask().get().getErrorMessage());
    }

    @Test
    public void activeOldVersionShowsUpdateAndPreviousEnablesRollback() throws Exception {
        Fixture fixture = fixture();
        fixture.install("runtime", "v1-aaaaaaaaaaaa", 1, OLD_SHA,
            "v0-bbbbbbbbbbbb");
        fixture.receipt("runtime", "v0-bbbbbbbbbbbb", 1,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        ComponentCatalogItem item = fixture.catalog.load().get(0);

        assertEquals(ComponentCatalogState.UPDATE_AVAILABLE, item.getState());
        assertTrue(item.getPrevious().isPresent());
        assertFalse(item.getTask().isPresent());
    }

    @Test
    public void currentActiveInstallationWinsOverStaleFailedAttempt() throws Exception {
        Fixture fixture = fixture();
        fixture.install("runtime", "v2-0123456789ab", 2, SHA, "");
        fixture.tasks.save(ComponentTask.queued("task-old", "runtime", 2,
            "https://example.invalid/runtime.tar.xz", 100, SHA)
            .transition(ComponentTaskState.FAILED, 10, "", "", "network", "offline"));

        ComponentCatalogItem item = fixture.catalog.load().get(0);

        assertEquals(ComponentCatalogState.INSTALLED, item.getState());
        assertEquals(2, item.getActive().get().getVersion());
    }

    private Fixture fixture() throws Exception {
        ComponentIndex index = new ComponentIndexParser().parse(new ByteArrayInputStream((
            "{\"schemaVersion\":2,\"generatedAt\":\"now\",\"packages\":[" +
                descriptor("runtime", "runtime", 2) + "," +
                descriptor("wine", "wine", 1) + "]}")
            .getBytes(StandardCharsets.UTF_8)));
        InMemoryTasks tasks = new InMemoryTasks();
        File installRoot = temporaryFolder.newFolder();
        return new Fixture(tasks, installRoot, new ComponentCatalogRepository(index, tasks,
            new ComponentInstallationReader(installRoot)));
    }

    private static String descriptor(String id, String category, int version) {
        String type = "wine".equals(category) ? "container" : "runtime_support";
        return "{\"id\":\"" + id + "\",\"category\":\"" + category +
            "\",\"type\":\"" + type + "\",\"displayName\":\"Component " + id +
            "\",\"versionName\":\"v" + version + "\",\"summary\":\"test component\"," +
            "\"framework\":\"Termux GLIBC\",\"base\":false,\"recommended\":false," +
            "\"profileValue\":\"" + ("wine".equals(category) ? id : "") + "\"," +
            "\"runtimeBackends\":[\"glibc_termux_box\"],\"version\":" + version +
            ",\"url\":\"https://example.invalid/" + id + ".tar.xz\"," +
            "\"size\":100,\"sha256\":\"" + SHA + "\"}";
    }

    private static final class Fixture {
        final InMemoryTasks tasks;
        final File installRoot;
        final ComponentCatalogRepository catalog;

        Fixture(InMemoryTasks tasks, File installRoot, ComponentCatalogRepository catalog) {
            this.tasks = tasks;
            this.installRoot = installRoot;
            this.catalog = catalog;
        }

        void install(String packageName, String active, int version, String sha,
                     String previous) throws Exception {
            receipt(packageName, active, version, sha);
            Properties pointer = new Properties();
            pointer.setProperty("schemaVersion", "1");
            pointer.setProperty("active", active);
            pointer.setProperty("previous", previous);
            write(new File(new File(installRoot, packageName), "active.properties"), pointer);
        }

        void receipt(String packageName, String directory, int version, String sha)
            throws Exception {
            Properties receipt = new Properties();
            receipt.setProperty("schemaVersion", "1");
            receipt.setProperty("packageName", packageName);
            receipt.setProperty("version", String.valueOf(version));
            receipt.setProperty("sha256", sha);
            write(new File(new File(new File(new File(installRoot, packageName), "versions"),
                directory), ".games-component.properties"), receipt);
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

    private static final class InMemoryTasks implements ComponentTaskRepository {
        final List<ComponentTask> values = new ArrayList<>();

        @Override public List<ComponentTask> list() { return new ArrayList<>(values); }
        @Override public Optional<ComponentTask> find(String taskId) {
            return values.stream().filter(task -> taskId.equals(task.getTaskId())).findFirst();
        }
        @Override public void save(ComponentTask task) {
            values.removeIf(existing -> task.getTaskId().equals(existing.getTaskId()));
            values.add(task);
        }
    }
}
