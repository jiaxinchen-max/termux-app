package com.termux.localgames.components.delivery;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.components.ComponentDownloader;
import com.termux.localgames.components.install.ArchiveFileOperations;
import com.termux.localgames.components.install.ComponentInstaller;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

public class ComponentDeliveryCoordinatorTest {

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void recoveryIncludesOnlyIncompleteAutomaticStates() throws Exception {
        File root = temporaryFolder.newFolder();
        FileComponentTaskRepository repository = new FileComponentTaskRepository(
            new File(root, "tasks"));
        File downloads = new File(root, "downloads");
        ComponentDownloader downloader = new ComponentDownloader(repository, downloads,
            url -> { throw new IOException("network must not be used"); });
        ComponentInstaller installer = new ComponentInstaller(repository,
            new File(root, "install"), new JvmArchiveFileOperations());
        ComponentDeliveryCoordinator coordinator = new ComponentDeliveryCoordinator(repository,
            downloader, installer, downloads);

        save(repository, "1-queued", ComponentTaskState.QUEUED, 0);
        save(repository, "2-downloading", ComponentTaskState.DOWNLOADING, 5);
        save(repository, "3-verifying", ComponentTaskState.VERIFYING, 10);
        save(repository, "4-verified", ComponentTaskState.VERIFIED, 10);
        save(repository, "5-installing", ComponentTaskState.INSTALLING, 10);
        save(repository, "6-paused", ComponentTaskState.PAUSED, 5);
        save(repository, "7-failed", ComponentTaskState.FAILED, 5);
        save(repository, "8-cancelled", ComponentTaskState.CANCELLED, 5);
        save(repository, "9-installed", ComponentTaskState.INSTALLED, 10);

        assertEquals(Arrays.asList("1-queued", "2-downloading", "3-verifying",
            "4-verified", "5-installing"), coordinator.recoverableTaskIds());
    }

    @Test
    public void queuedPauseWinsBeforeWorkerRegistersControl() throws Exception {
        File root = temporaryFolder.newFolder();
        FileComponentTaskRepository repository = new FileComponentTaskRepository(
            new File(root, "tasks"));
        File downloads = new File(root, "downloads");
        ComponentDownloader downloader = new ComponentDownloader(repository, downloads,
            url -> { throw new IOException("network must not be used after queued pause"); });
        ComponentInstaller installer = new ComponentInstaller(repository,
            new File(root, "install"), new JvmArchiveFileOperations());
        ComponentDeliveryCoordinator coordinator = new ComponentDeliveryCoordinator(repository,
            downloader, installer, downloads);
        save(repository, "task-1", ComponentTaskState.QUEUED, 0);

        coordinator.prepareExecution("task-1");
        coordinator.signalPause("task-1");
        ComponentTask result = coordinator.execute("task-1", ComponentTaskListener.NONE);

        assertEquals(ComponentTaskState.PAUSED, result.getState());
    }

    private static void save(FileComponentTaskRepository repository, String id,
                             ComponentTaskState state, long bytes) throws Exception {
        ComponentTask task = ComponentTask.queued(id, "runtime-" + id, 1,
            "https://example.invalid/runtime.tar.xz", 10, SHA256);
        if (state != ComponentTaskState.QUEUED) {
            task = task.transition(state, bytes, "", "", "", "");
        }
        repository.save(task);
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
        }

        @Override
        public boolean isSymbolicLink(File file) {
            return Files.isSymbolicLink(file.toPath());
        }
    }
}
