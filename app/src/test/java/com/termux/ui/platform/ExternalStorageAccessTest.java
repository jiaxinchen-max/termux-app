package com.termux.ui.platform;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = StorageTestOs.class)
public class ExternalStorageAccessTest {
    @Before public void reset() {
        ExternalStorageAccess.complete(false);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void grant() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void awaitFinish(Activity activity) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!activity.isFinishing() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        assertTrue("Permission flow must complete", activity.isFinishing());
    }

    @Test public void operationWaitsForApprovalThenRunsOnceWithRealStorageLinks() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger calls = new AtomicInteger();
            ExternalStorageAccess.ensure(owner.get(), calls::incrementAndGet);
            assertEquals(0, calls.get());
            Intent intent = Shadows.shadowOf(owner.get()).getNextStartedActivity();
            assertEquals(ExternalStoragePermissionActivity.class.getName(), intent.getComponent().getClassName());
            try (ActivityController<ExternalStoragePermissionActivity> broker = Robolectric.buildActivity(
                    ExternalStoragePermissionActivity.class, intent).setup()) {
                grant();
                broker.get().onRequestPermissionsResult(847, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    new int[] { PackageManager.PERMISSION_GRANTED });
                broker.get().onRequestPermissionsResult(847, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    new int[] { PackageManager.PERMISSION_GRANTED });
                awaitFinish(broker.get());
                assertEquals(1, calls.get());
                File storage = new File(owner.get().getFilesDir(), "home/storage");
                assertEquals(android.os.Environment.getExternalStorageDirectory().toPath(),
                    Files.readSymbolicLink(new File(storage, "shared").toPath()));
                assertTrue(SharedStorageSetup.linksPresent(owner.get()));
                assertTrue(new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Termux").isDirectory());
            }
        }
    }

    @Test public void concurrentRequestsShareOnePromptAndDenialDropsBothWithoutReplay() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger calls = new AtomicInteger();
            ExternalStorageAccess.ensure(owner.get(), calls::incrementAndGet);
            ExternalStorageAccess.ensure(owner.get(), calls::incrementAndGet);
            Intent intent = Shadows.shadowOf(owner.get()).getNextStartedActivity();
            assertNull(Shadows.shadowOf(owner.get()).getNextStartedActivity());
            try (ActivityController<ExternalStoragePermissionActivity> broker = Robolectric.buildActivity(
                    ExternalStoragePermissionActivity.class, intent).setup()) {
                // Robolectric keeps Activity launches in one application-wide queue.
                Intent systemPrompt = Shadows.shadowOf(owner.get()).getNextStartedActivity();
                assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", systemPrompt.getAction());
                broker.get().onRequestPermissionsResult(847, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    new int[] { PackageManager.PERMISSION_DENIED });
                assertTrue(broker.get().isFinishing());
                assertEquals(0, calls.get());
                assertNull(Shadows.shadowOf(owner.get()).getNextStartedActivity());
            }
            ExternalStorageAccess.ensure(owner.get(), calls::incrementAndGet);
            assertNotNull(Shadows.shadowOf(owner.get()).getNextStartedActivity());
            assertEquals(0, calls.get());
        }
    }

    @Test public void alreadyGrantedPermissionStillRepairsMissingLinksAndPreservesCustomFiles() throws Exception {
        grant();
        File storage = new File(RuntimeEnvironment.getApplication().getFilesDir(), "home/storage");
        assertTrue(storage.mkdirs());
        File custom = new File(storage, "custom.txt");
        Files.write(custom.toPath(), new byte[] {1, 2, 3});
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup()) {
            AtomicInteger calls = new AtomicInteger();
            ExternalStorageAccess.ensure(owner.get(), calls::incrementAndGet);
            Intent intent = Shadows.shadowOf(owner.get()).getNextStartedActivity();
            try (ActivityController<ExternalStoragePermissionActivity> broker = Robolectric.buildActivity(
                    ExternalStoragePermissionActivity.class, intent).setup()) {
                awaitFinish(broker.get());
                assertEquals(1, calls.get());
                assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(custom.toPath()));
            }
        }
    }

    @Test public void backgroundCommandWaitsForNotificationTapAndApprovalBeforeServiceDispatch() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Intent command = new Intent(app, com.termux.ui.service.ComponentTaskForegroundService.class)
            .setAction(com.termux.ui.api.ComponentTasks.ACTION_RETRY)
            .putExtra(com.termux.ui.api.ComponentTasks.EXTRA_TASK_ID, "waiting-component");
        assertTrue(ExternalStorageAccess.deferServiceIfNeeded(app, command));
        assertNull(Shadows.shadowOf(app).getNextStartedService());
        android.app.NotificationManager manager = (android.app.NotificationManager)
            app.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        java.util.List<android.app.Notification> notifications = Shadows.shadowOf(manager).getAllNotifications();
        assertEquals(1, notifications.size());
        notifications.get(0).contentIntent.send();
        Intent approval = Shadows.shadowOf(app).getNextStartedActivity();
        assertEquals(ExternalStoragePermissionActivity.class.getName(), approval.getComponent().getClassName());
        assertNull(Shadows.shadowOf(app).getNextStartedService());
        try (ActivityController<ExternalStoragePermissionActivity> broker = Robolectric.buildActivity(
                ExternalStoragePermissionActivity.class, approval).setup()) {
            Intent second = new Intent(command).putExtra(com.termux.ui.api.ComponentTasks.EXTRA_TASK_ID, "second-component");
            broker.get().onNewIntent(ExternalStorageAccess.serviceApprovalIntent(app, second));
            broker.get().onNewIntent(ExternalStorageAccess.serviceApprovalIntent(app, second));
            grant();
            broker.get().onRequestPermissionsResult(847, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                new int[] { PackageManager.PERMISSION_GRANTED });
            awaitFinish(broker.get());
            Intent dispatched = Shadows.shadowOf(app).getNextStartedService();
            assertNotNull(dispatched);
            assertEquals(command.getComponent(), dispatched.getComponent());
            assertEquals("waiting-component", dispatched.getStringExtra(com.termux.ui.api.ComponentTasks.EXTRA_TASK_ID));
            Intent next = Shadows.shadowOf(app).getNextStartedService();
            assertNotNull(next);
            assertEquals("second-component", next.getStringExtra(com.termux.ui.api.ComponentTasks.EXTRA_TASK_ID));
            assertNull(Shadows.shadowOf(app).getNextStartedService());
        }
    }
}
