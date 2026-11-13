package com.termux.ui.service;

import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SetupTaskSubmissionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void concurrentClicksAndNewRepositoryViewsRecoverOneQueuedSession() throws Exception {
        java.io.File directory = temporary.newFolder();
        Object lock = new Object();
        AtomicInteger ids = new AtomicInteger(), submissions = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch clicks = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) results.add(executor.submit(() -> {
                clicks.await();
                FilePrefixSetupTaskRepository repository = new FilePrefixSetupTaskRepository(directory);
                return SetupTaskSubmission.submit(lock,
                    () -> repository.list().stream().filter(t -> !t.getState().isTerminal()).findFirst().orElse(null),
                    () -> PrefixSetupTask.queued("task-" + ids.incrementAndGet(), "game", "wine", 1),
                    repository::save,
                    task -> {
                        assertEquals(task.getTaskId(), new FilePrefixSetupTaskRepository(directory).list().get(0).getTaskId());
                        submissions.incrementAndGet();
                    }, task -> fail("dispatch should succeed")).getTaskId();
            }));
            clicks.countDown();
            for (Future<String> result : results) assertEquals("task-1", result.get(5, TimeUnit.SECONDS));
            assertEquals(1, ids.get());
            assertEquals(1, submissions.get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void rejectedDispatchEndsTaskAndAnExplicitRetryGetsANewIdentity() throws Exception {
        FilePrefixSetupTaskRepository repository = new FilePrefixSetupTaskRepository(temporary.newFolder());
        Object lock = new Object();
        assertThrows(IllegalStateException.class, () -> SetupTaskSubmission.submit(lock, () -> null,
            () -> PrefixSetupTask.queued("rejected", "game", "wine", 1), repository::save,
            task -> { throw new IllegalStateException("service rejected"); },
            task -> repository.save(task.transition(RuntimeSetupTaskState.FAILED, "dispatch_failed", 2))));
        assertEquals(RuntimeSetupTaskState.FAILED, repository.find("rejected").get().getState());
        PrefixSetupTask retry = SetupTaskSubmission.submit(lock,
            () -> repository.list().stream().filter(t -> !t.getState().isTerminal()).findFirst().orElse(null),
            () -> PrefixSetupTask.queued("retry", "game", "wine", 3), repository::save,
            task -> { }, task -> fail("retry should succeed"));
        assertEquals("retry", retry.getTaskId());
    }

    @Test public void failedPersistenceNeverDispatchesAndReadFailureNeverCreatesTask() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        assertThrows(IOException.class, () -> SetupTaskSubmission.submit(new Object(), () -> null,
            () -> "task", task -> { throw new IOException("disk full"); },
            task -> starts.incrementAndGet(), task -> fail("not dispatched")));
        assertThrows(IOException.class, () -> SetupTaskSubmission.submit(new Object(),
            () -> { throw new IOException("unreadable active tasks"); },
            () -> { fail("must not create on unknown state"); return "bad"; }, task -> { },
            task -> starts.incrementAndGet(), task -> { }));
        assertEquals(0, starts.get());
    }
}
