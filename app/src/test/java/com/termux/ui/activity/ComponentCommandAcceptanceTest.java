package com.termux.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import com.termux.ui.api.ComponentTasks;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ComponentCommandAcceptanceTest {
    private static final class Host extends ContextWrapper {
        final CountDownLatch dispatched = new CountDownLatch(1);
        Intent command;
        Host() { super(RuntimeEnvironment.getApplication()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public ComponentName startForegroundService(Intent intent) {
            command = intent;
            dispatched.countDown();
            return intent.getComponent();
        }
        ResultReceiver receiver() { return command.getParcelableExtra(ComponentTasks.EXTRA_COMMAND_RECEIVER); }
    }

    @Test public void resumeWaitsUntilServiceAcceptsNewState() throws Exception {
        Host host = new Host();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> work = executor.submit(() -> {
                ComponentTasks.resumeAndAwaitAcceptance(host, "task-1", false, 5000);
                return null;
            });
            assertTrue(host.dispatched.await(5, TimeUnit.SECONDS));
            assertEquals(ComponentTasks.ACTION_RESUME, host.command.getAction());
            assertFalse(work.isDone());
            host.receiver().send(0, null);
            work.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test public void explicitRejectionIsReportedInsteadOfOldTaskFailure() throws Exception {
        Host host = new Host();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> work = executor.submit(() -> {
                try { ComponentTasks.resumeAndAwaitAcceptance(host, "task-2", true, 5000); }
                catch (IOException error) { return error.getMessage(); }
                return "unexpected success";
            });
            assertTrue(host.dispatched.await(5, TimeUnit.SECONDS));
            assertEquals(ComponentTasks.ACTION_RETRY, host.command.getAction());
            Bundle error = new Bundle();
            error.putString(ComponentTasks.EXTRA_COMMAND_ERROR, "component_install_busy");
            host.receiver().send(1, error);
            assertEquals("component_install_busy", work.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test public void missingAcceptanceFailsWithinExplicitDeadline() {
        Host host = new Host();
        IOException failure = assertThrows(IOException.class, () ->
            ComponentTasks.resumeAndAwaitAcceptance(host, "task-3", false, 10));
        assertEquals("component_command_timeout:task-3", failure.getMessage());
    }
}
